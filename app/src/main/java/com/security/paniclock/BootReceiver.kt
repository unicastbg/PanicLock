package com.security.paniclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs: SharedPreferences =
            context.getSharedPreferences(TriggerActions.PREFS_NAME, Context.MODE_PRIVATE)

        // Only auto-start if the service was running before reboot
        val wasRunning = prefs.getBoolean("service_running", false)
        if (!wasRunning) return

        val sensitivity = prefs.getFloat("sensitivity_value", 25f)
        val serviceIntent = Intent(context, LockService::class.java).apply {
            putExtra(LockService.EXTRA_SENSITIVITY, sensitivity)
        }
        context.startForegroundService(serviceIntent)
    }
}
