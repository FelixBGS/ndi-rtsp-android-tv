package com.ndirtsp.tvplayer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var urlInput: EditText
    private lateinit var latencyInput: EditText
    private lateinit var autostartBox: CheckBox
    private lateinit var mutedBox: CheckBox
    private lateinit var errorView: TextView
    private lateinit var overlayButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        urlInput = findViewById(R.id.url_input)
        latencyInput = findViewById(R.id.latency_input)
        autostartBox = findViewById(R.id.autostart)
        mutedBox = findViewById(R.id.muted)
        errorView = findViewById(R.id.error)
        overlayButton = findViewById(R.id.overlay_permission)

        urlInput.setText(prefs.streamUrl)
        latencyInput.setText(prefs.targetLatencyMs.toString())
        autostartBox.isChecked = prefs.autostart
        mutedBox.isChecked = prefs.muted
        findViewById<TextView>(R.id.version).text =
            getString(R.string.settings_version, BuildConfig.VERSION_NAME)

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        overlayButton.setOnClickListener { requestOverlayPermission() }

        if (prefs.isConfigured) findViewById<Button>(R.id.save).requestFocus() else urlInput.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val needsOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)
        overlayButton.visibility = if (needsOverlay) View.VISIBLE else View.GONE
    }

    private fun save() {
        val url = StreamUrl.normalize(urlInput.text.toString())
        if (url == null) {
            showError(getString(R.string.settings_error_url))
            urlInput.requestFocus()
            return
        }
        val latency = latencyInput.text.toString().toIntOrNull()
        if (latency == null || latency !in Prefs.MIN_LATENCY_MS..Prefs.MAX_LATENCY_MS) {
            showError(getString(R.string.settings_error_latency, Prefs.MIN_LATENCY_MS, Prefs.MAX_LATENCY_MS))
            latencyInput.requestFocus()
            return
        }

        if (url != prefs.streamUrl || !prefs.isConfigured) {
            prefs.streamUrl = url
            prefs.sourceKey = null
            prefs.sourceName = null
        }
        prefs.targetLatencyMs = latency
        prefs.autostart = autostartBox.isChecked
        prefs.muted = mutedBox.isChecked

        // PlayerActivity is singleTask: this brings it to the front and restarts the stream.
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(PlayerActivity.EXTRA_RESTART, true)
        )
        finish()
    }

    private fun showError(text: String) {
        errorView.text = text
        errorView.visibility = View.VISIBLE
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Many Android TV builds have no UI for this; it has to be granted via adb.
            showError(getString(R.string.settings_overlay_adb, packageName))
        }
    }
}
