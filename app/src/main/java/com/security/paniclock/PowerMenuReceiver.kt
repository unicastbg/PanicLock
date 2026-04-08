package com.security.paniclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

class PowerMenuReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return

        val prefs: SharedPreferences =
            context.getSharedPreferences(TriggerActions.PREFS_NAME, Context.MODE_PRIVATE)

        if (!prefs.getBoolean("fake_power_menu_enabled", false)) return

        val reason = intent.getStringExtra("reason") ?: return
        if (reason != "globalactions") return

        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 4"))
        } catch (e: Exception) {
            return
        }

        val overlayIntent = Intent(context, FakePowerMenuService::class.java)
        context.startService(overlayIntent)
    }
}