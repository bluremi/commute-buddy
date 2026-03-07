package com.commutebuddy.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = PollingSettingsRepository(
            context.getSharedPreferences("polling_prefs", Context.MODE_PRIVATE)
        ).load()
        if (settings.enabled) {
            context.startForegroundService(Intent(context, PollingForegroundService::class.java))
        }
    }
}
