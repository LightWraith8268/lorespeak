package com.inknironapps.lorespeak.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One book in the library, with its persisted reading position. */
data class BookRecord(
    val id: String,
    val title: String,
    val author: String,
    val format: String,
    val filePath: String,
    val totalSentences: Int,
    val totalChapters: Int,
    var chapterIndex: Int = 0,
    var sentenceIndex: Int = 0,
    var globalSentence: Int = 0,
    var voiceId: Int = 0,
    var lastOpenedAt: Long = 0L,
) {
    val progress: Float
        get() = if (totalSentences <= 0) 0f else (globalSentence.toFloat() / totalSentences).coerceIn(0f, 1f)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("author", author)
        put("format", format)
        put("filePath", filePath)
        put("totalSentences", totalSentences)
        put("totalChapters", totalChapters)
        put("chapterIndex", chapterIndex)
        put("sentenceIndex", sentenceIndex)
        put("globalSentence", globalSentence)
        put("voiceId", voiceId)
        put("lastOpenedAt", lastOpenedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = BookRecord(
            id = o.getString("id"),
            title = o.getString("title"),
            author = o.optString("author", "Unknown author"),
            format = o.getString("format"),
            filePath = o.getString("filePath"),
            totalSentences = o.getInt("totalSentences"),
            totalChapters = o.optInt("totalChapters", 1),
            chapterIndex = o.optInt("chapterIndex", 0),
            sentenceIndex = o.optInt("sentenceIndex", 0),
            globalSentence = o.optInt("globalSentence", 0),
            voiceId = o.optInt("voiceId", 0),
            lastOpenedAt = o.optLong("lastOpenedAt", 0L),
        )
    }
}

/**
 * Persists the library as a single JSON file in filesDir. Small N (personal use), so a flat file
 * beats pulling in Room/KSP. Writes are atomic (temp + rename).
 */
class LibraryStore(context: Context) {

    private val file = File(context.filesDir, "library.json")
    private val records = linkedMapOf<String, BookRecord>()

    init {
        load()
    }

    @Synchronized
    fun all(): List<BookRecord> =
        records.values.sortedByDescending { it.lastOpenedAt }

    @Synchronized
    fun get(id: String): BookRecord? = records[id]

    @Synchronized
    fun upsert(record: BookRecord) {
        records[record.id] = record
        save()
    }

    @Synchronized
    fun saveProgress(id: String, chapterIndex: Int, sentenceIndex: Int, globalSentence: Int) {
        val record = records[id] ?: return
        record.chapterIndex = chapterIndex
        record.sentenceIndex = sentenceIndex
        record.globalSentence = globalSentence
        record.lastOpenedAt = nowMillis()
        save()
    }

    @Synchronized
    fun setVoice(id: String, voiceId: Int) {
        records[id]?.let {
            it.voiceId = voiceId
            save()
        }
    }

    @Synchronized
    fun remove(id: String) {
        records.remove(id)?.let { runCatching { File(it.filePath).delete() } }
        save()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val record = BookRecord.fromJson(array.getJSONObject(i))
                records[record.id] = record
            }
        }
    }

    private fun save() {
        val array = JSONArray()
        records.values.forEach { array.put(it.toJson()) }
        val tmp = File(file.parentFile, "library.json.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    // System.currentTimeMillis is fine here; no resume-determinism concern in app code.
    private fun nowMillis() = System.currentTimeMillis()
}
