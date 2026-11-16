package com.example.watertracker

import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.provider.Settings
import android.view.WindowManager
import android.graphics.Typeface
import android.widget.Switch
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.app.AlertDialog
import android.app.TimePickerDialog
import java.util.Calendar
import android.os.Bundle
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.FrameLayout
import android.net.Uri
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SensorEventListener {
    private var totalMl = 0
    private val dailyGoalMl = 2000
    private lateinit var totalText: TextView
    private lateinit var progressText: TextView
    private lateinit var locationText: TextView
    private lateinit var historyText: TextView
    private var speechRecognizer: SpeechRecognizer? = null
    private var manualEntryTime = Calendar.getInstance()
    private var locationTaggingEnabled = false
    private lateinit var screenDimOverlay: android.view.View
    private lateinit var blueFilterOverlay: android.view.View
    private lateinit var cameraPreview: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraStarted = false
    private lateinit var sensorManager: SensorManager
    private var lastMotionThemeUpdate = 0L

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else toast("Camera permission was not granted.")
    }
    private val sleepActivityPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableSleepActivityTheme()
        else toast("Physical Activity permission is required for Muraqaba Theme. Your current theme is unchanged.")
    }
    private val sleepThemeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (getSharedPreferences("water_tracker", MODE_PRIVATE).getString("theme", "") == "muraqaba") {
                applyThemePreference()
            }
        }
    }
    private var sleepThemeReceiverRegistered = false
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startVoiceInput() else toast("Microphone permission was not granted.")
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        updateLocationStatus()
        if (results.values.none { it }) toast("Location access was not granted.")
    }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) {
        if (it != null) toast("Photo captured successfully.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemePreference()
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        applyKeepScreenAwake()
        loadTodayTotal()
        buildUi()
    }

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(30))
            setBackgroundColor(android.graphics.Color.rgb(242, 248, 252))
        }

        val hero = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(26).toFloat()
            cardElevation = dp(5).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(android.graphics.Color.rgb(13, 92, 153))
            layoutParams = LinearLayout.LayoutParams(-1, dp(150)).apply { bottomMargin = dp(18) }
        }
        val heroContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(18), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(android.graphics.Color.rgb(5, 105, 175), android.graphics.Color.rgb(0, 150, 160))
            ).apply { cornerRadius = dp(26).toFloat() }
        }
        val logo = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_water_tracker)
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginEnd = dp(16) }
            contentDescription = "Water Tracker icon"
        }
        heroContent.addView(logo)
        val heroText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroText.addView(TextView(this).apply {
            text = "Water Tracker"
            textSize = 27f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        heroText.addView(TextView(this).apply {
            text = "Small sips. Better habits."
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(224, 245, 255))
            setPadding(0, dp(5), 0, 0)
        })
        heroContent.addView(heroText)
        hero.addView(heroContent)
        page.addView(hero)

        val summary = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = dp(3).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(android.graphics.Color.rgb(218, 233, 243))
            setCardBackgroundColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
        }
        val summaryContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
        }
        summaryContent.addView(TextView(this).apply {
            text = "TODAY'S HYDRATION"
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(android.graphics.Color.rgb(65, 119, 150))
            setTypeface(typeface, Typeface.BOLD)
        })
        totalText = TextView(this).apply {
            textSize = 30f
            setTextColor(android.graphics.Color.rgb(20, 55, 78))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(3))
        }
        summaryContent.addView(totalText)
        progressText = TextView(this).apply {
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(83, 112, 130))
            setPadding(0, 0, 0, dp(14))
        }
        summaryContent.addView(progressText)
        waterProgress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(0, 150, 180))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(225, 239, 246))
            progress = 0
        }
        summaryContent.addView(waterProgress, LinearLayout.LayoutParams(-1, dp(10)))
        summary.addView(summaryContent)
        page.addView(summary)
        refreshWaterDisplay()

        addSectionTitle(page, "Quick add", "Choose the amount you just drank.")
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val add250 = createActionButton("＋ 250 ml") { addWater(250) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(6) }
        }
        val add500 = createActionButton("＋ 500 ml") { addWater(500) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(6) }
        }
        quickRow.addView(add250)
        quickRow.addView(add500)
        page.addView(quickRow)
        addButton(page, "＋  Manual water entry", secondary = true) { showManualWaterEntry() }
        addButton(page, "↶  Undo last entry", secondary = true) { undoLastWaterEntry() }
        addButton(page, "Reset today's total", secondary = true) {
            totalMl = 0
            saveTodayEntries(JSONArray())
            saveTodayTotal()
            refreshWaterDisplay()
            refreshHistoryDisplay()
        }
        historyText = TextView(this).apply {
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(76, 103, 120))
            setPadding(dp(6), dp(6), dp(6), dp(8))
        }
        page.addView(historyText)
        addButton(page, "View daily history", secondary = true) { showDailyHistory() }
        refreshHistoryDisplay()

        addSectionTitle(page, "Voice logging", "Log your water hands-free.")
        addButton(page, "🎙  Speak to log water") { requestVoice() }

        addSectionTitle(page, "Camera tools", "Use the camera only when you choose.")
        addButton(page, "▦  Scan bottle barcode / QR code") {
            if (hasPermission(Manifest.permission.CAMERA)) startCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        addButton(page, "📷  Take a water bottle photo", secondary = true) {
            if (hasPermission(Manifest.permission.CAMERA)) photoPicker.launch(null)
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        cameraPreview = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(210))
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        val previewCard = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(-1, dp(210)).apply { topMargin = dp(6) }
            addView(cameraPreview)
        }
        page.addView(previewCard)

        addSectionTitle(page, "Location tagging", "Optionally save the place with each water entry. Location is not collected unless you enable this.")
        locationText = TextView(this).apply {
            text = "Location access has not been requested."
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(76, 103, 120))
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        page.addView(locationText)
        page.addView(Switch(this).apply {
            text = "Save location with entries"
            isChecked = getSharedPreferences("water_tracker", MODE_PRIVATE).getBoolean("location_tagging", false)
            setTextColor(android.graphics.Color.rgb(35, 75, 99))
            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putBoolean("location_tagging", enabled).apply()
                locationTaggingEnabled = enabled
                if (enabled) {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                } else {
                    toast("Location tagging disabled.")
                }
            }
        })
        addButton(page, "Manage Location permission in Android Settings", secondary = true) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        updateLocationStatus()

        addSectionTitle(page, "Themes", "Choose how the app adapts to your surroundings.")
        addButton(page, "☾  Muraqaba theme – sleep activity sensor") {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                toast("Muraqaba Theme requires Android 10 or later.")
            } else if (hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                enableSleepActivityTheme()
            } else {
                sleepActivityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        val muraqabaInfo = TextView(this).apply {
            text = "Awake 0–20: Light  •  Asleep 80–100: Dark  •  21–79: keep previous theme. Uses activity recognition, not the camera."
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(83, 112, 130))
            setPadding(dp(4), dp(4), dp(4), dp(10))
        }
        page.addView(muraqabaInfo)
        addButton(page, "Room light – ambient light sensor", secondary = true) { saveThemeMode("room_light") }
        addButton(page, "Weather based – choose current weather", secondary = true) { showWeatherThemePicker() }
        addButton(page, "Time based – configure schedule", secondary = true) { showTimeThemeOptions() }
        addButton(page, "Day–night based", secondary = true) { saveThemeMode("day_night") }
        addButton(page, "Choice preference – Light or Dark", secondary = true) { showChoiceThemePicker() }
        addButton(page, "Motion detection based", secondary = true) { saveThemeMode("motion") }
        val themeMode = getSharedPreferences("water_tracker", MODE_PRIVATE).getString("theme", "system")
        page.addView(TextView(this).apply {
            text = "Current theme: ${themeMode?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "System"}"
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(83, 112, 130))
            setPadding(dp(4), dp(6), dp(4), dp(10))
        })

        addSectionTitle(page, "Display & accessibility", "Adjust the app's appearance and readability.")
        addSectionTitle(page, "Screen dim", "Turn the app screen dimming on or off.")
        page.addView(Switch(this).apply {
            text = "Dim screen"
            isChecked = getSharedPreferences("water_tracker", MODE_PRIVATE).getBoolean("screen_dim_enabled", false)
            setTextColor(android.graphics.Color.rgb(35, 75, 99))
            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putBoolean("screen_dim_enabled", enabled).apply()
                applyScreenDim(enabled)
            }
        })
        addSectionTitle(page, "Blue light filter", "Apply a gentle warm tint while using Water Tracker.")
        page.addView(Switch(this).apply {
            text = "Blue light filter"
            isChecked = getSharedPreferences("water_tracker", MODE_PRIVATE).getBoolean("blue_filter_enabled", false)
            setTextColor(android.graphics.Color.rgb(35, 75, 99))
            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putBoolean("blue_filter_enabled", enabled).apply()
                blueFilterOverlay.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
            }
        })
        addButton(page, "Color correction / color filters", secondary = true) {
            toast("Enable color correction in Android accessibility settings.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addSectionTitle(page, "Font size")
        addButton(page, "Smaller font", secondary = true) { changeFontSize(-0.1f) }
        addButton(page, "Larger font", secondary = true) { changeFontSize(0.1f) }
        addButton(page, "Reset font size", secondary = true) { getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putFloat("font_scale", 1f).apply(); recreate() }
        addSectionTitle(page, "Font style")
        addButton(page, "Default font", secondary = true) { getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putString("font_style", "default").apply(); recreate() }
        addButton(page, "Serif font", secondary = true) { getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putString("font_style", "serif").apply(); recreate() }
        addButton(page, "Sans-serif font", secondary = true) { getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putString("font_style", "sans").apply(); recreate() }
        addButton(page, "Toggle bold text", secondary = true) { val p = getSharedPreferences("water_tracker", MODE_PRIVATE); p.edit().putBoolean("bold_text", !p.getBoolean("bold_text", false)).apply(); recreate() }
        addSectionTitle(page, "Screen orientation")
        addButton(page, "Portrait", secondary = true) { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        addButton(page, "Landscape", secondary = true) { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        addButton(page, "Auto (follow device)", secondary = true) { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        addSectionTitle(page, "Screen awake setting")
        page.addView(Switch(this).apply {
            text = "Keep screen awake while Water Tracker is visible"
            isChecked = getSharedPreferences("water_tracker", MODE_PRIVATE).getBoolean("keep_screen_awake", true)
            setTextColor(android.graphics.Color.rgb(35, 75, 99))
            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putBoolean("keep_screen_awake", enabled).apply()
                applyKeepScreenAwake()
                toast(if (enabled) "Screen will stay awake while Water Tracker is visible." else "Normal Android screen timeout will apply.")
            }
        })
        addSectionTitle(page, "App updates")
        page.addView(Switch(this).apply {
            text = "Automatic updates (disabled)"
            isChecked = false
            isEnabled = false
            setTextColor(android.graphics.Color.rgb(35, 75, 99))
        })
        page.addView(TextView(this).apply {
            text = "Updates are manual so you can review changes and permissions before installing."
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(83, 112, 130))
            setPadding(dp(4), dp(6), dp(4), dp(12))
        })
        applyDisplayPreferences(page)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, 0)
            addView(page)
        }
        val root = FrameLayout(this)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        screenDimOverlay = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            alpha = 0.32f
            visibility = android.view.View.GONE
            isClickable = false
            isFocusable = false
        }
        blueFilterOverlay = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(255, 174, 76))
            alpha = 0.16f
            visibility = android.view.View.GONE
            isClickable = false
            isFocusable = false
        }
        root.addView(screenDimOverlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(blueFilterOverlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        screenDimOverlay.visibility = if (getSharedPreferences("water_tracker", MODE_PRIVATE).getBoolean("screen_dim_enabled", false)) android.view.View.VISIBLE else android.view.View.GONE
        blueFilterOverlay.visibility = if (getSharedPreferences("water_tracker", MODE_PRIVATE).getBoolean("blue_filter_enabled", false)) android.view.View.VISIBLE else android.view.View.GONE
    }

    private lateinit var waterProgress: android.widget.ProgressBar

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createActionButton(label: String, action: () -> Unit): com.google.android.material.button.MaterialButton =
        com.google.android.material.button.MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            cornerRadius = dp(16)
            insetTop = 0
            insetBottom = 0
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(0, 125, 166))
            elevation = dp(2).toFloat()
            setOnClickListener { action() }
        }

    private fun applyThemePreference() {
        val p = getSharedPreferences("water_tracker", MODE_PRIVATE)
        val mode = p.getString("theme", "system") ?: "system"
        val dark = when (mode) {
            "dark", "choice_dark" -> true
            "light", "choice_light" -> false
                        "viewing" -> false
            // Sleep API confidence uses 0–20 for Awake and 80–100 for Asleep.
            // The 21–79 middle band retains the last decided theme.
            "muraqaba" -> p.getBoolean("sleep_theme_dark", false)
            "eye_view" -> false // Legacy setting from older camera-based builds.
            "time" -> {
                if (p.getBoolean("time_all_day", false)) p.getBoolean("time_all_day_dark", false)
                else {
                    val now = Calendar.getInstance()
                    val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    val start = p.getInt("time_dark_start", 19 * 60)
                    val end = p.getInt("time_dark_end", 7 * 60)
                    if (start == end) p.getBoolean("time_all_day_dark", false)
                    else if (start < end) current !in start until end
                    else current >= start || current < end
                }
            }
            "day_night" -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY) !in 6..17
            "weather" -> p.getString("weather_condition", "clear") in setOf("cloudy", "rain", "storm", "snow")
            "room_light" -> p.getBoolean("last_room_dark", false)
            "motion" -> false
            else -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                return
            }
        }
        AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
    }

    private fun saveThemeMode(mode: String) {
        getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putString("theme", mode).apply()
        applyThemePreference()
        recreate()
    }

    private fun showTimeThemeOptions() {
        val p = getSharedPreferences("water_tracker", MODE_PRIVATE)
        AlertDialog.Builder(this).setTitle("Time-based theme")
            .setItems(arrayOf("Light theme for 24 hours", "Dark theme for 24 hours", "Set Light and Dark time periods")) { _, choice ->
                when (choice) {
                    0 -> {
                        p.edit().putString("theme", "time").putBoolean("time_all_day", true)
                            .putBoolean("time_all_day_dark", false).apply()
                        applyThemePreference(); recreate()
                    }
                    1 -> {
                        p.edit().putString("theme", "time").putBoolean("time_all_day", true)
                            .putBoolean("time_all_day_dark", true).apply()
                        applyThemePreference(); recreate()
                    }
                    2 -> configureTimeRange()
                }
            }.show()
    }

    private fun configureTimeRange() {
        val p = getSharedPreferences("water_tracker", MODE_PRIVATE)
        val startDefault = p.getInt("time_dark_start", 19 * 60)
        val endDefault = p.getInt("time_dark_end", 7 * 60)
        TimePickerDialog(this, { _, startHour, startMinute ->
            val start = startHour * 60 + startMinute
            val endPicker = TimePickerDialog(this, { _, endHour, endMinute ->
                val end = endHour * 60 + endMinute
                p.edit().putString("theme", "time").putBoolean("time_all_day", false)
                    .putInt("time_dark_start", start).putInt("time_dark_end", end).apply()
                applyThemePreference()
                toast("Schedule saved: Dark ${formatClock(start)}–${formatClock(end)}; Light for the remaining time.")
                recreate()
            }, endDefault / 60, endDefault % 60, true)
            endPicker.setTitle("Choose when Dark theme ends")
            endPicker.show()
        }, startDefault / 60, startDefault % 60, true).apply {
            setTitle("Choose when Dark theme starts")
            show()
        }
    }

    private fun formatClock(minutes: Int): String =
        String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60)

    private fun showChoiceThemePicker() {
        AlertDialog.Builder(this).setTitle("Choice preference")
            .setItems(arrayOf("Light", "Dark")) { _, which -> saveThemeMode(if (which == 0) "choice_light" else "choice_dark") }
            .show()
    }

    private fun showWeatherThemePicker() {
        AlertDialog.Builder(this).setTitle("Select current weather")
            .setMessage("Choose the current condition manually. This avoids automatic weather requests and mobile-data use.")
            .setItems(arrayOf("Sunny / clear", "Cloudy", "Rainy", "Stormy", "Snowy")) { _, which ->
                val condition = arrayOf("clear", "cloudy", "rain", "storm", "snow")[which]
                getSharedPreferences("water_tracker", MODE_PRIVATE).edit()
                    .putString("weather_condition", condition).putString("theme", "weather").apply()
                applyThemePreference()
                recreate()
            }.show()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val mode = getSharedPreferences("water_tracker", MODE_PRIVATE).getString("theme", "") ?: ""
        if (mode == "room_light" && event.sensor.type == Sensor.TYPE_LIGHT) {
            val darkRoom = (event.values.firstOrNull() ?: 0f) < 40f
            getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putBoolean("last_room_dark", darkRoom).apply()
            applyThemePreference()
        } else if (mode == "motion" && event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val now = System.currentTimeMillis()
            if (now - lastMotionThemeUpdate > 1500) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
                val movementDetected = kotlin.math.abs(magnitude - SensorManager.GRAVITY_EARTH) > 1.0f
                if (movementDetected) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
                lastMotionThemeUpdate = now
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun registerThemeSensor() {
        val mode = getSharedPreferences("water_tracker", MODE_PRIVATE).getString("theme", "") ?: ""
        val type = when (mode) {
            "room_light" -> Sensor.TYPE_LIGHT
            "motion" -> Sensor.TYPE_ACCELEROMETER
            else -> return
        }
        val sensor = sensorManager.getDefaultSensor(type)
        if (sensor == null) {
            toast(if (type == Sensor.TYPE_LIGHT) "Ambient light sensor is unavailable on this device." else "Motion sensor is unavailable on this device.")
            return
        }
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun addSectionTitle(parent: LinearLayout, title: String, subtitle: String? = null) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(android.graphics.Color.rgb(22, 65, 91))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(22), dp(2), dp(3))
        })
        if (subtitle != null) parent.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(100, 127, 143))
            setPadding(dp(3), 0, dp(3), dp(8))
        })
    }

    private fun addButton(parent: LinearLayout, label: String, secondary: Boolean = false, action: () -> Unit) {
        val button = com.google.android.material.button.MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            cornerRadius = dp(15)
            insetTop = dp(2)
            insetBottom = dp(2)
            minHeight = dp(50)
            setTextColor(if (secondary) android.graphics.Color.rgb(24, 91, 121) else android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (secondary) android.graphics.Color.rgb(225, 241, 248) else android.graphics.Color.rgb(0, 125, 166)
            )
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(5)
        })
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun addWater(amount: Int) {
        if (amount <= 0) {
            if (amount < 0) undoLastWaterEntry()
            return
        }
        addWaterEntry(amount, System.currentTimeMillis())
    }

    private fun addWaterEntry(amount: Int, timestamp: Long) {
        val entries = loadTodayEntries()
        val entry = JSONObject().put("amount", amount).put("time", timestamp)
        entries.put(entry)
        totalMl += amount
        saveTodayEntries(entries)
        if (getSharedPreferences("water_tracker", MODE_PRIVATE).getBoolean("location_tagging", false) &&
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            runCatching {
                LocationServices.getFusedLocationProviderClient(this).lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            val latest = loadTodayEntries()
                            for (i in 0 until latest.length()) {
                                val saved = latest.optJSONObject(i)
                                if (saved?.optLong("time") == timestamp) {
                                    saved.put("latitude", location.latitude)
                                    saved.put("longitude", location.longitude)
                                    saved.put("location_accuracy_m", location.accuracy.toInt())
                                    saveTodayEntries(latest)
                                    refreshHistoryDisplay()
                                    break
                                }
                            }
                        }
                    }
            }
        }
        saveTodayTotal()
        refreshWaterDisplay()
        refreshHistoryDisplay()
    }

    private fun undoLastWaterEntry() {
        val entries = loadTodayEntries()
        if (entries.length() == 0) { toast("There is no entry to undo."); return }
        val last = entries.optJSONObject(entries.length() - 1)
        totalMl = (totalMl - (last?.optInt("amount", 0) ?: 0)).coerceAtLeast(0)
        val updated = JSONArray()
        for (i in 0 until entries.length() - 1) updated.put(entries.getJSONObject(i))
        saveTodayEntries(updated)
        saveTodayTotal()
        refreshWaterDisplay()
        refreshHistoryDisplay()
        toast("Last water entry removed.")
    }

    private fun refreshWaterDisplay() {
        totalText.text = "$totalMl ml"
        val percent = ((totalMl * 100) / dailyGoalMl).coerceAtMost(100)
        progressText.text = "$percent% of your $dailyGoalMl ml daily goal"
        if (::waterProgress.isInitialized) waterProgress.progress = percent
    }

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun loadTodayTotal() {
        val prefs = getSharedPreferences("water_tracker", Context.MODE_PRIVATE)
        totalMl = if (prefs.getString("date", "") == todayKey()) prefs.getInt("total_ml", 0) else 0
    }

    private fun saveTodayTotal() {
        getSharedPreferences("water_tracker", Context.MODE_PRIVATE).edit()
            .putString("date", todayKey()).putInt("total_ml", totalMl)
            .putInt("total_ml_${todayKey()}", totalMl).apply()
    }

    private fun entriesKey(day: String = todayKey()) = "water_entries_$day"
    private fun loadTodayEntries(): JSONArray {
        val raw = getSharedPreferences("water_tracker", MODE_PRIVATE).getString(entriesKey(), "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }
    private fun saveTodayEntries(entries: JSONArray) {
        getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putString(entriesKey(), entries.toString()).apply()
    }

    private fun refreshHistoryDisplay() {
        if (!::historyText.isInitialized) return
        val entries = loadTodayEntries()
        if (entries.length() == 0) { historyText.text = "Today's entries: none yet."; return }
        val first = maxOf(0, entries.length() - 8)
        val lines = (first until entries.length()).reversed().map { index ->
            val item = entries.optJSONObject(index) ?: JSONObject()
            val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(item.optLong("time")))
            val place = if (item.has("latitude")) " • location saved" else ""
            "• $time — ${item.optInt("amount")} ml$place"
        }
        historyText.text = "TODAY'S ENTRIES\n" + lines.joinToString("\n")
    }

    private fun showManualWaterEntry() {
        val input = android.widget.EditText(this).apply {
            hint = "Amount in ml"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("250"); selectAll()
        }
        manualEntryTime = Calendar.getInstance()
        val timeButton = com.google.android.material.button.MaterialButton(this).apply {
            isAllCaps = false
            text = "Time: " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(manualEntryTime.time)
        }
        timeButton.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                manualEntryTime.set(Calendar.HOUR_OF_DAY, hour)
                manualEntryTime.set(Calendar.MINUTE, minute)
                timeButton.text = "Time: " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(manualEntryTime.time)
            }, manualEntryTime.get(Calendar.HOUR_OF_DAY), manualEntryTime.get(Calendar.MINUTE), false).show()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(input); addView(timeButton)
        }
        AlertDialog.Builder(this).setTitle("Manual water entry")
            .setMessage("Enter the amount you drank and select its time.")
            .setView(content).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val amount = input.text.toString().toIntOrNull()
                if (amount == null || amount !in 1..5000) toast("Enter an amount between 1 and 5000 ml.")
                else { addWaterEntry(amount, manualEntryTime.timeInMillis); toast("$amount ml entry saved.") }
            }.show()
    }

    private fun showDailyHistory() {
        val prefs = getSharedPreferences("water_tracker", MODE_PRIVATE)
        val days = (0..6).map { offset ->
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -offset) }
            val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
            val label = SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(cal.time)
            val total = if (key == todayKey()) totalMl else prefs.getInt("total_ml_$key", 0)
            val entries = runCatching { JSONArray(prefs.getString(entriesKey(key), "[]") ?: "[]") }.getOrElse { JSONArray() }
            val detail = if (entries.length() == 0) "No saved entries" else (0 until entries.length()).joinToString("\n") { i ->
                val item = entries.optJSONObject(i) ?: JSONObject()
                val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(item.optLong("time")))
                val place = if (item.has("latitude")) " (location saved)" else ""
                "$time — ${item.optInt("amount")} ml$place"
            }
            "$label • $total ml\n$detail"
        }
        AlertDialog.Builder(this).setTitle("Daily water history (last 7 days)")
            .setMessage(days.joinToString("\n\n")).setPositiveButton("Close", null).show()
    }

    private fun requestVoice() {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) startVoiceInput()
        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceInput() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            toast("Allow Microphone access in Android Settings first."); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("No speech recognition service is available. Install or enable Google Speech Services."); return
        }
        speechRecognizer?.destroy()
        speechRecognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { toast("Listening… say 250 ml or 500 ml.") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                speechRecognizer?.destroy(); speechRecognizer = null
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error. Check microphone access."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow Microphone access in Android Settings."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service network error. Check Google Speech Services and mobile data."
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I could not recognise that. Please try again."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy. Try again."
                    SpeechRecognizer.ERROR_SERVER -> "Speech service returned a server error. Update or enable Google Speech Services."
                    else -> "Speech recognition failed (error $error)."
                }
                toast(message)
            }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase(Locale.getDefault()).orEmpty()
                speechRecognizer?.destroy(); speechRecognizer = null
                when {
                    spoken.contains("500") -> addWater(500)
                    spoken.contains("250") -> addWater(250)
                    else -> toast("Please say 250 ml or 500 ml. Recognised: $spoken")
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say: add 250 ml or add 500 ml")
        }
        runCatching { speechRecognizer?.startListening(intent) }
            .onFailure { toast("Could not start speech recognition: ${it.localizedMessage}") }
    }

    private fun updateLocationStatus() {
        val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        locationText.text = when {
            fine -> "Location access: allowed (precise permission)"
            coarse -> "Location access: allowed (approximate permission)"
            else -> "Location access: not allowed"
        }
    }

    private fun applyScreenDim(enabled: Boolean) {
        if (::screenDimOverlay.isInitialized) screenDimOverlay.visibility =
            if (enabled) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(cameraPreview.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val scanner = BarcodeScanning.getClient()
            analysis.setAnalyzer(cameraExecutor) { proxy ->
                val mediaImage = proxy.image
                if (mediaImage == null) {
                    proxy.close()
                } else {
                    val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            codes.firstOrNull()?.rawValue?.let { value ->
                                runOnUiThread { toast("Barcode / QR: $value") }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                toast("Scanner ready. Point the camera at a barcode or QR code.")
            } catch (e: Exception) {
                cameraStarted = false
                toast("Unable to start camera: ${e.localizedMessage}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun enableSleepActivityTheme() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        getSharedPreferences("water_tracker", MODE_PRIVATE).edit()
            .putString("theme", "muraqaba")
            .putBoolean("sleep_theme_enabled", true)
            .putInt("sleep_awake_threshold", 20)
            .putInt("sleep_asleep_threshold", 80)
            .apply()
        applyThemePreference()
        SleepThemeManager.subscribe(this)
            .addOnSuccessListener { toast("Muraqaba Theme enabled. Awake ≤20: Light; Asleep ≥80: Dark. Middle values keep the previous theme.") }
            .addOnFailureListener { e -> toast("Could not start Sleep API updates: ${e.localizedMessage ?: "Google Play services unavailable"}") }
        recreate()
    }

    private fun unusedLocationPlaceholder() { updateLocationStatus() }

    private fun changeFontSize(delta: Float) {
        val p = getSharedPreferences("water_tracker", MODE_PRIVATE)
        val next = (p.getFloat("font_scale", 1f) + delta).coerceIn(0.8f, 1.6f)
        p.edit().putFloat("font_scale", next).apply()
        recreate()
    }

    private fun applyDisplayPreferences(root: android.view.View) {
        val p = getSharedPreferences("water_tracker", MODE_PRIVATE)
        val scale = p.getFloat("font_scale", 1f)
        val family = when (p.getString("font_style", "default")) { "serif" -> Typeface.SERIF; "sans" -> Typeface.SANS_SERIF; else -> Typeface.DEFAULT }
        val style = if (p.getBoolean("bold_text", false)) Typeface.BOLD else Typeface.NORMAL
        fun visit(v: android.view.View) {
            if (v is TextView) { v.textSize = v.textSize / resources.displayMetrics.scaledDensity * scale; v.typeface = Typeface.create(family, style) }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
    }

    override fun onStart() {
        super.onStart()
        if (!sleepThemeReceiverRegistered) {
            ContextCompat.registerReceiver(this, sleepThemeChangeReceiver,
                IntentFilter(SleepThemeManager.ACTION_THEME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            sleepThemeReceiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenAwake()
        applyScreenDim(getSharedPreferences("water_tracker", MODE_PRIVATE).getBoolean("screen_dim_enabled", false))
        applyThemePreference()
        registerThemeSensor()
        val prefs = getSharedPreferences("water_tracker", MODE_PRIVATE)
        if (prefs.getBoolean("sleep_theme_enabled", false) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            SleepThemeManager.subscribe(this)
        }
    }
    private fun applyKeepScreenAwake() {
        val enabled = getSharedPreferences("water_tracker", MODE_PRIVATE)
            .getBoolean("keep_screen_awake", true)
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        if (sleepThemeReceiverRegistered) {
            unregisterReceiver(sleepThemeChangeReceiver)
            sleepThemeReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
        cameraExecutor.shutdown()
    }
}
