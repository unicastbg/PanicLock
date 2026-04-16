package com.security.paniclock

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TelegramBot(private val context: Context) {

    companion object {
        const val KEY_BOT_TOKEN = "telegram_bot_token"
        const val KEY_CHAT_ID = "telegram_chat_id"
        const val KEY_TELEGRAM_ENABLED = "telegram_enabled"
        const val KEY_LOCATION_INTERVAL = "telegram_location_interval"
        private const val TAG = "PanicLock"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(TriggerActions.PREFS_NAME, Context.MODE_PRIVATE)

    private var lastUpdateId: Long = 0

    // --- Outbound ---

    fun sendTriggerAlert(actions: List<String>) {
        if (!isConfigured()) return
        Thread {
            val location = getLastKnownLocation()
            val battery = getBatteryPercent()
            val sb = StringBuilder()
            sb.appendLine("🚨 *PanicLock Triggered*")
            sb.appendLine()
            sb.appendLine("🔋 Battery: $battery%")
            if (location != null) {
                sb.appendLine("📍 Location: [Open in Maps](https://maps.google.com/?q=${location.latitude},${location.longitude})")
                sb.appendLine("Accuracy: ±${location.accuracy.toInt()}m")
            } else {
                sb.appendLine("📍 Location: unavailable")
            }
            sb.appendLine()
            sb.appendLine("*Actions fired:*")
            actions.forEach { sb.appendLine("• $it") }
            sendMessage(sb.toString())
        }.start()
    }

    fun sendLocation() {
        if (!isConfigured()) return
        Thread {
            val location = getLastKnownLocation()
            val battery = getBatteryPercent()
            if (location != null) {
                val msg = "📍 *Location Update*\n" +
                        "[Open in Maps](https://maps.google.com/?q=${location.latitude},${location.longitude})\n" +
                        "Accuracy: ±${location.accuracy.toInt()}m\n" +
                        "🔋 Battery: $battery%"
                sendMessage(msg)
            } else {
                sendMessage("📍 Location unavailable at this time.\n🔋 Battery: $battery%")
            }
        }.start()
    }

    // --- Inbound command polling ---

    fun pollCommands(triggerActions: TriggerActions) {
        if (!isConfigured()) return
        Thread {
            try {
                val token = prefs.getString(KEY_BOT_TOKEN, "") ?: return@Thread
                val url = "https://api.telegram.org/bot$token/getUpdates?offset=${lastUpdateId + 1}&timeout=10"
                val response = httpGet(url) ?: return@Thread
                val json = JSONObject(response)
                if (!json.getBoolean("ok")) return@Thread

                val results = json.getJSONArray("result")
                for (i in 0 until results.length()) {
                    val update = results.getJSONObject(i)
                    lastUpdateId = update.getLong("update_id")

                    if (!update.has("message")) continue
                    val message = update.getJSONObject("message")
                    if (!message.has("text")) continue

                    val chatId = message.getJSONObject("chat").getLong("id").toString()
                    val savedChatId = prefs.getString(KEY_CHAT_ID, "") ?: ""

                    if (chatId != savedChatId) continue

                    val text = message.getString("text").trim().lowercase()
                    handleCommand(text, triggerActions)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Telegram poll failed: ${e.message}")
            }
        }.start()
    }

    private fun handleCommand(command: String, triggerActions: TriggerActions) {
        when (command) {
            "/locate" -> sendLocation()

            "/lock" -> {
                sendMessage("🔒 Locking screen...")
                triggerActions.remoteLock()
            }

            "/alarm" -> {
                sendMessage("🚨 Triggering panic alarm...")
                triggerActions.remotePanicAlarm()
            }

            "/silent" -> {
                sendMessage("🔇 Enabling silent mode...")
                triggerActions.remoteSilent()
            }

            "/gps" -> {
                sendMessage("📡 Enabling GPS...")
                triggerActions.remoteGps()
            }

            "/data" -> {
                sendMessage("📶 Enabling mobile data...")
                triggerActions.remoteMobileData()
            }

            "/status" -> {
                val battery = getBatteryPercent()
                val location = getLastKnownLocation()
                val locationStr = if (location != null)
                    "[Maps](https://maps.google.com/?q=${location.latitude},${location.longitude})"
                else "unavailable"
                val msg = "📊 *Device Status*\n" +
                        "🔋 Battery: $battery%\n" +
                        "📍 Location: $locationStr\n" +
                        "🛡 PanicLock: active"
                sendMessage(msg)
            }

            "/help" -> {
                sendMessage(
                    "🛡 *PanicLock Commands*\n\n" +
                    "/locate — current GPS location\n" +
                    "/lock — lock the screen\n" +
                    "/alarm — trigger panic alarm\n" +
                    "/silent — enable silent mode\n" +
                    "/gps — enable GPS\n" +
                    "/data — enable mobile data\n" +
                    "/status — battery, location, status\n" +
                    "/help — show this message"
                )
            }
        }
    }

    // --- Helpers ---

    fun sendMessage(text: String) {
        try {
            val token = prefs.getString(KEY_BOT_TOKEN, "") ?: return
            val chatId = prefs.getString(KEY_CHAT_ID, "") ?: return
            if (token.isEmpty() || chatId.isEmpty()) return

            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://api.telegram.org/bot$token/sendMessage" +
                    "?chat_id=$chatId&text=$encodedText&parse_mode=Markdown"
            httpGet(url)
        } catch (e: Exception) {
            Log.e(TAG, "Send message failed: ${e.message}")
        }
    }

    fun isConfigured(): Boolean {
        if (!prefs.getBoolean(KEY_TELEGRAM_ENABLED, false)) return false
        val token = prefs.getString(KEY_BOT_TOKEN, "") ?: ""
        val chatId = prefs.getString(KEY_CHAT_ID, "") ?: ""
        return token.isNotEmpty() && chatId.isNotEmpty()
    }

    fun testConnection(): Boolean {
        return try {
            val token = prefs.getString(KEY_BOT_TOKEN, "") ?: return false
            val url = "https://api.telegram.org/bot$token/getMe"
            val response = httpGet(url) ?: return false
            val json = JSONObject(response)
            json.getBoolean("ok")
        } catch (e: Exception) {
            false
        }
    }

    private fun getLastKnownLocation(): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            providers.mapNotNull { provider ->
                try { lm.getLastKnownLocation(provider) } catch (e: Exception) { null }
            }.maxByOrNull { it.time }
        } catch (e: Exception) {
            null
        }
    }

    private fun getBatteryPercent(): Int {
        return try {
            val intent = context.registerReceiver(null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        } catch (e: Exception) { -1 }
    }

    private fun httpGet(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val response = reader.readText()
            reader.close()
            conn.disconnect()
            response
        } catch (e: Exception) {
            Log.e(TAG, "HTTP GET failed: ${e.message}")
            null
        }
    }
}
