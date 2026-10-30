package com.lectorvoz.app.integration

import com.lectorvoz.core.data.pronunciation.PronunciationEntry
import com.lectorvoz.core.model.BookFormat
import com.lectorvoz.core.model.ChapterText
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.core.model.TocEntry
import com.lectorvoz.core.model.Utterance
import com.lectorvoz.core.model.VoiceSettings
import com.lectorvoz.core.text.PronunciationDictionary
import com.lectorvoz.core.text.PronunciationRule
import com.lectorvoz.core.text.TextOptions
import com.lectorvoz.core.text.UtteranceBuilder
import com.lectorvoz.format.epub.EpubBook
import com.lectorvoz.format.pdf.PdfBook
import com.lectorvoz.reader.TextProgress
import com.lectorvoz.tts.UtteranceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/** Lo que la voz puede leer de un libro abierto (opaco para la UI). */
interface SpeechSource {
    val chapterCount: Int

    /** Texto de la frase que contiene [locator] (para marcadores y la frase actual). */
    suspend fun textAt(locator: Locator): String?

    /** Rango de la frase que contiene [locator] (resaltarla al abrir el lector). */
    suspend fun rangeAt(locator: Locator): LocatorRange? = null

    /** Fraccion leida del libro en [locator] (estimada con las longitudes conocidas). */
    fun progressAt(locator: Locator): Float =
        if (chapterCount <= 0) 0f else (locator.chapter.toFloat() / chapterCount).coerceIn(0f, 1f)
}

/** Contenido de un libro abierto para la voz y la busqueda. */
sealed interface BookContent {
    val format: BookFormat
    val chapterCount: Int

    /** Texto plano del capitulo/pagina en la escala de `Locator.offset`. */
    suspend fun plainText(chapter: Int): String

    class Epub(val book: EpubBook) : BookContent {
        override val format get() = BookFormat.EPUB
        override val chapterCount get() = book.chapterCount
        override suspend fun plainText(chapter: Int): String = book.chapter(chapter).plainText
    }

    class Pdf(val book: PdfBook, val cleaner: PdfCleanerAdapter) : BookContent {
        override val format get() = BookFormat.PDF
        override val chapterCount get() = book.pageCount
        override suspend fun plainText(chapter: Int): String {
            cleaner.prefetch(chapter)
            val raw = book.pageText(chapter).rawText
            return cleaner.flowFor(chapter, raw).sameLength
        }
    }
}

/** Convierte las entradas guardadas en el diccionario de `:core:text`. */
fun dictionaryOf(entries: List<PronunciationEntry>): PronunciationDictionary =
    if (entries.none { it.enabled }) PronunciationDictionary.EMPTY
    else PronunciationDictionary(
        entries.filter { it.enabled }.map {
            PronunciationRule(it.pattern, it.replacement, it.isRegex, it.caseSensitive, it.wholeWord, it.enabled)
        },
    )

/**
 * [UtteranceProvider] de `:tts` sobre `:core:text`: EPUB con `build(ChapterDocument)` (titulos,
 * listas, notas) y PDF con el texto limpio de [PdfCleanerAdapter] (misma longitud que pdfium).
 * Cada capitulo se trocea una vez y se guarda en una LRU pequena. Los ids son los estables de
 * `UtteranceBuilder` (base por capitulo `UtteranceBuilder.idBase`), unicos en el libro.
 */
class UtteranceProviderImpl(
    private val content: BookContent,
    settings: VoiceSettings,
    entries: List<PronunciationEntry> = emptyList(),
    private val toc: List<TocEntry> = emptyList(),
) : UtteranceProvider, SpeechSource {
    private val mutex = Mutex()

    @Volatile private var settings: VoiceSettings = settings
    @Volatile private var entries: List<PronunciationEntry> = entries
    @Volatile private var builder = UtteranceBuilder(dictionary = dictionaryOf(entries))

    /** Sube con cada cambio que afecta al troceo: un capitulo hecho antes no se guarda. */
    @Volatile private var generation = 0L

    private val cache = object : LinkedHashMap<Int, List<Utterance>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Utterance>>) = size > CACHE
    }
    private val lengths = HashMap<Int, Int>()
    private val titles: Map<Int, String> = HashMap<Int, String>().also { m ->
        fun visit(list: List<TocEntry>) {
            for (e in list) { m.putIfAbsent(e.target.chapter, e.title); visit(e.children) }
        }
        visit(toc)
    }

    override val chapterCount: Int get() = content.chapterCount

    /**
     * Cambia ajustes o diccionario. Solo se vuelven a trocear los capitulos si cambia algo
     * que afecta al texto (diccionario, idioma, notas, tablas, anuncios, filtro de PDF); la
     * velocidad, el tono, la voz o las pausas se aplican en el motor sin tocar la cache.
     * Devuelve true si se vacio la cache.
     */
    suspend fun update(settings: VoiceSettings, entries: List<PronunciationEntry>): Boolean = mutex.withLock {
        val old = this.settings
        val textChanged = entries != this.entries ||
            TextOptions.from(old) != TextOptions.from(settings) ||
            old.skipPdfHeadersFooters != settings.skipPdfHeadersFooters
        this.settings = settings
        if (!textChanged) return@withLock false
        this.entries = entries
        builder = UtteranceBuilder(dictionary = dictionaryOf(entries))
        generation++
        cache.clear()
        true
    }

    private suspend fun chapter(index: Int): List<Utterance> {
        if (index !in 0 until chapterCount) return emptyList()
        val gen: Long
        val b: UtteranceBuilder
        val s: VoiceSettings
        mutex.withLock {
            cache[index]?.let { return it }
            gen = generation
            b = builder
            s = settings
        }
        // Troceo fuera del hilo de quien llama (el controlador lo pide desde el principal al
        // empezar, saltar o retroceder): un capitulo grande no debe congelar la interfaz.
        val list = withContext(Dispatchers.Default) { buildChapter(index, b, s) }
        mutex.withLock { if (generation == gen) cache[index] = list }
        return list
    }

    private suspend fun buildChapter(index: Int, b: UtteranceBuilder, s: VoiceSettings): List<Utterance> =
        when (content) {
            is BookContent.Epub -> {
                val epub = content.book
                val doc = epub.chapter(index)
                val notes = if (s.footnotes == com.lectorvoz.core.model.FootnoteMode.END_OF_PARAGRAPH) epub.footnoteTexts(index) else emptyMap()
                synchronized(lengths) { lengths[index] = doc.plainText.length }
                val path = runCatching { epub.chapterPath(index) }.getOrNull()
                b.build(doc, 0, options = TextOptions.from(s, chapterNumber = null, chapterTitle = titles[index] ?: doc.title, footnoteTexts = notes, chapterPath = path))
            }
            is BookContent.Pdf -> {
                val text = content.plainText(index)
                synchronized(lengths) { lengths[index] = text.length }
                // En PDF cada pagina es un "capitulo": sin anuncios.
                b.build(ChapterText(index, text, null, null), 0, options = TextOptions.from(s).copy(announceChapters = false))
            }
        }

    private fun chapterOf(u: Utterance): Int = (u.id ushr CHAPTER_SHIFT).toInt()

    override suspend fun at(locator: Locator): Utterance? {
        var ch = locator.chapter
        val first = chapter(ch)
        val hit = if (locator.offset == 0) first.firstOrNull()
        else first.firstOrNull { !it.range.isEmpty && it.range.endExclusive.offset > locator.offset && it.range.endExclusive.chapter == ch }
        if (hit != null) return hit
        while (++ch < chapterCount) chapter(ch).firstOrNull()?.let { return it }
        return null
    }

    override suspend fun next(current: Utterance): Utterance? {
        var ch = chapterOf(current)
        val list = chapter(ch)
        val i = indexIn(list, current)
        list.getOrNull(i + 1)?.let { return it }
        while (++ch < chapterCount) chapter(ch).firstOrNull()?.let { return it }
        return null
    }

    override suspend fun previous(current: Utterance): Utterance? {
        var ch = chapterOf(current)
        val list = chapter(ch)
        val i = indexIn(list, current)
        if (i > 0) return list[i - 1]
        while (--ch >= 0) chapter(ch).lastOrNull()?.let { return it }
        return null
    }

    /** Indice de [u] en [list]: por id (estable); si se retrocearon, por posicion. */
    private fun indexIn(list: List<Utterance>, u: Utterance): Int {
        val byId = list.indexOfFirst { it.id == u.id }
        if (byId >= 0) return byId
        return list.indexOfLast { it.range.start <= u.range.start }
    }

    override suspend fun chapterLength(chapter: Int): Int? {
        synchronized(lengths) { lengths[chapter] }?.let { return it }
        chapter(chapter)
        return synchronized(lengths) { lengths[chapter] }
    }

    override suspend fun textAt(locator: Locator): String? = at(locator)?.displayText

    override suspend fun rangeAt(locator: Locator): LocatorRange? = at(locator)?.range?.takeIf { !it.isEmpty }

    /** Progreso con las longitudes de capitulo ya conocidas (las demas, con la media). */
    override fun progressAt(locator: Locator): Float {
        val known = synchronized(lengths) { HashMap(lengths) }
        if (known.isEmpty()) return super.progressAt(locator)
        return TextProgress.compute(locator, chapterCount) { known[it] }.bookFraction
    }

    private companion object {
        const val CACHE = 4

        /** `UtteranceBuilder.idBase(capitulo)` = capitulo shl 40. */
        const val CHAPTER_SHIFT = 40
    }
}
