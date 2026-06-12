package com.inknironapps.lorespeak.parse

import java.io.File

/** Plain .txt: split into chapters on form-feeds or "Chapter N" headings, else one chapter. */
object TxtParser {
    private val chapterHeading = Regex("(?im)^\\s*(chapter|part|book)\\s+[\\dIVXLC]+.*$")

    fun parse(file: File): ParsedBook {
        val raw = file.readText()
        val blocks = if (raw.contains('')) {
            raw.split('')
        } else {
            splitOnHeadings(raw)
        }
        val chapters = blocks.mapIndexedNotNull { index, block ->
            val sentences = Sentencizer.split(block)
            if (sentences.isEmpty()) {
                null
            } else {
                val title = chapterHeading.find(block)?.value?.trim()?.take(80)
                    ?: "Chapter ${index + 1}"
                Chapter(title, sentences)
            }
        }
        require(chapters.isNotEmpty()) { "Text file is empty" }
        return ParsedBook(file.nameWithoutExtension, "Unknown author", chapters)
    }

    private fun splitOnHeadings(raw: String): List<String> {
        val matches = chapterHeading.findAll(raw).toList()
        if (matches.isEmpty()) return listOf(raw)
        val blocks = mutableListOf<String>()
        for ((i, match) in matches.withIndex()) {
            val start = match.range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else raw.length
            blocks += raw.substring(start, end)
        }
        if (matches.first().range.first > 0) {
            blocks.add(0, raw.substring(0, matches.first().range.first))
        }
        return blocks
    }
}

/** Markdown: drop code/images, split chapters on level 1-2 ATX headings, strip inline markers. */
object MarkdownParser {
    private val codeFence = Regex("(?s)```.*?```")
    private val image = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val link = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val heading = Regex("(?m)^(#{1,2})\\s+(.*)$")
    private val inlineMarker = Regex("[*_`>#]+")

    fun parse(file: File): ParsedBook {
        val raw = file.readText().replace(codeFence, " ").replace(image, " ")
        val headings = heading.findAll(raw).toList()

        val chapters = mutableListOf<Chapter>()
        if (headings.isEmpty()) {
            val sentences = Sentencizer.split(clean(raw))
            if (sentences.isNotEmpty()) chapters += Chapter(file.nameWithoutExtension, sentences)
        } else {
            for ((i, match) in headings.withIndex()) {
                val title = match.groupValues[2].trim()
                val bodyStart = match.range.last + 1
                val bodyEnd = if (i + 1 < headings.size) headings[i + 1].range.first else raw.length
                val sentences = Sentencizer.split(clean(raw.substring(bodyStart, bodyEnd)))
                if (sentences.isNotEmpty()) {
                    chapters += Chapter(title.ifBlank { "Chapter ${chapters.size + 1}" }, sentences)
                }
            }
        }
        require(chapters.isNotEmpty()) { "Markdown file produced no readable text" }
        return ParsedBook(file.nameWithoutExtension, "Unknown author", chapters)
    }

    private fun clean(text: String): String =
        text.replace(link, "$1").replace(inlineMarker, " ")
}
