package com.ndirtsp.tvplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Lists streams found on the LAN and lets the user pick one with the D-pad (or touch). */
class SourcesActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: LinearLayout
    private lateinit var refreshButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    private var searching = false
    private var sources: List<DiscoveredSource> = emptyList()

    private val autoRefresh = Runnable { search() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sources)
        prefs = Prefs(this)

        statusView = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        list = findViewById(R.id.sources)
        refreshButton = findViewById(R.id.refresh)

        refreshButton.setOnClickListener { search() }
        findViewById<Button>(R.id.manual).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.diag).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        executor = Executors.newSingleThreadExecutor()
        search()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
        executor?.shutdownNow()
        executor = null
        searching = false
    }

    private fun search() {
        val exec = executor ?: return
        if (searching) return
        searching = true
        handler.removeCallbacks(autoRefresh)
        progress.visibility = View.VISIBLE
        if (sources.isEmpty()) statusView.text = getString(R.string.sources_searching)

        val knownHost = if (prefs.isConfigured) Uri.parse(prefs.streamUrl).host else null
        exec.execute {
            val found = Discovery.discover(extraHosts = listOfNotNull(knownHost))
            handler.post {
                if (isFinishing || executor == null) return@post
                searching = false
                progress.visibility = View.GONE
                show(found)
                handler.postDelayed(autoRefresh, AUTO_REFRESH_MS)
            }
        }
    }

    private fun show(found: List<DiscoveredSource>) {
        // Rebuilding the list moves focus elsewhere; remember whether the user was on an item.
        val focusedKey = list.findFocus()?.tag as? String
        val firstLoad = sources.isEmpty() || currentFocus == null
        sources = found
        list.removeAllViews()

        statusView.text = if (found.isEmpty()) {
            getString(R.string.sources_none)
        } else {
            resources.getQuantityString(R.plurals.sources_found, found.size, found.size)
        }

        val inflater = LayoutInflater.from(this)
        for (source in found) {
            val item = inflater.inflate(R.layout.item_source, list, false)
            item.tag = source.key
            item.findViewById<TextView>(R.id.title).text =
                getString(R.string.source_title, source.path, source.serverName)
            item.findViewById<TextView>(R.id.details).text = details(source)
            item.findViewById<View>(R.id.current).visibility =
                if (source.key == prefs.sourceKey) View.VISIBLE else View.GONE
            item.setOnClickListener { select(source) }
            list.addView(item)
        }

        if (focusedKey == null && !firstLoad) return
        val items = (0 until list.childCount).map { list.getChildAt(it) }
        val target = items.firstOrNull { it.tag == focusedKey }
            ?: items.firstOrNull { it.tag == prefs.sourceKey }
            ?: items.firstOrNull()
        (target ?: refreshButton).requestFocus()
    }

    private fun details(source: DiscoveredSource): String {
        val state = when (source.ready) {
            true -> getString(R.string.source_live)
            false -> getString(R.string.source_offline)
            null -> getString(R.string.source_unknown)
        }
        val parts = mutableListOf(state)
        if (source.width > 0) parts += "${source.width}x${source.height}"
        if (source.tracks.isNotEmpty()) parts += source.tracks.joinToString(", ")
        parts += getString(R.string.source_readers, source.readers)
        parts += getString(R.string.source_rtt, source.rttMs)
        return source.url + "\n" + parts.joinToString("  ·  ")
    }

    private fun select(source: DiscoveredSource) {
        prefs.sourceKey = source.key
        prefs.sourceName = getString(R.string.source_title, source.path, source.serverName)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, source.url)
                .putExtra(PlayerActivity.EXTRA_SOURCE_KEY, source.key)
        )
        finish()
    }

    companion object {
        private const val AUTO_REFRESH_MS = 5_000L
    }
}
