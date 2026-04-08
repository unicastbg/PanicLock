package com.security.paniclock

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import android.graphics.Color
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = PanicLockPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Settings"
                1 -> "Log"
                else -> ""
            }
        }.attach()

        checkRootAccess()
    }

    private fun checkRootAccess() {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo root_ok"))
            val result = process.inputStream.bufferedReader().readLine()
            process.waitFor()
            if (result?.trim() == "root_ok") {
                Toast.makeText(this, "✓ Root access confirmed", Toast.LENGTH_SHORT).show()
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c",
                        "appops set com.security.paniclock SYSTEM_ALERT_WINDOW allow"))
                } catch (e: Exception) { }
            } else {
                showNoRootWarning()
            }
        } catch (e: Exception) {
            showNoRootWarning()
        }
    }

    private fun showNoRootWarning() {
        AlertDialog.Builder(this)
            .setTitle("Root Not Available")
            .setMessage("PanicLock requires root access to lock the screen and execute trigger actions. Please make sure your device is rooted and has granted root permission to this app.")
            .setPositiveButton("OK", null)
            .show()
    }
}

// --- Pager Adapter ---

class PanicLockPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount() = 2
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> SettingsFragment()
        1 -> LogFragment()
        else -> SettingsFragment()
    }
}

// --- Settings Fragment ---

class SettingsFragment : Fragment() {

    private lateinit var prefs: SharedPreferences

    private val sensitivitySteps = listOf(12f, 18f, 25f, 35f, 50f)
    private val sensitivityLabels = listOf("Very High", "High", "Medium", "Low", "Very Low")
    private val panicDurationSteps = listOf(10, 30, 60, 120, 300)
    private val panicDurationLabels = listOf("10s", "30s", "1m", "2m", "5m")
    private val batterySteps = listOf(0, 5, 10, 15, 20, 25, 30)
    private val batteryLabels = listOf("Off", "5%", "10%", "15%", "20%", "25%", "30%")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences(TriggerActions.PREFS_NAME, Context.MODE_PRIVATE)

        setupMasterSwitch(view)
        setupSensitivitySlider(view)
        setupActionSwitches(view)
        setupKillListSection(view)
        setupPanicAlarmSection(view)
        setupNotificationSection(view)
        setupBatterySection(view)
        setupAboutButton(view)
    }

    private fun setupMasterSwitch(view: View) {
        val switch = view.findViewById<Switch>(R.id.switchMaster)
        switch.isChecked = prefs.getBoolean("service_running", false)
        switch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("service_running", isChecked).apply()
            if (isChecked) startLockService() else stopLockService()
        }
    }

    private fun startLockService() {
        val sensitivity = sensitivitySteps[prefs.getInt("sensitivity_progress", 2)]
        val intent = Intent(requireContext(), LockService::class.java).apply {
            putExtra(LockService.EXTRA_SENSITIVITY, sensitivity)
        }
        requireContext().startForegroundService(intent)
    }

    private fun stopLockService() {
        requireContext().stopService(Intent(requireContext(), LockService::class.java))
    }

    private fun restartServiceIfRunning() {
        if (prefs.getBoolean("service_running", false)) {
            stopLockService()
            startLockService()
        }
    }

    private fun setupSensitivitySlider(view: View) {
        val seekBar = view.findViewById<SeekBar>(R.id.seekBarSensitivity)
        val tvValue = view.findViewById<TextView>(R.id.tvSensitivityValue)

        val savedProgress = prefs.getInt("sensitivity_progress", 2)
        seekBar.progress = savedProgress
        tvValue.text = sensitivityLabels[savedProgress]

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvValue.text = sensitivityLabels[progress]
                prefs.edit()
                    .putInt("sensitivity_progress", progress)
                    .putFloat("sensitivity_value", sensitivitySteps[progress])
                    .apply()
                restartServiceIfRunning()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupActionSwitches(view: View) {
        setupSwitch(view, R.id.switchLock, TriggerActions.KEY_LOCK_ENABLED, true)
        setupSwitch(view, R.id.switchSilent, TriggerActions.KEY_SILENT_ENABLED, false)
        setupSwitch(view, R.id.switchGps, TriggerActions.KEY_GPS_ENABLED, false)
        setupSwitch(view, R.id.switchMobileData, TriggerActions.KEY_MOBILE_DATA_ENABLED, false)
        setupSwitch(view, R.id.switchKillApps, TriggerActions.KEY_KILL_APPS_ENABLED, false)
        setupSwitch(view, R.id.switchFakePowerMenu, "fake_power_menu_enabled", false)
    }

    private fun setupSwitch(view: View, viewId: Int, prefKey: String, default: Boolean) {
        val switch = view.findViewById<Switch>(viewId)
        switch.isChecked = prefs.getBoolean(prefKey, default)
        switch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(prefKey, isChecked).apply()
        }
    }

    private fun setupKillListSection(view: View) {
        view.findViewById<Button>(R.id.btnManageKillList).setOnClickListener {
            showKillListDialog()
        }
    }

    private fun showKillListDialog() {
        val pm = requireContext().packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        val appNames = installedApps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
        val packageNames = installedApps.map { it.packageName }
        val savedList = prefs.getString(TriggerActions.KEY_KILL_APP_LIST, "") ?: ""
        val savedPackages = savedList.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val checkedItems = BooleanArray(packageNames.size) { packageNames[it] in savedPackages }

        AlertDialog.Builder(requireContext(), android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Select Apps to Kill")
            .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                val selected = packageNames.filterIndexed { index, _ -> checkedItems[index] }
                prefs.edit()
                    .putString(TriggerActions.KEY_KILL_APP_LIST, selected.joinToString(","))
                    .apply()
                Toast.makeText(requireContext(), "${selected.size} app(s) in kill list", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupPanicAlarmSection(view: View) {
        val switchAlarm = view.findViewById<Switch>(R.id.switchPanicAlarm)
        val layoutDuration = view.findViewById<View>(R.id.layoutPanicDuration)
        val seekBar = view.findViewById<SeekBar>(R.id.seekBarPanicDuration)
        val tvDuration = view.findViewById<TextView>(R.id.tvPanicDuration)

        val savedEnabled = prefs.getBoolean(TriggerActions.KEY_PANIC_ALARM_ENABLED, false)
        val savedProgress = prefs.getInt("panic_duration_progress", 1)

        switchAlarm.isChecked = savedEnabled
        layoutDuration.visibility = if (savedEnabled) View.VISIBLE else View.GONE
        seekBar.progress = savedProgress
        tvDuration.text = panicDurationLabels[savedProgress]

        switchAlarm.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(TriggerActions.KEY_PANIC_ALARM_ENABLED, isChecked).apply()
            layoutDuration.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvDuration.text = panicDurationLabels[progress]
                prefs.edit()
                    .putInt("panic_duration_progress", progress)
                    .putInt(TriggerActions.KEY_PANIC_ALARM_DURATION, panicDurationSteps[progress])
                    .apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupNotificationSection(view: View) {
        val switch = view.findViewById<Switch>(R.id.switchHideNotification)
        switch.isChecked = prefs.getBoolean("hide_notification_icon", false)
        switch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("hide_notification_icon", isChecked).apply()
            restartServiceIfRunning()
        }
    }

    private fun setupBatterySection(view: View) {
        val seekBar = view.findViewById<SeekBar>(R.id.seekBarBattery)
        val tvThreshold = view.findViewById<TextView>(R.id.tvBatteryThreshold)

        val savedProgress = prefs.getInt("battery_stop_progress", 0)
        seekBar.progress = savedProgress
        tvThreshold.text = batteryLabels[savedProgress]

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvThreshold.text = batteryLabels[progress]
                prefs.edit()
                    .putInt("battery_stop_progress", progress)
                    .putInt("battery_stop_threshold", batterySteps[progress])
                    .apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupAboutButton(view: View) {
        view.findViewById<Button>(R.id.btnAbout).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("About PanicLock")
                .setMessage(
                    "PanicLock protects your device by locking it instantly when a shake is detected.\n\n" +
                            "HOW TO USE\n" +
                            "1. Enable Shake Detection with the master toggle.\n" +
                            "2. Adjust sensitivity to match your needs — start at Medium and tune from there.\n" +
                            "3. Choose what happens on trigger: lock screen, silent mode, GPS, mobile data, app kill list, or panic alarm.\n" +
                            "4. The app runs silently in the background and auto-starts after reboot.\n\n" +
                            "ROOT REQUIRED\n" +
                            "PanicLock requires root access to lock the screen and execute system-level actions. " +
                            "When prompted by your root manager (e.g. Magisk), please grant access.\n\n" +
                            "BATTERY\n" +
                            "The service is optimized for minimal battery use. " +
                            "You can set an auto-stop threshold under the Battery section.\n\n" +
                            "─────────────────────\n" +
                            "Developed by Svetoslav Izov\n" +
                            "with great help from Claude\n" +
                            "─────────────────────"
                )
                .setPositiveButton("Close", null)
                .show()
        }
    }
}

// --- Log Fragment ---

class LogFragment : Fragment() {

    private lateinit var prefs: SharedPreferences
    private lateinit var logContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_log, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences(TriggerActions.PREFS_NAME, Context.MODE_PRIVATE)
        logContainer = view.findViewById(R.id.logContainer)

        view.findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Clear Log")
                .setMessage("Are you sure you want to clear all log entries?")
                .setPositiveButton("Clear") { _, _ ->
                    TriggerActions(requireContext()).clearLog()
                    loadLog()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        loadLog()
    }

    override fun onResume() {
        super.onResume()
        loadLog()
    }

    private fun loadLog() {
        logContainer.removeAllViews()
        val logJson = prefs.getString("trigger_log", "[]") ?: "[]"
        val log = JSONArray(logJson)

        if (log.length() == 0) {
            val empty = TextView(requireContext()).apply {
                text = "No triggers recorded yet."
                setTextColor(Color.parseColor("#8B949E"))
                textSize = 14f
                setPadding(0, 24, 0, 0)
            }
            logContainer.addView(empty)
            return
        }

        for (i in 0 until log.length()) {
            val entry = log.getJSONObject(i)
            val timestamp = entry.getString("time")
            val actions = entry.getJSONArray("actions")

            // Card container
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#161B22"))
                setPadding(32, 24, 32, 24)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = 12
                layoutParams = params
            }

            // Timestamp
            val tvTime = TextView(requireContext()).apply {
                text = timestamp
                setTextColor(Color.WHITE)
                textSize = 15f
            }
            card.addView(tvTime)

            // Actions
            for (j in 0 until actions.length()) {
                val tvAction = TextView(requireContext()).apply {
                    text = "└ ${actions.getString(j)}"
                    setTextColor(Color.parseColor("#00D4FF"))
                    textSize = 13f
                    setPadding(0, 4, 0, 0)
                }
                card.addView(tvAction)
            }

            logContainer.addView(card)
        }
    }
}