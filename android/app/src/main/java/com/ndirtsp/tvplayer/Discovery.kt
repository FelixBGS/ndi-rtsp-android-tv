package com.ndirtsp.tvplayer

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID

data class DiscoveredSource(
    val serverId: String,
    val serverName: String,
    val host: String,
    val path: String,
    val url: String,
    /** null when the server could not ask MediaMTX (stream state unknown). */
    val ready: Boolean?,
    val tracks: List<String>,
    val readers: Int,
    val width: Int,
    val height: Int,
    val rttMs: Long,
) {
    val key: String get() = sourceKey(serverId, path)
}

fun sourceKey(serverId: String, path: String) = "$serverId/$path"

/**
 * UDP discovery of server/discovery.py instances. All calls are blocking: run them off the main thread.
 *
 * The request is broadcast to 255.255.255.255 and to every interface's directed broadcast address
 * (some routers/Wi-Fi drivers drop one or the other), plus sent unicast to [extraHosts]
 * (e.g. the last known server), which also works where broadcast is filtered.
 */
object Discovery {
    const val PORT = 8555
    private const val TAG = "Discovery"
    private const val MAX_PACKET = 8192

    fun discover(timeoutMs: Int = 1500, extraHosts: Collection<String> = emptyList()): List<DiscoveredSource> {
        val nonce = UUID.randomUUID().toString()
        val found = LinkedHashMap<String, DiscoveredSource>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 200
            val start = SystemClock.elapsedRealtime()
            val payload = JSONObject()
                .put("type", "discover").put("v", 1).put("nonce", nonce).put("t", start)
                .toString().toByteArray()

            for (target in targets(extraHosts)) {
                try {
                    socket.send(DatagramPacket(payload, payload.size, target, PORT))
                } catch (e: Exception) {
                    Log.d(TAG, "send to $target failed: ${e.message}")
                }
            }

            val buf = ByteArray(MAX_PACKET)
            while (SystemClock.elapsedRealtime() - start < timeoutMs) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val rtt = SystemClock.elapsedRealtime() - start
                val host = packet.address.hostAddress ?: continue
                try {
                    val msg = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                    if (msg.optString("type") != "announce" || msg.optString("nonce") != nonce) continue
                    for (source in parseAnnounce(msg, host, rtt)) {
                        val prev = found[source.key]
                        if (prev == null || source.rttMs < prev.rttMs) found[source.key] = source
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "bad announce from $host", e)
                }
            }
        }
        return found.values.sortedWith(compareBy({ it.ready == false }, { it.serverName }, { it.path }))
    }

    /** Round-trip time to the discovery service on [host] in ms, or null if it did not answer. */
    fun ping(host: String, timeoutMs: Int = 1000): Long? = try {
        DatagramSocket().use { socket -> pingWith(socket, host, timeoutMs) }
    } catch (_: Exception) {
        null
    }

    private fun pingWith(socket: DatagramSocket, host: String, timeoutMs: Int): Long? {
        val nonce = UUID.randomUUID().toString()
        val start = SystemClock.elapsedRealtimeNanos()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val payload = JSONObject().put("type", "ping").put("nonce", nonce).toString().toByteArray()
        socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(host), PORT))
        val buf = ByteArray(512)
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            socket.soTimeout = remaining.toInt()
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                return null
            }
            val msg = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
            if (msg.optString("type") == "pong" && msg.optString("nonce") == nonce) {
                return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
            }
        }
    }

    private fun parseAnnounce(msg: JSONObject, host: String, rtt: Long): List<DiscoveredSource> {
        val serverId = msg.getString("id")
        val serverName = msg.optString("name", host)
        val port = msg.optInt("rtsp_port", 8554)
        val sources = msg.optJSONArray("sources") ?: return emptyList()
        return (0 until sources.length()).map { i ->
            val s = sources.getJSONObject(i)
            val path = s.getString("path")
            val tracks = s.optJSONArray("tracks")
            DiscoveredSource(
                serverId = serverId,
                serverName = serverName,
                host = host,
                path = path,
                url = "rtsp://$host:$port/${Uri.encode(path, "/")}",
                ready = if (s.isNull("ready")) null else s.optBoolean("ready"),
                tracks = tracks?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                readers = s.optInt("readers"),
                width = s.optInt("width"),
                height = s.optInt("height"),
                rttMs = rtt,
            )
        }
    }

    private fun targets(extraHosts: Collection<String>): Set<InetAddress> {
        val result = LinkedHashSet<InetAddress>()
        try {
            result += InetAddress.getByName("255.255.255.255")
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is Inet4Address }
                .mapNotNullTo(result) { it.broadcast }
        } catch (e: Exception) {
            Log.w(TAG, "cannot enumerate interfaces", e)
        }
        for (host in extraHosts) {
            try {
                result += InetAddress.getByName(host)
            } catch (_: Exception) {
            }
        }
        return result
    }
}
