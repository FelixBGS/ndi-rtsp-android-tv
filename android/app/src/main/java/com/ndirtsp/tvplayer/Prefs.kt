package com.ndirtsp.tvplayer

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("player", Context.MODE_PRIVATE)

    var streamUrl: String
        get() = sp.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = sp.edit().putString(KEY_URL, value.trim()).apply()

    /** "serverId/path" of a discovered source, or null when the URL was typed in manually. */
    var sourceKey: String?
        get() = sp.getString(KEY_SOURCE, null)
        set(value) = sp.edit().putString(KEY_SOURCE, value).apply()

    var sourceName: String?
        get() = sp.getString(KEY_SOURCE_NAME, null)
        set(value) = sp.edit().putString(KEY_SOURCE_NAME, value).apply()

    /**
     * Buffer every TV holds. All TVs with the same value show the same frame (± a frame or two),
     * because the player keeps its buffer at this level by adjusting playback speed.
     */
    var targetLatencyMs: Int
        get() = sp.getInt(KEY_LATENCY, DEFAULT_LATENCY_MS)
        set(value) = sp.edit().putInt(KEY_LATENCY, value.coerceIn(MIN_LATENCY_MS, MAX_LATENCY_MS)).apply()

    var autostart: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, true)
        set(value) = sp.edit().putBoolean(KEY_AUTOSTART, value).apply()

    var muted: Boolean
        get() = sp.getBoolean(KEY_MUTED, false)
        set(value) = sp.edit().putBoolean(KEY_MUTED, value).apply()

    val isConfigured: Boolean
        get() = sp.contains(KEY_URL)

    companion object {
        const val DEFAULT_URL = "rtsp://192.168.1.10:8554/live"
        const val DEFAULT_LATENCY_MS = 500
        const val MIN_LATENCY_MS = 150
        const val MAX_LATENCY_MS = 5000

        private const val KEY_URL = "url"
        private const val KEY_SOURCE = "source_key"
        private const val KEY_SOURCE_NAME = "source_name"
        private const val KEY_LATENCY = "target_latency_ms"
        private const val KEY_AUTOSTART = "autostart"
        private const val KEY_MUTED = "muted"
    }
}
