package com.ndirtsp.tvplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

/**
 * Full-screen kiosk player for a single RTSP stream.
 *
 * - RTP over TCP (interleaved): survives Wi-Fi packet loss and NAT/firewalls better than UDP.
 * - Jitter buffer = ExoPlayer's buffer, held at [Prefs.targetLatencyMs] by a speed controller
 *   (0.9x..1.25x). Every TV pulls the same stream from the same server, so TVs that hold the same
 *   amount of buffered media show the same frame: this is what keeps the screens in sync.
 *   Late frames are dropped by ExoPlayer's video renderer; the count is shown in the overlay.
 * - Reconnects forever with backoff on errors, stream end, long stalls and when the network
 *   comes back. If the stream was picked from discovery and keeps failing, the source is looked
 *   up again by server id, so a PC that got a new IP from DHCP is followed automatically.
 *
 * Remote: OK -> source list, Menu -> settings, Up / Info -> stats overlay, Down -> mute.
 * Touch (phones): tap -> source list, long press -> stats overlay.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var net: ScheduledExecutorService? = null

    private var started = false
    private var retryAttempt = 0
    private var reconnectCount = 0
    private var currentSpeed = 1f
    private var syncErrorMs = 0L
    private var bufferedMs = 0L
    private var reconnectPending = false
    private var rediscovering = false
    private var rttMs: Long? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var statsAt = 0L
    private var statsRendered = 0
    private var statsRxBytes = 0L
    private var fps = 0f
    private var bitrateMbps: Float? = null

    private val reconnectRunnable = Runnable { startPlayback() }
    private val stallRunnable = Runnable {
        Log.w(TAG, "Stalled for ${STALL_TIMEOUT_MS}ms, reconnecting")
        scheduleReconnect(getString(R.string.status_stalled))
    }
    private val syncRunnable = object : Runnable {
        override fun run() {
            adjustSync()
            handler.postDelayed(this, SYNC_TICK_MS)
        }
    }
    private val statsRunnable = object : Runnable {
        override fun run() {
            sampleStats()
            updateStats()
            handler.postDelayed(this, STATS_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        prefs = Prefs(this)
        playerView = findViewById(R.id.player_view)
        statusView = findViewById(R.id.status)
        statsView = findViewById(R.id.stats)

        playerView.useController = false
        playerView.keepScreenOn = true
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

        findViewById<View>(R.id.root).apply {
            setOnClickListener { openSources() }
            setOnLongClickListener { toggleStats(); true }
        }

        applyProvisioningExtras(intent)
        if (!prefs.isConfigured) openSources()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // May arrive after onStart (Android delivers it once the activity is already started).
        if (applyProvisioningExtras(intent) && started) {
            restartPlayback()
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        hideSystemUi()
        net = Executors.newSingleThreadScheduledExecutor().also { exec ->
            exec.scheduleWithFixedDelay({ pingServer() }, 0, PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
        registerNetworkCallback()
        startPlayback()
        handler.post(syncRunnable)
        handler.post(statsRunnable)
    }

    override fun onStop() {
        super.onStop()
        started = false
        handler.removeCallbacksAndMessages(null)
        net?.shutdownNow()
        net = null
        rediscovering = false
        unregisterNetworkCallback()
        releasePlayer()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                openSources()
                return true
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_INFO -> {
                toggleStats()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                prefs.muted = !prefs.muted
                player?.volume = if (prefs.muted) 0f else 1f
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openSources() {
        startActivity(Intent(this, SourcesActivity::class.java))
    }

    private fun toggleStats() {
        statsView.visibility = if (statsView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        updateStats()
    }

    /**
     * Source list / settings hand over via extras; admins can also configure boxes remotely:
     * `adb shell am start -n com.ndirtsp.tvplayer/.PlayerActivity --es url rtsp://... --ei latency 500`.
     */
    private fun applyProvisioningExtras(intent: Intent?): Boolean {
        intent ?: return false
        var changed = intent.getBooleanExtra(EXTRA_RESTART, false)
        intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }?.let { url ->
            prefs.streamUrl = url
            val key = intent.getStringExtra(EXTRA_SOURCE_KEY)
            prefs.sourceKey = key
            if (key == null) prefs.sourceName = null
            changed = true
        }
        if (intent.hasExtra(EXTRA_LATENCY)) {
            prefs.targetLatencyMs = intent.getIntExtra(EXTRA_LATENCY, Prefs.DEFAULT_LATENCY_MS)
            changed = true
        }
        return changed
    }

    private fun buildPlayer(): ExoPlayer {
        val target = prefs.targetLatencyMs
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ target,
                /* maxBufferMs = */ maxOf(target * 4, 2000),
                /* bufferForPlaybackMs = */ maxOf(target / 2, 100),
                /* bufferForPlaybackAfterRebufferMs = */ target,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .also { p ->
                p.addListener(playerListener)
                p.volume = if (prefs.muted) 0f else 1f
                p.playWhenReady = true
            }
    }

    private fun startPlayback() {
        handler.removeCallbacks(reconnectRunnable)
        reconnectPending = false
        if (!prefs.isConfigured) {
            showStatus(getString(R.string.status_no_source))
            return
        }
        val url = prefs.streamUrl
        if (!url.startsWith("rtsp://", ignoreCase = true) && !url.startsWith("rtsps://", ignoreCase = true)) {
            showStatus(getString(R.string.status_bad_url, url))
            return
        }

        val p = player ?: buildPlayer().also {
            player = it
            playerView.player = it
        }

        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(RTSP_TIMEOUT_MS)
            .createMediaSource(MediaItem.fromUri(url))

        currentSpeed = 1f
        syncErrorMs = 0
        bufferedMs = 0
        p.playbackParameters = PlaybackParameters.DEFAULT
        p.setMediaSource(source)
        p.prepare()
        showStatus(getString(R.string.status_connecting, prefs.sourceName ?: url))
        armStallWatchdog()
    }

    private fun restartPlayback() {
        retryAttempt = 0
        releasePlayer()
        startPlayback()
    }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
        playerView.player = null
    }

    private fun scheduleReconnect(reason: String) {
        handler.removeCallbacks(stallRunnable)
        handler.removeCallbacks(reconnectRunnable)
        player?.stop()
        val delay = min(MAX_RETRY_DELAY_MS, INITIAL_RETRY_DELAY_MS shl min(retryAttempt, 5))
        retryAttempt++
        reconnectCount++
        showStatus(getString(R.string.status_retry, reason, delay / 1000.0))
        reconnectPending = true
        handler.postDelayed(reconnectRunnable, delay)
        if (retryAttempt >= REDISCOVER_AFTER_ATTEMPTS) rediscover()
    }

    /** The PC may have a new IP (DHCP): look the selected source up again by its server id. */
    private fun rediscover() {
        val key = prefs.sourceKey ?: return
        val exec = net ?: return
        if (rediscovering) return
        rediscovering = true
        val knownHost = Uri.parse(prefs.streamUrl).host
        exec.execute {
            val match = Discovery.discover(extraHosts = listOfNotNull(knownHost)).firstOrNull { it.key == key }
            handler.post {
                rediscovering = false
                if (match == null || match.url == prefs.streamUrl || net == null) return@post
                Log.i(TAG, "Source $key moved: ${prefs.streamUrl} -> ${match.url}")
                prefs.streamUrl = match.url
                if (reconnectPending) {
                    retryAttempt = 0
                    startPlayback()
                }
            }
        }
    }

    private fun pingServer() {
        if (!prefs.isConfigured) return
        val host = Uri.parse(prefs.streamUrl).host ?: return
        val rtt = Discovery.ping(host)
        handler.post { rttMs = rtt }
    }

    private fun armStallWatchdog() {
        handler.removeCallbacks(stallRunnable)
        handler.postDelayed(stallRunnable, STALL_TIMEOUT_MS)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    handler.removeCallbacks(stallRunnable)
                    retryAttempt = 0
                    hideStatus()
                }
                Player.STATE_BUFFERING -> armStallWatchdog()
                Player.STATE_ENDED -> scheduleReconnect(getString(R.string.status_ended))
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback error ${error.errorCodeName}", error)
            scheduleReconnect(describe(error))
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updateStats()
    }

    private fun describe(error: PlaybackException): String {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val headline = when {
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                causes.any { it is ConnectException || it is SocketTimeoutException || it is UnknownHostException } ->
                getString(R.string.error_network)
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                causes.any { it.message?.contains("404") == true } ->
                getString(R.string.error_not_found)
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                getString(R.string.error_decoder)
            else -> getString(R.string.error_generic, error.errorCodeName)
        }
        val detail = causes.asSequence().drop(1)
            .map { throwable ->
                val msg = throwable.message?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
                val name = throwable.javaClass.simpleName.ifBlank { "Exception" }
                when {
                    msg.isEmpty() -> name
                    msg.contains(name) -> msg
                    else -> "$name: $msg"
                }
            }
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)
            .joinToString("\n")
            .take(400)
        return if (detail.isBlank() || headline.contains(detail)) headline else "$headline\n$detail"
    }

    /**
     * Holds the buffer at the target: faster when behind (buffer too big), slower when ahead.
     * RTSP live streams are not seekable, so changing speed is the only way to move along the stream.
     */
    private fun adjustSync() {
        val p = player ?: return
        if (p.playbackState != Player.STATE_READY || !p.isPlaying) return
        bufferedMs = p.totalBufferedDuration
        val error = bufferedMs - prefs.targetLatencyMs
        syncErrorMs = error
        val wanted = if (abs(error) <= SYNC_DEADBAND_MS) {
            1f
        } else {
            (1f + error / SYNC_GAIN_MS).coerceIn(1f - MAX_SLOWDOWN, 1f + MAX_SPEEDUP)
        }
        if (abs(wanted - currentSpeed) >= 0.01f || (wanted == 1f && currentSpeed != 1f)) {
            currentSpeed = wanted
            p.playbackParameters = PlaybackParameters(wanted)
        }
    }

    private fun sampleStats() {
        val now = SystemClock.elapsedRealtime()
        val rendered = player?.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount } ?: 0
        val rx = TrafficStats.getUidRxBytes(Process.myUid())
        val dt = (now - statsAt) / 1000f
        if (statsAt != 0L && dt > 0f) {
            fps = if (rendered >= statsRendered) (rendered - statsRendered) / dt else 0f
            bitrateMbps = if (rx >= 0 && statsRxBytes >= 0 && rx >= statsRxBytes) {
                (rx - statsRxBytes) * 8 / dt / 1_000_000f
            } else {
                null
            }
        }
        statsAt = now
        statsRendered = rendered
        statsRxBytes = rx
    }

    private fun updateStats() {
        if (statsView.visibility != View.VISIBLE) return
        val p = player
        val size = p?.videoSize
        val dropped = p?.videoDecoderCounters?.droppedBufferCount ?: 0
        statsView.text = getString(
            R.string.stats_template,
            prefs.sourceName ?: getString(R.string.stats_manual_source),
            prefs.streamUrl,
            size?.width ?: 0,
            size?.height ?: 0,
            fps,
            p?.videoFormat?.sampleMimeType ?: "-",
            bitrateMbps?.let { getString(R.string.stats_mbps, it) } ?: "—",
            rttMs?.let { getString(R.string.stats_ms, it) } ?: getString(R.string.stats_no_rtt),
            bufferedMs,
            prefs.targetLatencyMs,
            syncErrorMs,
            currentSpeed,
            dropped,
            reconnectCount,
            if (prefs.muted) getString(R.string.stats_muted) else "",
        )
    }

    private fun showStatus(text: String) {
        statusView.text = text
        statusView.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusView.visibility = View.GONE
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    if (reconnectPending) {
                        Log.i(TAG, "Network available, reconnecting now")
                        retryAttempt = 0
                        startPlayback()
                    }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: RuntimeException) {
        }
        networkCallback = null
    }

    companion object {
        private const val TAG = "RtspTvPlayer"

        const val EXTRA_URL = "url"
        const val EXTRA_SOURCE_KEY = "source_key"
        const val EXTRA_LATENCY = "latency"
        const val EXTRA_RESTART = "restart"

        private const val RTSP_TIMEOUT_MS = 8_000L
        private const val STALL_TIMEOUT_MS = 10_000L
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 5_000L
        private const val REDISCOVER_AFTER_ATTEMPTS = 3
        private const val PING_INTERVAL_MS = 2_000L
        private const val SYNC_TICK_MS = 250L
        private const val STATS_TICK_MS = 1_000L

        /** Buffer deviation tolerated without touching speed (about one frame plus measurement noise). */
        private const val SYNC_DEADBAND_MS = 40L
        /** 200 ms off target -> 10% speed change. */
        private const val SYNC_GAIN_MS = 2000f
        private const val MAX_SLOWDOWN = 0.10f
        private const val MAX_SPEEDUP = 0.25f
    }
}
