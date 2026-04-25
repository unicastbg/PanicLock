package com.security.paniclock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TelegramBot(private val context: Context) {

    companion object {
        const val KEY_BOT_TOKEN = "telegram_bot_token"
        const val KEY_CHAT_ID = "telegram_chat_id"
        const val KEY_TELEGRAM_ENABLED = "telegram_enabled"
        const val KEY_LOCATION_INTERVAL = "telegram_location_interval"
        private const val TAG = "PanicLock"
        private const val POLL_TIMEOUT_SECONDS = 25
        private const val RETRY_DELAY_MS = 3_000L
        private const val LOCATION_FIX_TIMEOUT_SECONDS = 20L
        private const val GOOD_ACCURACY_METERS = 50f
        private const val GPS_WARMUP_DELAY_MS = 2_000L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(TriggerActions.PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var polling = false
    private var pollingThread: Thread? = null
    private var lastUpdateId: Long = 0

    fun sendTriggerAlert(actions: List<String>) {
        if (!isConfigured()) return
        Thread {
            val location = getLastKnownLocation()
            val battery = getBatteryPercent()
            val sb = StringBuilder()
            sb.appendLine("PanicLock Triggered")
            sb.appendLine()
            sb.appendLine("Battery: $battery%")
            if (location != null) {
                sb.appendLine("Location: [Open in Maps](https://maps.google.com/?q=${location.latitude},${location.longitude})")
                sb.appendLine("Accuracy: +/- ${location.accuracy.toInt()}m")
            } else {
                sb.appendLine("Location: unavailable")
            }
            sb.appendLine()
            sb.appendLine("Actions fired:")
            actions.forEach { sb.appendLine("- $it") }
            sendMessage(sb.toString())
        }.start()
    }

    fun sendLocation() {
        if (!isConfigured()) return
        Thread {
            if (!hasLocationPermission()) {
                sendMessage("Location permission is not granted. Open PanicLock once and set Location to Allow all the time.")
                return@Thread
            }

            val location = getCurrentLocation()
            val battery = getBatteryPercent()
            if (location != null) {
                sendMessage(formatLocationMessage("Location Update", location, battery))
            } else {
                sendMessage("Location unavailable at this time.\nBattery: $battery%")
            }
        }.start()
    }

    fun startPolling(triggerActions: TriggerActions) {
        if (polling || !isConfigured()) return

        polling = true
        pollingThread = Thread {
            while (polling) {
                try {
                    pollCommandsOnce(triggerActions)
                } catch (e: Exception) {
                    Log.e(TAG, "Telegram poll failed: ${e.message}")
                    sleepQuietly(RETRY_DELAY_MS)
                }
            }
        }.apply {
            name = "PanicLockTelegramPoll"
            start()
        }
    }

    fun stopPolling() {
        polling = false
        pollingThread?.interrupt()
        pollingThread = null
    }

    private fun pollCommandsOnce(triggerActions: TriggerActions) {
        val token = prefs.getString(KEY_BOT_TOKEN, "") ?: return
        val url =
            "https://api.telegram.org/bot$token/getUpdates?offset=${lastUpdateId + 1}&timeout=$POLL_TIMEOUT_SECONDS"
        val response = httpGet(url, readTimeoutMs = (POLL_TIMEOUT_SECONDS + 10) * 1000) ?: return
        val json = JSONObject(response)
        if (!json.getBoolean("ok")) return

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
    }

    private fun handleCommand(command: String, triggerActions: TriggerActions) {
        when (command) {
            "/locate" -> sendLocation()

            "/ping" -> sendPingLocation(triggerActions)

            "/lock" -> {
                sendMessage("Locking screen...")
                triggerActions.remoteLock()
            }

            "/alarm" -> {
                sendMessage("Triggering panic alarm...")
                triggerActions.remotePanicAlarm()
            }

            "/silent" -> {
                sendMessage("Enabling silent mode...")
                triggerActions.remoteSilent()
            }

            "/gps" -> {
                sendMessage("Enabling GPS...")
                triggerActions.remoteGps()
            }

            "/data" -> {
                sendMessage("Enabling mobile data...")
                triggerActions.remoteMobileData()
            }

            "/status" -> {
                val battery = getBatteryPercent()
                val location = getLastKnownLocation()
                val locationStr = if (location != null) {
                    "[Maps](https://maps.google.com/?q=${location.latitude},${location.longitude})"
                } else {
                    "unavailable"
                }
                val msg = "Device Status\n" +
                    "Battery: $battery%\n" +
                    "Location: $locationStr\n" +
                    "PanicLock: active"
                sendMessage(msg)
            }

            "/help" -> {
                sendMessage(
                    "PanicLock Commands\n\n" +
                        "/locate - current GPS location\n" +
                        "/ping - briefly enable GPS, send location, then restore GPS\n" +
                        "/lock - lock the screen\n" +
                        "/alarm - trigger panic alarm\n" +
                        "/silent - enable silent mode\n" +
                        "/gps - enable GPS\n" +
                        "/data - enable mobile data\n" +
                        "/status - battery, location, status\n" +
                        "/help - show this message"
                )
            }
        }
    }

    private fun sendPingLocation(triggerActions: TriggerActions) {
        if (!isConfigured()) return

        Thread {
            if (!hasLocationPermission()) {
                sendMessage("Location permission is not granted. Open PanicLock once and set Location to Allow all the time.")
                return@Thread
            }

            val gpsWasEnabled = triggerActions.isGpsEnabled()

            try {
                if (!gpsWasEnabled) {
                    triggerActions.remoteGps()
                    sleepQuietly(GPS_WARMUP_DELAY_MS)
                }

                val location = getCurrentLocation()
                val battery = getBatteryPercent()
                if (location != null) {
                    sendMessage(formatLocationMessage("Ping Location", location, battery))
                } else {
                    sendMessage("Ping failed: location unavailable.\nBattery: $battery%")
                }
            } finally {
                if (!gpsWasEnabled) {
                    triggerActions.remoteDisableGps()
                }
            }
        }.start()
    }

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

    private fun formatLocationMessage(title: String, location: Location, battery: Int): String {
        return "$title\n" +
            "[Open in Maps](https://maps.google.com/?q=${location.latitude},${location.longitude})\n" +
            "Accuracy: +/- ${location.accuracy.toInt()}m\n" +
            "Battery: $battery%"
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
        } catch (_: Exception) {
            false
        }
    }

    private fun getLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null

        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            providers.mapNotNull { provider ->
                try {
                    lm.getLastKnownLocation(provider)
                } catch (_: Exception) {
                    null
                }
            }.maxByOrNull { it.time }
        } catch (_: Exception) {
            null
        }
    }

    private fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).filter { provider ->
            try {
                lm.isProviderEnabled(provider)
            } catch (_: Exception) {
                false
            }
        }

        if (providers.isEmpty()) return getLastKnownLocation()

        val latch = CountDownLatch(1)
        val listeners = mutableListOf<LocationListener>()
        val bestLocation = AtomicReference(getLastKnownLocation())

        try {
            providers.forEach { provider ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        bestLocation.set(chooseBetterLocation(bestLocation.get(), location))
                        if (location.accuracy <= GOOD_ACCURACY_METERS) {
                            latch.countDown()
                        }
                    }
                }
                listeners.add(listener)
                lm.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
            }

            latch.await(LOCATION_FIX_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Location request failed: ${e.message}")
        } finally {
            listeners.forEach { listener ->
                try {
                    lm.removeUpdates(listener)
                } catch (_: Exception) {
                }
            }
        }

        return bestLocation.get()
    }

    private fun hasLocationPermission(): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun chooseBetterLocation(current: Location?, candidate: Location): Location {
        if (current == null) return candidate

        val candidateIsNewer = candidate.time > current.time
        val candidateIsMoreAccurate = candidate.accuracy < current.accuracy
        val currentIsOld = System.currentTimeMillis() - current.time > TimeUnit.MINUTES.toMillis(2)

        return if (candidateIsMoreAccurate || candidateIsNewer && currentIsOld) {
            candidate
        } else {
            current
        }
    }

    private fun getBatteryPercent(): Int {
        return try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                level * 100 / scale
            } else {
                -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    private fun httpGet(urlString: String, readTimeoutMs: Int = 15000): String? {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = readTimeoutMs
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val response = reader.readText()
            reader.close()
            conn.disconnect()
            response
        } catch (e: Exception) {
            if (polling && e is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            Log.e(TAG, "HTTP GET failed: ${e.message}")
            null
        }
    }

    private fun sleepQuietly(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
