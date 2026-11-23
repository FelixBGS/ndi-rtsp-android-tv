package com.ndirtsp.tvplayer

import android.content.Context
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Step-by-step check of subnet, discovery, RTSP port and the MediaMTX path. Blocking. */
object NetDiag {
    private const val RTSP_PORT = 8554

    fun check(context: Context, hostRaw: String, pathRaw: String): String {
        val host = StreamUrl.hostOf(hostRaw)
            ?: return context.getString(R.string.diag_bad_host)
        val path = pathRaw.trim('/').ifEmpty { "live" }
        val lines = mutableListOf<String>()
        lines += context.getString(R.string.diag_target, host)
        lines += context.getString(R.string.diag_stream, host, RTSP_PORT, path)

        val nics = localNics()
        if (nics.isEmpty()) {
            lines += context.getString(R.string.diag_no_ipv4)
        } else {
            for (nic in nics) {
                lines += context.getString(R.string.diag_phone, nic.ip, nic.prefix, nic.name)
            }
            val remote = ipv4(host)
            lines += when {
                remote == null -> context.getString(R.string.diag_subnet_skip)
                nics.none { sameSubnet(it, remote) } -> context.getString(R.string.diag_subnet_bad)
                else -> context.getString(R.string.diag_subnet_ok)
            }
        }

        lines += ""
        val rtt = Discovery.ping(host, timeoutMs = 1500)
        lines += if (rtt == null) {
            context.getString(R.string.diag_udp_fail)
        } else {
            context.getString(R.string.diag_udp_ok, rtt.toInt())
        }
        val found = try {
            Discovery.discover(timeoutMs = 1500, extraHosts = listOf(host))
        } catch (_: Exception) {
            emptyList()
        }
        for (source in found.filter { it.host == host }) {
            val state = when (source.ready) {
                true -> context.getString(R.string.source_live)
                false -> context.getString(R.string.source_offline)
                null -> context.getString(R.string.source_unknown)
            }
            lines += context.getString(R.string.diag_source_line, source.path, state)
        }
        val others = found.map { it.host }.distinct().filter { it != host }
        if (others.isNotEmpty()) {
            lines += context.getString(R.string.diag_other_host, others.joinToString())
        }

        lines += ""
        lines += rtspProbe(context, host, path)
        return lines.joinToString("\n").trimEnd()
    }

    private fun rtspProbe(context: Context, host: String, path: String): String = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, RTSP_PORT), 2500)
            socket.soTimeout = 2500
            val url = "rtsp://$host:$RTSP_PORT/$path"
            val request = "DESCRIBE $url RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            val status = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
            if (status.isNullOrBlank()) {
                context.getString(R.string.diag_tcp_open, RTSP_PORT) + "\n" +
                    context.getString(R.string.diag_rtsp_silent)
            } else {
                val code = status.split(" ").getOrNull(1)
                val detail = when (code) {
                    "200" -> context.getString(R.string.diag_rtsp_ok, path, status)
                    "404" -> context.getString(R.string.diag_rtsp_404, status)
                    "400" -> context.getString(R.string.diag_rtsp_400, status)
                    else -> context.getString(R.string.diag_rtsp_other, status)
                }
                context.getString(R.string.diag_tcp_open, RTSP_PORT) + "\n" + detail
            }
        }
    } catch (e: Exception) {
        val reason = buildString {
            append(e.javaClass.simpleName.ifBlank { "Exception" })
            val message = e.message?.trim().orEmpty()
            if (message.isNotEmpty()) {
                append(": ")
                append(message)
            }
        }
        context.getString(R.string.diag_tcp_fail, RTSP_PORT, reason)
    }

    private data class Nic(val name: String, val ip: String, val prefix: Int)

    private fun localNics(): List<Nic> {
        val out = mutableListOf<Nic>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return out
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (ia in nif.interfaceAddresses) {
                val addr = ia.address
                if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                val ip = addr.hostAddress ?: continue
                out += Nic(nif.name, ip, ia.networkPrefixLength.toInt())
            }
        }
        return out
    }

    private fun sameSubnet(nic: Nic, remote: Int): Boolean {
        val local = ipv4(nic.ip) ?: return false
        val prefix = nic.prefix
        if (prefix !in 1..32) return false
        val shift = 32 - prefix
        return (local ushr shift) == (remote ushr shift)
    }

    private fun ipv4(ip: String): Int? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val n = part.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            value = (value shl 8) or n
        }
        return value
    }
}
