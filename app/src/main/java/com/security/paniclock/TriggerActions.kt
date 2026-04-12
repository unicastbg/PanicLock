package com.security.paniclock

import android.content.Context
import android.content.SharedPreferences
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log

class TriggerActions(private val context: Context) {

    companion object {
        const val PREFS_NAME = "paniclock_prefs"
        const val KEY_LOCK_ENABLED = "lock_enabled"
        const val KEY_GPS_ENABLED = "gps_enabled"
        const val KEY_MOBILE_DATA_ENABLED = "mobile_data_enabled"
        const val KEY_SILENT_ENABLED = "silent_enabled"
        const val KEY_KILL_APPS_ENABLED = "kill_apps_enabled"
        const val KEY_KILL_APP_LIST = "kill_app_list"
        const val KEY_PANIC_ALARM_ENABLED = "panic_alarm_enabled"
        const val KEY_PANIC_ALARM_DURATION = "panic_alarm_duration"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var mediaPlayer: MediaPlayer? = null
    private var panicHandler: Handler? = null
    private var strobeRunnable: Runnable? = null

    fun executeAll() {
        Log.d("PanicLock", "Shake detected — executing trigger actions")

        val actions = mutableListOf<String>()

        if (prefs.getBoolean(KEY_SILENT_ENABLED, false)) {
            enableSilentMode()
            actions.add("Silent mode enabled")
        }
        if (prefs.getBoolean(KEY_GPS_ENABLED, false)) {
            enableGps()
            actions.add("GPS enabled")
        }
        if (prefs.getBoolean(KEY_MOBILE_DATA_ENABLED, false)) {
            enableMobileData()
            actions.add("Mobile data enabled")
        }
        if (prefs.getBoolean(KEY_KILL_APPS_ENABLED, false)) {
            killApps()
            actions.add("Apps killed")
        }
        if (prefs.getBoolean(KEY_PANIC_ALARM_ENABLED, false)) {
            startPanicAlarm()
            actions.add("Panic alarm triggered")
        }
        if (prefs.getBoolean(KEY_LOCK_ENABLED, true)) {
            lockScreen()
            actions.add("Screen locked")
        }

        writeLogEntry(actions)

        // Send Telegram alert
        TelegramBot(context).sendTriggerAlert(actions)
    }

    // --- Remote commands from Telegram ---

    fun remoteLock() {
        lockScreen()
    }

    fun remotePanicAlarm() {
        startPanicAlarm()
    }

    fun remoteSilent() {
        enableSilentMode()
    }

    // --- Log ---

    private fun writeLogEntry(actions: List<String>) {
        try {
            val timestamp = java.text.SimpleDateFormat(
                "dd MMM yyyy  HH:mm:ss",
                java.util.Locale.getDefault()
            ).format(java.util.Date())

            val entry = org.json.JSONObject().apply {
                put("time", timestamp)
                put("actions", org.json.JSONArray(actions))
            }

            val existing = prefs.getString("trigger_log", "[]") ?: "[]"
            val log = org.json.JSONArray(existing)

            val newLog = org.json.JSONArray()
            newLog.put(entry)
            for (i in 0 until minOf(log.length(), 9)) {
                newLog.put(log.get(i))
            }

            prefs.edit().putString("trigger_log", newLog.toString()).apply()
        } catch (e: Exception) {
            Log.e("PanicLock", "Failed to write log: ${e.message}")
        }
    }

    fun clearLog() {
        prefs.edit().putString("trigger_log", "[]").apply()
    }

    // --- Actions ---

    private fun lockScreen() {
        executeRootCommand("input keyevent 26")
    }

    private fun enableGps() {
        try {
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                executeRootCommand("settings put secure location_mode 3")
            }
        } catch (e: Exception) {
            Log.e("PanicLock", "GPS enable failed: ${e.message}")
        }
    }

    private fun enableMobileData() {
        executeRootCommand("svc data enable")
    }

    fun enableSilentMode() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        } catch (e: Exception) {
            Log.e("PanicLock", "Silent mode failed: ${e.message}")
        }
    }

    private fun killApps() {
        val killListJson = prefs.getString(KEY_KILL_APP_LIST, "") ?: return
        if (killListJson.isEmpty()) return
        val packages = killListJson.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        for (pkg in packages) {
            executeRootCommand("am force-stop $pkg")
        }
    }

    private fun startPanicAlarm() {
        val durationMs = (prefs.getInt(KEY_PANIC_ALARM_DURATION, 30) * 1000).toLong()

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
        } catch (e: Exception) {
            Log.e("PanicLock", "Volume set failed: ${e.message}")
        }

        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("PanicLock", "Alarm sound failed: ${e.message}")
        }

        try {
            val cameraManager =
                context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            var torchOn = false
            panicHandler = Handler(Looper.getMainLooper())
            strobeRunnable = object : Runnable {
                override fun run() {
                    torchOn = !torchOn
                    cameraManager.setTorchMode(cameraId, torchOn)
                    panicHandler?.postDelayed(this, 150)
                }
            }
            panicHandler?.post(strobeRunnable!!)
        } catch (e: Exception) {
            Log.e("PanicLock", "Strobe failed: ${e.message}")
        }

        panicHandler?.postDelayed({ stopPanicAlarm() }, durationMs)
    }

    fun stopPanicAlarm() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("PanicLock", "Stop alarm failed: ${e.message}")
        }
        try {
            val cameraManager =
                context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            strobeRunnable?.let { panicHandler?.removeCallbacks(it) }
            cameraManager.setTorchMode(cameraId, false)
        } catch (e: Exception) {
            Log.e("PanicLock", "Stop strobe failed: ${e.message}")
        }
        panicHandler?.removeCallbacksAndMessages(null)
        panicHandler = null
    }

    private fun executeRootCommand(command: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.waitFor()
        } catch (e: Exception) {
            Log.e("PanicLock", "Root command failed [$command]: ${e.message}")
        }
    }
}
