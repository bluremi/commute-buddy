package com.commutebuddy.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d("BootReceiver", "BOOT_COMPLETED received")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("BootReceiver", "BLUETOOTH_CONNECT not granted — skipping service start")
            return
        }
        val settings = PollingSettingsRepository(
            context.getSharedPreferences("polling_prefs", Context.MODE_PRIVATE)
        ).load()
        Log.d("BootReceiver", "Polling enabled=${settings.enabled}")
        if (settings.enabled) {
            context.startForegroundService(Intent(context, PollingForegroundService::class.java))
        }
    }
}
