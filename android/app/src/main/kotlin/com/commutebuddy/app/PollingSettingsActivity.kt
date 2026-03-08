package com.commutebuddy.app

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

class PollingSettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_POLLING = "polling_prefs"
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Start the service regardless — on denial the service runs but notification is hidden
        startPollingService()
        finish()
    }

    private val exactAlarmSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        startPollingServiceWithNotificationCheck()
    }

    private lateinit var repository: PollingSettingsRepository
    private lateinit var enabledSwitch: SwitchMaterial
    private lateinit var morningStartButton: Button
    private lateinit var morningEndButton: Button
    private lateinit var eveningStartButton: Button
    private lateinit var eveningEndButton: Button
    private lateinit var intervalSlider: Slider
    private lateinit var intervalValueText: TextView

    // In-memory state for the four window boundary times
    private var morningStart = 8 to 0
    private var morningEnd = 9 to 30
    private var eveningStart = 17 to 30
    private var eveningEnd = 19 to 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_polling_settings)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        title = getString(R.string.title_polling_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val rootScrollView = findViewById<NestedScrollView>(R.id.rootScrollView)
        ViewCompat.setOnApplyWindowInsetsListener(rootScrollView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        repository = PollingSettingsRepository(
            getSharedPreferences(PREFS_POLLING, Context.MODE_PRIVATE)
        )

        enabledSwitch = findViewById(R.id.pollingEnabledSwitch)
        morningStartButton = findViewById(R.id.morningStartButton)
        morningEndButton = findViewById(R.id.morningEndButton)
        eveningStartButton = findViewById(R.id.eveningStartButton)
        eveningEndButton = findViewById(R.id.eveningEndButton)
        intervalSlider = findViewById(R.id.intervalSlider)
        intervalValueText = findViewById(R.id.intervalValueText)

        val settings = repository.load()
        enabledSwitch.isChecked = settings.enabled

        val morning = settings.windows.getOrElse(0) { CommuteWindow(8, 0, 9, 30) }
        val evening = settings.windows.getOrElse(1) { CommuteWindow(17, 30, 19, 0) }
        morningStart = morning.startHour to morning.startMinute
        morningEnd = morning.endHour to morning.endMinute
        eveningStart = evening.startHour to evening.startMinute
        eveningEnd = evening.endHour to evening.endMinute

        intervalSlider.value = settings.intervalMinutes.toFloat().coerceIn(2f, 15f)
        updateIntervalLabel(settings.intervalMinutes)

        updateButtonLabel(morningStartButton, morningStart)
        updateButtonLabel(morningEndButton, morningEnd)
        updateButtonLabel(eveningStartButton, eveningStart)
        updateButtonLabel(eveningEndButton, eveningEnd)

        morningStartButton.setOnClickListener {
            showTimePicker(morningStart) { h, m ->
                morningStart = h to m
                updateButtonLabel(morningStartButton, morningStart)
            }
        }
        morningEndButton.setOnClickListener {
            showTimePicker(morningEnd) { h, m ->
                morningEnd = h to m
                updateButtonLabel(morningEndButton, morningEnd)
            }
        }
        eveningStartButton.setOnClickListener {
            showTimePicker(eveningStart) { h, m ->
                eveningStart = h to m
                updateButtonLabel(eveningStartButton, eveningStart)
            }
        }
        eveningEndButton.setOnClickListener {
            showTimePicker(eveningEnd) { h, m ->
                eveningEnd = h to m
                updateButtonLabel(eveningEndButton, eveningEnd)
            }
        }

        intervalSlider.addOnChangeListener { _, value, _ -> updateIntervalLabel(value.toInt()) }

        findViewById<MaterialButton>(R.id.savePollingSettingsButton).setOnClickListener {
            onSaveClicked()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showTimePicker(initial: Pair<Int, Int>, onSet: (Int, Int) -> Unit) {
        TimePickerDialog(this, { _, hour, minute -> onSet(hour, minute) },
            initial.first, initial.second, false).show()
    }

    private fun updateButtonLabel(button: Button, time: Pair<Int, Int>) {
        val (h, m) = time
        val amPm = if (h < 12) "AM" else "PM"
        val displayHour = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        button.text = String.format(Locale.US, "%d:%02d %s", displayHour, m, amPm)
    }

    private fun updateIntervalLabel(minutes: Int) {
        intervalValueText.text = getString(R.string.polling_interval_value, minutes)
    }

    private fun startPollingService() {
        startForegroundService(Intent(this, PollingForegroundService::class.java))
    }

    private fun startPollingServiceWithNotificationCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return  // finish() called in permission result callback
        }
        startPollingService()
        finish()
    }

    private fun onSaveClicked() {
        val settings = PollingSettings(
            enabled = enabledSwitch.isChecked,
            windows = listOf(
                CommuteWindow(morningStart.first, morningStart.second, morningEnd.first, morningEnd.second),
                CommuteWindow(eveningStart.first, eveningStart.second, eveningEnd.first, eveningEnd.second)
            ),
            intervalMinutes = intervalSlider.value.toInt()
        )
        repository.save(settings)
        if (settings.enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = getSystemService(AlarmManager::class.java)
                if (!alarmManager.canScheduleExactAlarms()) {
                    exactAlarmSettingsLauncher.launch(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:$packageName"))
                    )
                    return  // finish() called after returning from exact alarm settings
                }
            }
            startPollingServiceWithNotificationCheck()
            return
        } else {
            stopService(Intent(this, PollingForegroundService::class.java))
        }
        finish()
    }
}
