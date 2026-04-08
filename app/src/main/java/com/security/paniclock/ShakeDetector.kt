package com.security.paniclock

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class ShakeDetector(
    private val onShakeDetected: () -> Unit
) : SensorEventListener {

    var sensitivityThreshold: Float = 25f
    private val cooldownMs: Long = 2000
    private var lastShakeTime: Long = 0
    var isOnCooldown: Boolean = false

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        if (isOnCooldown) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val acceleration = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH

        if (acceleration > sensitivityThreshold) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > cooldownMs) {
                lastShakeTime = now
                isOnCooldown = true
                onShakeDetected()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}