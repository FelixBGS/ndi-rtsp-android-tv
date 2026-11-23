package com.ndirtsp.tvplayer

import android.net.Uri

/**
 * Accepts a bare PC address (`192.168.0.102`) as well as a full RTSP URL.
 * A missing port becomes 8554 and a missing path becomes `/live`.
 */
object StreamUrl {
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "rtsp://$trimmed"
        val uri = Uri.parse(withScheme)
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "rtsp" && scheme != "rtsps") return null
        val host = uri.host?.trim().orEmpty()
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null
        val port = if (uri.port == -1) 8554 else uri.port
        if (port !in 1..65535) return null
        var path = uri.path ?: ""
        if (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
        if (path.isEmpty() || path == "/") path = "/live"
        if (!path.startsWith("/")) path = "/$path"
        return "$scheme://$host:$port$path"
    }

    fun hostOf(raw: String): String? = normalize(raw)?.let { Uri.parse(it).host }

    fun pathOf(raw: String): String {
        val path = normalize(raw)?.let { Uri.parse(it).path } ?: return "live"
        return path.trim('/').ifEmpty { "live" }
    }
}
