package com.inknironapps.lorespeak.parse

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

/**
 * EPUB text extractor. Chapters come from the book's TABLE OF CONTENTS (EPUB3 `nav` document or
 * EPUB2 `.ncx`) so the chapter list matches what a normal e-reader shows — front-matter like the
 * cover, title page, and copyright (which the TOC omits) is excluded. Falls back to the raw spine
 * (reading order) if no usable TOC is found.
 */
object EpubParser {

    private data class TocEntry(val title: String, val file: String)

    fun parse(file: File): ParsedBook {
        ZipFile(file).use { zip ->
            val opfPath = findOpfPath(zip) ?: error("EPUB has no OPF (not a valid epub?)")
            val opfText = zip.readEntry(opfPath) ?: error("OPF unreadable")
            val opf = Jsoup.parse(opfText, "", Parser.xmlParser())
            val opfBase = opfPath.substringBeforeLast('/', "")

            val title = opf.select("metadata > dc|title, metadata > title").firstOrNull()?.text()
                ?.ifBlank { null } ?: file.nameWithoutExtension
            val author = opf.select("metadata > dc|creator, metadata > creator").firstOrNull()?.text()
                ?.ifBlank { null } ?: "Unknown author"

            val manifest = opf.select("manifest > item")

            val toc = parseToc(zip, opf, manifest, opfBase)
            val chapters = if (toc.isNotEmpty()) {
                buildFromToc(zip, toc)
            } else {
                buildFromSpine(zip, opf, manifest, opfBase)
            }

            require(chapters.isNotEmpty()) { "EPUB produced no readable text" }
            return ParsedBook(title, author, chapters)
        }
    }

    // --- Table of contents ---------------------------------------------------------------------

    private fun parseToc(
        zip: ZipFile,
        opf: org.jsoup.nodes.Document,
        manifest: org.jsoup.select.Elements,
        opfBase: String,
    ): List<TocEntry> {
        // EPUB3: manifest item with properties="nav".
        val navItem = manifest.firstOrNull { it.attr("properties").split(" ").contains("nav") }
        if (navItem != null) {
            val navPath = resolvePath(opfBase, navItem.attr("href"))
            zip.readEntry(navPath)?.let { navText ->
                val entries = parseNavDocument(navText, navPath.substringBeforeLast('/', ""))
                if (entries.isNotEmpty()) return entries
            }
        }

        // EPUB2: ncx referenced by spine@toc, or by media-type.
        val tocId = opf.select("spine").firstOrNull()?.attr("toc")?.ifBlank { null }
        val ncxItem = manifest.firstOrNull { it.attr("id") == tocId }
            ?: manifest.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
        if (ncxItem != null) {
            val ncxPath = resolvePath(opfBase, ncxItem.attr("href"))
            zip.readEntry(ncxPath)?.let { ncxText ->
                val entries = parseNcx(ncxText, ncxPath.substringBeforeLast('/', ""))
                if (entries.isNotEmpty()) return entries
            }
        }
        return emptyList()
    }

    private fun parseNavDocument(navText: String, navBase: String): List<TocEntry> {
        val doc = Jsoup.parse(navText, "", Parser.xmlParser())
        // Prefer the nav with epub:type="toc"; else the first nav.
        val navs = doc.select("nav")
        val tocNav = navs.firstOrNull { it.attr("epub:type").contains("toc") } ?: navs.firstOrNull()
        val anchors = (tocNav ?: doc).select("a[href]")
        return collectEntries(anchors.map { it.text() to it.attr("href") }, navBase)
    }

    private fun parseNcx(ncxText: String, ncxBase: String): List<TocEntry> {
        val doc = Jsoup.parse(ncxText, "", Parser.xmlParser())
        val pairs = doc.select("navMap navPoint").map { navPoint ->
            val label = navPoint.selectFirst("navLabel > text")?.text().orEmpty()
            val src = navPoint.selectFirst("content")?.attr("src").orEmpty()
            label to src
        }
        return collectEntries(pairs, ncxBase)
    }

    /** Normalizes (title, href) pairs to file paths, in order, de-duplicated by file. */
    private fun collectEntries(pairs: List<Pair<String, String>>, base: String): List<TocEntry> {
        val seen = LinkedHashMap<String, TocEntry>()
        for ((rawTitle, rawHref) in pairs) {
            if (rawHref.isBlank()) continue
            val file = resolvePath(base, rawHref.substringBefore('#'))
            if (file.isBlank()) continue
            if (!seen.containsKey(file)) {
                val title = rawTitle.trim().ifBlank { "Chapter ${seen.size + 1}" }
                seen[file] = TocEntry(title, file)
            }
        }
        return seen.values.toList()
    }

    private fun buildFromToc(zip: ZipFile, toc: List<TocEntry>): List<Chapter> {
        val chapters = ArrayList<Chapter>()
        for (entry in toc) {
            val html = zip.readEntry(entry.file) ?: continue
            val sentences = extractSentences(html)
            if (sentences.isNotEmpty()) chapters += Chapter(entry.title, sentences)
        }
        return chapters
    }

    // --- Spine fallback ------------------------------------------------------------------------

    private fun buildFromSpine(
        zip: ZipFile,
        opf: org.jsoup.nodes.Document,
        manifest: org.jsoup.select.Elements,
        opfBase: String,
    ): List<Chapter> {
        val hrefById = manifest.associate { it.attr("id") to it.attr("href") }
        val chapters = ArrayList<Chapter>()
        for (itemref in opf.select("spine > itemref")) {
            val href = hrefById[itemref.attr("idref")] ?: continue
            val html = zip.readEntry(resolvePath(opfBase, href)) ?: continue
            val sentences = extractSentences(html)
            if (sentences.isEmpty()) continue
            val doc = Jsoup.parse(html)
            val title = doc.select("h1, h2, h3").firstOrNull()?.text()?.ifBlank { null }
                ?: "Chapter ${chapters.size + 1}"
            chapters += Chapter(title, sentences)
        }
        return chapters
    }

    // --- Shared --------------------------------------------------------------------------------

    private fun extractSentences(html: String): List<String> {
        val doc = Jsoup.parse(html)
        doc.select("nav, aside, figure, sup.footnote, .footnotes, header, footer").remove()
        val prose = doc.body()?.wholeText()?.trim().orEmpty()
        return Sentencizer.split(prose)
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val container = zip.readEntry("META-INF/container.xml") ?: return null
        val doc = Jsoup.parse(container, "", Parser.xmlParser())
        return doc.select("rootfile").firstOrNull()?.attr("full-path")?.ifBlank { null }
    }

    /** Joins [base] and a relative [href], resolving "." and ".." segments. */
    private fun resolvePath(base: String, href: String): String {
        val decoded = href.replace("%20", " ").removePrefix("./")
        val segments = ArrayDeque<String>()
        if (base.isNotEmpty() && !decoded.startsWith('/')) {
            base.split('/').filter { it.isNotEmpty() }.forEach { segments.addLast(it) }
        }
        for (part in decoded.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(part)
            }
        }
        return segments.joinToString("/")
    }

    private fun ZipFile.readEntry(path: String): String? {
        val normalized = path.removePrefix("./")
        val entry = getEntry(normalized) ?: getEntry(path) ?: return null
        return getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
