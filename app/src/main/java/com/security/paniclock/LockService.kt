package com.security.paniclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class LockService : Service() {

    private lateinit var sensorManager: SensorManager
    private lateinit var shakeDetector: ShakeDetector
    private lateinit var triggerActions: TriggerActions
    private lateinit var telegramBot: TelegramBot
    private val handler = Handler(Looper.getMainLooper())
    private var accelerometer: Sensor? = null

    // Periodic location sending
    private var locationRunnable: Runnable? = null

    // Battery receiver
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra("level", 100)
            val scale = intent.getIntExtra("scale", 100)
            val percent = (level * 100 / scale)
            val threshold = getSharedPreferences(TriggerActions.PREFS_NAME, MODE_PRIVATE)
                .getInt("battery_stop_threshold", 0)
            if (threshold > 0 && percent <= threshold) {
                stopSelf()
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "PanicLockChannel2"
        const val NOTIFICATION_ID = 1
        const val EXTRA_SENSITIVITY = "sensitivity"
        const val SENSOR_RATE_US = 500000
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        triggerActions = TriggerActions(this)
        telegramBot = TelegramBot(this)

        shakeDetector = ShakeDetector {
            unregisterSensor()
            Thread {
                triggerActions.executeAll()
            }.start()
            handler.postDelayed({
                shakeDetector.isOnCooldown = false
                registerSensor()
            }, 2000)
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(TriggerActions.PREFS_NAME, MODE_PRIVATE)
        val sensitivity = intent?.getFloatExtra(EXTRA_SENSITIVITY, 25f) ?: 25f
        shakeDetector.sensitivityThreshold = sensitivity

        val hideIcon = prefs.getBoolean("hide_notification_icon", false)
        startForeground(NOTIFICATION_ID, buildNotification(hideIcon))
        registerSensor()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // Keep a long-polling connection open so commands arrive quickly.
        telegramBot.startPolling(triggerActions)

        // Start periodic location sending if configured
        startLocationUpdates(prefs)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterSensor()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        triggerActions.stopPanicAlarm()
        telegramBot.stopPolling()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerSensor() {
        accelerometer?.let {
            sensorManager.registerListener(shakeDetector, it, SENSOR_RATE_US)
        }
    }

    private fun unregisterSensor() {
        sensorManager.unregisterListener(shakeDetector)
    }

    private fun startLocationUpdates(prefs: android.content.SharedPreferences) {
        locationRunnable?.let { handler.removeCallbacks(it) }

        val intervalMinutes = prefs.getInt(TelegramBot.KEY_LOCATION_INTERVAL, 0)
        if (intervalMinutes <= 0 || !telegramBot.isConfigured()) return

        val intervalMs = intervalMinutes * 60_000L
        locationRunnable = object : Runnable {
            override fun run() {
                telegramBot.sendLocation()
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.postDelayed(locationRunnable!!, intervalMs)
    }

    private fun buildNotification(hideIcon: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PanicLock Active")
            .setContentText("Shake detection is running")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setPriority(
                if (hideIcon) NotificationCompat.PRIORITY_MIN
                else NotificationCompat.PRIORITY_LOW
            )
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PanicLock Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps shake detection running in the background"
            setSound(null, null)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
