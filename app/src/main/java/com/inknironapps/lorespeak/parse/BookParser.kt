package com.inknironapps.lorespeak.parse

import java.io.File

/** Dispatches a stored book file to the right format parser. */
object BookParser {

    fun parse(file: File, format: BookFormat): ParsedBook = when (format) {
        BookFormat.EPUB -> EpubParser.parse(file)
        BookFormat.MARKDOWN -> MarkdownParser.parse(file)
        BookFormat.TXT -> TxtParser.parse(file)
    }
}
