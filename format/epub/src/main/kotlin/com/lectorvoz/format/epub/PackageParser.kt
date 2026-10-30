package com.lectorvoz.format.epub

import com.lectorvoz.core.model.BookMetadata
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Recurso del manifiesto con su ruta ya resuelta en el contenedor. */
internal data class ManifestItem(
    val id: String,
    val path: String,
    val mediaType: String,
    val properties: Set<String>,
)

/** Paquete OPF analizado. */
internal class PackageInfo(
    val opfPath: String,
    val version: String,
    val metadata: BookMetadata,
    val manifest: Map<String, ManifestItem>,
    val spine: List<ManifestItem>,
    val ncx: ManifestItem?,
    val nav: ManifestItem?,
)

/** Entrada de indice antes de convertir su destino en `Locator`. */
internal class RawTocEntry(val title: String, val path: String?, val fragment: String?, val children: List<RawTocEntry>)

/** Lectura de container.xml, OPF, nav (EPUB3), NCX (EPUB2) y encryption.xml. */
internal object PackageParser {
    /** Profundidad maxima del indice: los niveles mas hondos se descartan (evita desbordar la pila). */
    const val MAX_TOC_DEPTH = 32

    private const val CONTAINER = "META-INF/container.xml"
    private const val ENCRYPTION = "META-INF/encryption.xml"

    /** Ofuscacion de fuentes del estandar EPUB (no es DRM: el texto se puede leer). */
    private const val FONT_OBFUSCATION = "http://www.idpf.org/2008/embedding"

    /** Extensiones de fichero de fuente: ocultarlas (con cualquier metodo) no es DRM. */
    private val FONT_EXTENSIONS = setOf("ttf", "otf", "woff", "woff2")

    /**
     * Lanza [EpubException.Drm] si el libro tiene contenido cifrado. Solo cuenta lo que impide
     * leer: si lo cifrado son unicamente fuentes (ocultas para no redistribuirlas, con el metodo
     * del estandar o cualquier otro), el texto se lee igual y no es DRM.
     */
    fun checkDrm(zip: ZipContainer) {
        if (zip.exists("META-INF/license.lcpl")) throw EpubException.Drm("Libro protegido con LCP")
        val bytes = zip.readDocumentOrNull(ENCRYPTION) ?: return
        val doc = Markup.parseXml(bytes)
        val encrypted = doc.byLocalName("encrypteddata")
        for (ed in encrypted) {
            val algorithm = ed.byLocalName("encryptionmethod").firstOrNull()?.attrLocal("algorithm")?.trim().orEmpty()
            val uri = ed.byLocalName("cipherreference").firstOrNull()?.attrLocal("uri").orEmpty()
            val isFont = uri.substringAfterLast('.', "").lowercase() in FONT_EXTENSIONS
            if (algorithm != FONT_OBFUSCATION && !isFont) {
                throw EpubException.Drm("Libro protegido con DRM: recurso cifrado $uri")
            }
        }
    }

    /** Ruta del OPF segun container.xml (o el primer .opf si falta). */
    fun opfPath(zip: ZipContainer): String {
        val bytes = zip.readDocumentOrNull(CONTAINER)
        if (bytes != null) {
            val doc = Markup.parseXml(bytes)
            val rf = doc.byLocalName("rootfile").firstOrNull {
                it.attr("media-type").isEmpty() || it.attr("media-type") == "application/oebps-package+xml"
            }
            val fullPath = rf?.attr("full-path")?.trim()
            if (!fullPath.isNullOrEmpty()) {
                return EpubPaths.normalize(EpubPaths.percentDecode(fullPath)) ?: throw EpubException.UnsafePath(fullPath)
            }
        }
        return zip.names.filter { it.endsWith(".opf", ignoreCase = true) }.minOrNull()
            ?: throw EpubException.Invalid("No se encuentra el paquete OPF")
    }

    fun parsePackage(zip: ZipContainer, opfPath: String): PackageInfo {
        val doc = Markup.parseXml(zip.readDocument(opfPath))
        val pkg = doc.byLocalName("package").firstOrNull() ?: throw EpubException.Invalid("OPF sin <package>")
        val version = pkg.attr("version").trim()
        val manifest = LinkedHashMap<String, ManifestItem>()
        for (item in pkg.byLocalName("item")) {
            val id = item.attr("id").trim()
            val href = item.attr("href").trim()
            if (id.isEmpty() || href.isEmpty() || EpubPaths.isExternal(href)) continue
            // Una entrada que sale del contenedor se ignora (no se rechaza el libro entero).
            val path = try {
                EpubPaths.resolve(opfPath, href)
            } catch (e: EpubException.UnsafePath) {
                continue
            }
            val props = item.attr("properties").split(' ').filter { it.isNotBlank() }.toSet()
            manifest.putIfAbsent(id, ManifestItem(id, path, item.attr("media-type").trim().lowercase(), props))
        }
        val spineEl = pkg.byLocalName("spine").firstOrNull()
        val spine = spineEl?.byLocalName("itemref")?.mapNotNull { manifest[it.attr("idref").trim()] }.orEmpty()
            // Spine vacio: respaldo con los XHTML del manifiesto en su orden (sin el nav).
            .ifEmpty { manifest.values.filter { "html" in it.mediaType && "nav" !in it.properties } }
        if (spine.isEmpty()) throw EpubException.Invalid("El spine esta vacio")
        val ncx = spineEl?.attr("toc")?.trim()?.let { manifest[it] }
            ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        val nav = manifest.values.firstOrNull { "nav" in it.properties }
        val metadata = parseMetadata(pkg, manifest)
        return PackageInfo(opfPath, version, metadata, manifest, spine, ncx, nav)
    }

    private fun parseMetadata(pkg: Element, manifest: Map<String, ManifestItem>): BookMetadata {
        val md = pkg.byLocalName("metadata").firstOrNull() ?: pkg
        fun dc(name: String) = md.byLocalName(name).map { clean(it.text()) }.filter { it.isNotEmpty() }
        val uniqueId = pkg.attr("unique-identifier").trim()
        val identifiers = md.byLocalName("identifier")
        val identifier = (identifiers.firstOrNull { uniqueId.isNotEmpty() && it.id() == uniqueId } ?: identifiers.firstOrNull())
            ?.text()?.let { clean(it) }?.ifEmpty { null }
        val description = md.byLocalName("description").firstOrNull()?.let { el ->
            // Algunas descripciones traen HTML escapado.
            clean(Jsoup.parse(el.text()).text()).ifEmpty { null }
        }
        // Portada: EPUB3 properties="cover-image"; EPUB2 <meta name="cover" content="id">.
        val cover = manifest.values.firstOrNull { "cover-image" in it.properties }
            ?: md.byLocalName("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")?.trim()?.let { c ->
                manifest[c] ?: manifest.values.firstOrNull { it.path.endsWith(c) && it.mediaType.startsWith("image/") }
            }
            ?: manifest.values.firstOrNull {
                it.mediaType.startsWith("image/") && (it.id.equals("cover", true) || it.id.equals("cover-image", true))
            }
        return BookMetadata(
            title = dc("title").firstOrNull() ?: "",
            authors = dc("creator"),
            language = dc("language").firstOrNull(),
            publisher = dc("publisher").firstOrNull(),
            description = description,
            identifier = identifier,
            coverHref = cover?.path,
        )
    }

    /** Indice EPUB3 desde nav.xhtml. */
    fun parseNav(zip: ZipContainer, nav: ManifestItem): List<RawTocEntry> {
        val doc = Markup.parseXhtml(zip.readDocument(nav.path))
        val navs = doc.select("nav")
        val tocNav = navs.firstOrNull { el -> el.attr("epub:type").split(' ').any { it == "toc" } || el.attr("role") == "doc-toc" }
            ?: navs.firstOrNull() ?: return emptyList()
        val ol = tocNav.select("ol").firstOrNull() ?: return emptyList()
        return navList(ol, nav.path, 1)
    }

    private fun navList(ol: Element, base: String, depth: Int): List<RawTocEntry> {
        val out = ArrayList<RawTocEntry>()
        for (li in ol.children()) {
            if (li.localName() != "li") continue
            val label = li.children().firstOrNull { it.localName() == "a" || it.localName() == "span" }
            val title = clean(label?.text() ?: li.ownText())
            val href = label?.takeIf { it.localName() == "a" }?.attr("href")?.trim().orEmpty()
            val sub = if (depth >= MAX_TOC_DEPTH) {
                emptyList()
            } else {
                li.children().firstOrNull { it.localName() == "ol" }?.let { navList(it, base, depth + 1) }.orEmpty()
            }
            out.add(entry(title, href, base, sub))
        }
        return out
    }

    /** Indice EPUB2 desde el NCX (anidado). */
    fun parseNcx(zip: ZipContainer, ncx: ManifestItem): List<RawTocEntry> {
        val doc = Markup.parseXml(zip.readDocument(ncx.path))
        val navMap = doc.byLocalName("navmap").firstOrNull() ?: return emptyList()
        return ncxPoints(navMap, ncx.path, 1)
    }

    private fun ncxPoints(parent: Element, base: String, depth: Int): List<RawTocEntry> =
        parent.childrenByLocalName("navpoint").map { np ->
            val label = np.childrenByLocalName("navlabel").firstOrNull()?.childrenByLocalName("text")?.firstOrNull()?.text().orEmpty()
            val src = np.childrenByLocalName("content").firstOrNull()?.attr("src")?.trim().orEmpty()
            val sub = if (depth >= MAX_TOC_DEPTH) emptyList() else ncxPoints(np, base, depth + 1)
            entry(clean(label), src, base, sub)
        }

    private fun entry(title: String, href: String, base: String, children: List<RawTocEntry>): RawTocEntry {
        if (href.isEmpty() || EpubPaths.isExternal(href)) return RawTocEntry(title, null, null, children)
        val frag = EpubPaths.percentDecode(href.substringAfter('#', "")).ifEmpty { null }
        val path = if (href.startsWith("#")) {
            base
        } else {
            try {
                EpubPaths.resolve(base, href)
            } catch (e: EpubException.UnsafePath) {
                null
            }
        }
        return RawTocEntry(title, path, frag, children)
    }

    private val WS = Regex("\\s+")

    private fun clean(s: String): String = WS.replace(s, " ").trim()
}
