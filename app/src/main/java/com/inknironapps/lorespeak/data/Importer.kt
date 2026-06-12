package com.inknironapps.lorespeak.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.inknironapps.lorespeak.parse.BookFormat
import com.inknironapps.lorespeak.parse.BookParser
import com.inknironapps.lorespeak.parse.ParsedBook
import java.io.File

/** Copies a picked document into app storage and registers it in the library. */
class Importer(private val context: Context, private val store: LibraryStore) {

    /** Returns the new record, or throws with a readable message on failure. */
    fun import(uri: Uri, defaultVoiceId: Int = 0): BookRecord {
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "book"
        val format = BookFormat.fromName(displayName)
            ?: error("Unsupported file type (use .epub, .md, or .txt)")

        val id = generateId()
        val ext = displayName.substringAfterLast('.', format.extensions.first())
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val dest = File(booksDir, "$id.$ext")

        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not read the selected file")

        val parsed = try {
            BookParser.parse(dest, format)
        } catch (t: Throwable) {
            dest.delete()
            throw IllegalStateException("Could not parse the book: ${t.message}", t)
        }

        val record = BookRecord(
            id = id,
            title = parsed.title,
            author = parsed.author,
            format = format.name,
            filePath = dest.absolutePath,
            totalSentences = parsed.totalSentences,
            totalChapters = parsed.chapters.size,
            voiceId = defaultVoiceId,
            lastOpenedAt = System.currentTimeMillis(),
        )
        store.upsert(record)
        return record
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

    // Avoid Math.random / Date.now style nondeterminism warnings — nanoTime is fine for an id.
    private fun generateId(): String =
        (System.currentTimeMillis().toString(36) + "_" + System.nanoTime().toString(36)).take(20)

    companion object {
        fun parseStored(record: BookRecord): ParsedBook {
            val format = BookFormat.valueOf(record.format)
            return BookParser.parse(File(record.filePath), format)
        }
    }
}
