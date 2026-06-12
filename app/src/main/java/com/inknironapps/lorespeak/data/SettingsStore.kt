package com.inknironapps.lorespeak.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** App-wide defaults applied to newly imported books. Tiny JSON file in filesDir. */
class SettingsStore(context: Context) {

    private val file = File(context.filesDir, "settings.json")

    var defaultVoiceId: Int = 0
        private set
    var defaultSpeed: Float = 1.25f
        private set

    init {
        if (file.exists()) {
            runCatching {
                val o = JSONObject(file.readText())
                defaultVoiceId = o.optInt("defaultVoiceId", 0)
                defaultSpeed = o.optDouble("defaultSpeed", 1.25).toFloat()
            }
        }
    }

    fun setDefaultVoice(id: Int) {
        defaultVoiceId = id
        save()
    }

    fun setDefaultSpeed(speed: Float) {
        defaultSpeed = speed
        save()
    }

    private fun save() {
        val o = JSONObject()
            .put("defaultVoiceId", defaultVoiceId)
            .put("defaultSpeed", defaultSpeed.toDouble())
        val tmp = File(file.parentFile, "settings.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }
}
