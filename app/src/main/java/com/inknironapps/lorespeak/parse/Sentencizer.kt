package com.inknironapps.lorespeak.parse

import java.text.BreakIterator
import java.util.Locale

/** Splits prose into speakable sentences using the JVM BreakIterator (handles abbreviations). */
object Sentencizer {

    fun split(text: String): List<String> {
        val clean = text.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()

        val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH)
        iterator.setText(clean)

        val sentences = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val sentence = clean.substring(start, end).trim()
            if (sentence.isNotEmpty()) {
                // Kokoro chokes near ~500 phoneme tokens; hard-split very long sentences on commas.
                if (sentence.length > 400) {
                    sentences += hardSplit(sentence)
                } else {
                    sentences += sentence
                }
            }
            start = end
            end = iterator.next()
        }
        return sentences
    }

    private fun hardSplit(sentence: String): List<String> {
        val parts = mutableListOf<String>()
        val buffer = StringBuilder()
        for (clause in sentence.split(Regex("(?<=[,;:])\\s+"))) {
            if (buffer.length + clause.length > 350 && buffer.isNotEmpty()) {
                parts += buffer.toString().trim()
                buffer.setLength(0)
            }
            buffer.append(clause).append(' ')
        }
        if (buffer.isNotBlank()) parts += buffer.toString().trim()
        return parts
    }
}
