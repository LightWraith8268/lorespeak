package com.inknironapps.lorespeak.parse

/** A book parsed into chapters of ready-to-speak sentences. */
data class ParsedBook(
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
) {
    val totalSentences: Int get() = chapters.sumOf { it.sentences.size }
}

data class Chapter(
    val title: String,
    val sentences: List<String>,
)

enum class BookFormat(val extensions: List<String>) {
    EPUB(listOf("epub")),
    MARKDOWN(listOf("md", "markdown")),
    TXT(listOf("txt", "text")),
    ;

    companion object {
        fun fromName(name: String): BookFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}
