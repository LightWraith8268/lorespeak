package com.inknironapps.lorespeak.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: String, val downloadUrl: String, val assetName: String)

/**
 * Sideloaded-flavor update checker (Ink & Iron "Pipeline B"). Polls a public releases mirror for the
 * latest debug APK, compares against the installed version, downloads it, and hands it to the system
 * package installer. The main repo is private, so releases are mirrored to a dedicated public repo
 * for unauthenticated polling.
 */
object UpdateChecker {

    private const val RELEASES_API =
        "https://api.github.com/repos/LightWraith8268/lorespeak-releases/releases/latest"

    /** Returns an update if the mirror's latest version is newer than [currentVersion], else null. */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGet(RELEASES_API) ?: return@runCatching null
            val release = JSONObject(body)
            val version = release.optString("tag_name").removePrefix("v").trim()
            if (version.isEmpty() || !isNewer(version, currentVersion)) return@runCatching null

            val assets = release.optJSONArray("assets") ?: return@runCatching null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith("-debug.apk")) {
                    val url = asset.optString("browser_download_url")
                    if (url.isNotEmpty()) return@runCatching UpdateInfo(version, url, name)
                }
            }
            null
        }.getOrNull()
    }

    /** Streams the APK to cacheDir/updates, reporting 0..100. Returns the file, or null on failure. */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "update.apk")
            val connection = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LoreSpeak")
            }
            connection.inputStream.use { input ->
                val totalBytes = connection.contentLengthLong
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) onProgress((downloaded * 100 / totalBytes).toInt())
                    }
                }
            }
            connection.disconnect()
            target
        }.getOrNull()
    }

    /** Hands the downloaded APK to the system package installer. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun httpGet(urlString: String): String? {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "LoreSpeak")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (connection.responseCode != 200) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Compares dotted-integer versions; true if [remote] > [local]. */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
