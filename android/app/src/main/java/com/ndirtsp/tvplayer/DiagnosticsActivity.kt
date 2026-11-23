package com.ndirtsp.tvplayer

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.util.concurrent.Executors

/** Lets the user type the PC's LAN address and see which hop (subnet, UDP, TCP, RTSP) fails. */
class DiagnosticsActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diag)
        val prefs = Prefs(this)
        val input = findViewById<EditText>(R.id.host_input)
        val result = findViewById<TextView>(R.id.result)
        val run = findViewById<Button>(R.id.run)

        if (prefs.isConfigured) {
            input.setText(Uri.parse(prefs.streamUrl).host.orEmpty())
        }
        val check = {
            if (!running) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(input.windowToken, 0)
                running = true
                run.isEnabled = false
                result.text = getString(R.string.diag_working)
                val typed = input.text.toString()
                val path = if (typed.contains("://") || typed.contains("/")) {
                    StreamUrl.pathOf(typed)
                } else {
                    "live"
                }
                executor.execute {
                    val text = try {
                        NetDiag.check(applicationContext, typed, path)
                    } catch (e: Exception) {
                        e.javaClass.simpleName + ": " + (e.message ?: "")
                    }
                    handler.post {
                        if (isFinishing) return@post
                        running = false
                        run.isEnabled = true
                        result.text = text
                    }
                }
            }
        }
        run.setOnClickListener { check() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                check()
                true
            } else {
                false
            }
        }
        if (input.text.isNullOrEmpty()) input.requestFocus() else run.requestFocus()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
