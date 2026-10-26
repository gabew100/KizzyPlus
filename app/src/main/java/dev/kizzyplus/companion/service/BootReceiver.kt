package dev.kizzyplus.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.kizzyplus.companion.EventLog
import dev.kizzyplus.companion.Settings

/** Brings the service back after a reboot or an app update, if sharing was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Settings(context).enabled) return
        try {
            PresenceService.start(context)
        } catch (e: Exception) {
            EventLog.add("Couldn't start after ${intent.action}: $e")
        }
    }
}
