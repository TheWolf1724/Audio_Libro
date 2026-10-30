package com.lectorvoz.core.text

import com.google.common.truth.Truth.assertWithMessage
import com.lectorvoz.core.model.ChapterText
import com.lectorvoz.core.model.FootnoteMode
import com.lectorvoz.core.model.ReadingLanguage
import com.lectorvoz.core.model.Utterance
import org.junit.Test
import java.io.File

/**
 * Banco de frases dificiles (ficheros .txt de `src/test/resources/banco`) con su salida esperada.
 *
 * Formato de cada fichero: casos separados por una linea en blanco. `#` comenta; `@ clave=valor`
 * fija opciones (`lang`, `readingLanguage`, `footnotes`, `readTables`, `announce=N:Titulo`);
 * `> texto` es la entrada (`\n` = salto de linea); cada `= TIPO|PAPEL|PAUSA|idioma|texto hablado`
 * es una unidad esperada, en orden.
 *
 * Para regenerar la salida (y revisarla a mano) se ejecuta con la variable de entorno
 * `BANCO_SALIDA=<directorio>`: escribe ahi los ficheros con la salida real.
 */
class BancoTest {
    private val builder = UtteranceBuilder()

    private class Case(val options: Map<String, String>, val input: String, val expected: List<String>, val comments: List<String>)

    private fun parse(text: String): List<Case> {
        val out = ArrayList<Case>()
        for (block in text.split(Regex("\\n\\s*\\n"))) {
            val opts = LinkedHashMap<String, String>()
            var input: String? = null
            val exp = ArrayList<String>()
            val comments = ArrayList<String>()
            for (line in block.lines()) {
                when {
                    line.startsWith("#") -> comments.add(line)
                    line.startsWith("@ ") -> line.substring(2).trim().split(' ').forEach {
                        val k = it.substringBefore('=')
                        opts[k] = it.substringAfter('=', "")
                    }
                    line.startsWith("> ") -> input = line.substring(2)
                    line.startsWith("= ") -> exp.add(line.substring(2))
                }
            }
            if (input != null) out.add(Case(opts, input!!, exp, comments))
        }
        return out
    }

    private fun run(c: Case): List<Utterance> {
        val text = c.input.replace("\\n", "\n")
        var o = TextOptions()
        c.options["readingLanguage"]?.let { o = o.copy(readingLanguage = ReadingLanguage.valueOf(it)) }
        c.options["footnotes"]?.let { o = o.copy(footnotes = FootnoteMode.valueOf(it)) }
        c.options["readTables"]?.let { o = o.copy(readTables = it.toBoolean()) }
        c.options["announce"]?.let {
            o = o.copy(announceChapters = true, chapterNumber = it.substringBefore(':').toIntOrNull(), chapterTitle = it.substringAfter(':', "").replace('_', ' ').ifEmpty { null })
        }
        return builder.build(ChapterText(0, text, null, c.options["lang"] ?: "es"), idBase = 0, options = o)
    }

    private fun format(u: Utterance) = "${u.kind}|${u.role}|${u.pauseAfter}|${u.language}|${u.spokenText}"

    private fun files(): List<File> {
        val dir = File(javaClass.classLoader.getResource("banco")!!.toURI())
        return dir.listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }
    }

    @Test
    fun bancoDeFrasesDificiles() {
        val salida = System.getenv("BANCO_SALIDA")?.let { File(it).apply { mkdirs() } }
        var total = 0
        val errors = StringBuilder()
        for (f in files()) {
            val cases = parse(f.readText())
            val regenerated = StringBuilder()
            for (c in cases) {
                val actual = run(c).map { format(it) }
                total++
                c.comments.forEach { regenerated.append(it).append('\n') }
                if (c.options.isNotEmpty()) regenerated.append("@ ").append(c.options.entries.joinToString(" ") { "${it.key}=${it.value}" }).append('\n')
                regenerated.append("> ").append(c.input).append('\n')
                actual.forEach { regenerated.append("= ").append(it).append('\n') }
                regenerated.append('\n')
                if (salida == null && actual != c.expected) {
                    errors.append("${f.name}: ${c.input}\n  esperado: ${c.expected.joinToString("\n            ")}\n  obtenido: ${actual.joinToString("\n            ")}\n")
                }
            }
            salida?.let { File(it, f.name).writeText(regenerated.toString().trimEnd() + "\n") }
        }
        assertWithMessage("casos del banco").that(total).isAtLeast(30)
        if (errors.isNotEmpty()) throw AssertionError("Diferencias en el banco:\n$errors")
    }

    @Test
    fun propiedadesDeTodoElBanco() {
        for (f in files()) for (c in parse(f.readText())) {
            val text = c.input.replace("\\n", "\n")
            for (u in run(c)) {
                if (u.range.isEmpty) continue
                assertWithMessage(c.input).that(u.displayText).isEqualTo(text.substring(u.range.start.offset, u.range.endExclusive.offset))
                SpanishNormalizerTest.assertMapValid(MappedText(u.spokenText, u.spokenToDisplay), u.displayText.length)
                val last = u.spokenText.trimEnd().last()
                val allowed = if (u.pauseAfter == com.lectorvoz.core.model.PauseKind.CLAUSE) ".,;:?!…" else ".?!…,"
                assertWithMessage(u.spokenText).that(last in allowed).isTrue()
            }
        }
    }
}
