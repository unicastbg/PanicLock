package com.security.paniclock

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class FakePowerMenuService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showFakeMenu()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showFakeMenu() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Build the fake menu programmatically
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(0, 8, 0, 8)
        }

        // Title
        val title = TextView(this).apply {
            text = "Power options"
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
            setPadding(48, 24, 48, 8)
        }
        layout.addView(title)

        // Divider
        layout.addView(makeDivider())

        // Power off button — does nothing
        val btnPowerOff = makeMenuButton("Power off") {
            // Intentionally empty — button does nothing
        }
        layout.addView(btnPowerOff)

        layout.addView(makeDivider())

        // Restart button — does nothing
        val btnRestart = makeMenuButton("Restart") {
            // Intentionally empty — button does nothing
        }
        layout.addView(btnRestart)

        layout.addView(makeDivider())

        // Cancel button — dismisses the overlay
        val btnCancel = makeMenuButton("Cancel") {
            stopSelf()
        }
        layout.addView(btnCancel)

        // Overlay params — covers full screen, touchable
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        // Dim background — full screen touch interceptor
        val dimLayout = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            addView(layout)
        }

        val dimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        // Tap outside to dismiss
        dimLayout.setOnClickListener {
            stopSelf()
        }

        // Prevent taps on menu from dismissing
        layout.setOnClickListener { }

        overlayView = dimLayout
        windowManager?.addView(dimLayout, dimParams)
    }

    private fun makeMenuButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(48, 36, 48, 36)
            setOnClickListener { onClick() }
        }
    }

    private fun makeDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
        }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
            overlayView = null
        } catch (e: Exception) {
            // Already removed
        }
    }
}