package com.ndirtsp.tvplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Opens the player after the box boots.
 *
 * On Android 10+ starting an Activity from the background is only allowed when the app
 * holds SYSTEM_ALERT_WINDOW ("display over other apps"); see README for the adb command.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        if (!Prefs(context).autostart) return

        Log.i("RtspTvPlayer", "Boot completed, starting player")
        context.startActivity(
            Intent(context, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }
}
