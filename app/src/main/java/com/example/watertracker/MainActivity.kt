package com.example.watertracker

import android.Manifest
import android.app.Activity
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
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SensorEventListener {
    private var totalMl = 0
    private val dailyGoalMl = 2000
    private lateinit var totalText: TextView
    private lateinit var progressText: TextView
    private lateinit var locationText: TextView
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
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) showApproximateLocation() else locationText.text = "Location access: not allowed"
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 40, 36, 32)
        }

        layout.addView(TextView(this).apply {
            text = "💧 Water Tracker"
            textSize = 28f
            setPadding(0, 0, 0, 12)
        })

        layout.addView(TextView(this).apply {
            text = "Track your daily hydration"
            textSize = 16f
        })


        totalText = TextView(this).apply {
            textSize = 25f
            setPadding(0, 28, 0, 8)
        }
        layout.addView(totalText)

        progressText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 18)
        }
        layout.addView(progressText)
        refreshWaterDisplay()

        addButton(layout, "Add 250 ml") { addWater(250) }
        addButton(layout, "Add 500 ml") { addWater(500) }
        addButton(layout, "Undo last 250 ml") { addWater(-250) }
        addButton(layout, "Reset today's total") {
            totalMl = 0
            saveTodayTotal()
            refreshWaterDisplay()
        }

        addSectionTitle(layout, "Voice logging")
        addButton(layout, "🎙 Speak to log water") { requestVoice() }

        addSectionTitle(layout, "Camera tools")
        addButton(layout, "Scan a bottle barcode / QR code") {
            if (hasPermission(Manifest.permission.CAMERA)) startCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        addButton(layout, "Take a water bottle photo") {
            if (hasPermission(Manifest.permission.CAMERA)) photoPicker.launch(null)
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        cameraPreview = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 420)
        }
        layout.addView(cameraPreview)

        addSectionTitle(layout, "Approximate location")
        locationText = TextView(this).apply {
            text = "Location access has not been requested."
            textSize = 15f
            setPadding(0, 8, 0, 8)
        }
        layout.addView(locationText)
        addButton(layout, "Check approximate location") {
            if (hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) showApproximateLocation()
            else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        addSectionTitle(layout, "Theme Settings")
        addButton(layout, "Muraqaba Theme – Awake: Light; Asleep: Dark") {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                toast("Muraqaba Theme requires Android 10 or later.")
            } else if (hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                enableSleepActivityTheme()
            } else {
                sleepActivityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        layout.addView(TextView(this).apply {
            text = "Muraqaba confidence thresholds: 0–20 = Awake / Light; 80–100 = Asleep / Dark; 21–79 = retain the previous theme. Uses device motion and ambient-light sensors, not the camera. Updates are periodic and may take about 10 minutes to arrive."
            textSize = 13f
            setPadding(0, 4, 0, 12)
        })
        addButton(layout, "Room light – use ambient light sensor") { saveThemeMode("room_light") }
        addButton(layout, "Weather based – choose current weather") { showWeatherThemePicker() }
        addButton(layout, "Time based – configure your theme schedule") { showTimeThemeOptions() }
        addButton(layout, "Day–night based – local day/night cycle") { saveThemeMode("day_night") }
        addButton(layout, "Choice preference – select Light or Dark") { showChoiceThemePicker() }
        addButton(layout, "Motion detection based – movement / stillness") { saveThemeMode("motion") }        val themeMode = getSharedPreferences("water_tracker", MODE_PRIVATE).getString("theme", "system")
        layout.addView(TextView(this).apply {
            text = "Selected theme: ${themeMode?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "System"}. Room-light and motion modes use device sensors. Weather mode asks you to select the current condition to avoid background data use."
            textSize = 13f
            setPadding(0, 8, 0, 12)
        })
        addSectionTitle(layout, "Display & Accessibility")
        addButton(layout, "Color correction / color filters") {
            toast("Enable color correction in Android accessibility settings.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addSectionTitle(layout, "Font size")
        addButton(layout, "Smaller font") { changeFontSize(-0.1f) }
        addButton(layout, "Larger font") { changeFontSize(0.1f) }
        addButton(layout, "Reset font size") { getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putFloat("font_scale", 1f).apply(); recreate() }
        addSectionTitle(layout, "Font style")
        addButton(layout, "Default font") { getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putString("font_style", "default").apply(); recreate() }
        addButton(layout, "Serif font") { getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putString("font_style", "serif").apply(); recreate() }
        addButton(layout, "Sans-serif font") { getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putString("font_style", "sans").apply(); recreate() }
        addButton(layout, "Toggle bold text") { val p = getSharedPreferences("water_tracker", MODE_PRIVATE); p.edit().putBoolean("bold_text", !p.getBoolean("bold_text", false)).apply(); recreate() }
        addSectionTitle(layout, "Screen orientation")
        addButton(layout, "Portrait") { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        addButton(layout, "Landscape") { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        addButton(layout, "Auto (follow device)") { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        addSectionTitle(layout, "Screen awake setting")
        layout.addView(Switch(this).apply {
            text = "Keep screen awake while Water Tracker is visible"
            isChecked = getSharedPreferences("water_tracker", MODE_PRIVATE).getBoolean("keep_screen_awake", true)
            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putBoolean("keep_screen_awake", enabled).apply()
                applyKeepScreenAwake()
                toast(if (enabled) "Screen will stay awake while Water Tracker is visible." else "Normal Android screen timeout will apply.")
            }
        })
        addSectionTitle(layout, "App updates")
        layout.addView(Switch(this).apply { text = "Automatic updates"; isChecked = false; isEnabled = false })
        layout.addView(TextView(this).apply { text = "Automatic updates are disabled. Review changes and permissions consciously, then install updates manually when you choose. This preserves control over app changes and avoids unexpected mobile-data use."; textSize = 13f; setPadding(0, 8, 0, 12) })
        layout.addView(TextView(this).apply { text = "The screen stays awake while Water Tracker is visible. Normal screen timeout resumes when you leave the app."; textSize = 13f })
        applyDisplayPreferences(layout)
        setContentView(ScrollView(this).apply { addView(layout) })
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

    private fun addSectionTitle(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setPadding(0, 28, 0, 8)
        })
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            setOnClickListener { action() }
        })
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun addWater(amount: Int) {
        totalMl = (totalMl + amount).coerceAtLeast(0)
        saveTodayTotal()
        refreshWaterDisplay()
    }

    private fun refreshWaterDisplay() {
        totalText.text = "Today's water: $totalMl ml"
        val percent = ((totalMl * 100) / dailyGoalMl).coerceAtMost(100)
        progressText.text = "Daily goal: $dailyGoalMl ml  •  $percent% completed"
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun loadTodayTotal() {
        val prefs = getSharedPreferences("water_tracker", Context.MODE_PRIVATE)
        totalMl = if (prefs.getString("date", "") == todayKey()) prefs.getInt("total_ml", 0) else 0
    }

    private fun saveTodayTotal() {
        getSharedPreferences("water_tracker", Context.MODE_PRIVATE).edit()
            .putString("date", todayKey())
            .putInt("total_ml", totalMl)
            .apply()
    }

    private fun requestVoice() {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) startVoiceInput()
        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Speech recognition is not available on this device.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say: add 250 ml or add 500 ml")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 501)
    }

    @Deprecated("Legacy activity result used for speech recognizer compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 501 && resultCode == Activity.RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.lowercase(Locale.getDefault()).orEmpty()
            when {
                spoken.contains("500") -> addWater(500)
                spoken.contains("250") -> addWater(250)
                else -> toast("Please say 250 ml or 500 ml.")
            }
        }
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
 {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        getSharedPreferences("water_tracker", MODE_PRIVATE).edit()
            .putString("theme", "muraqaba")
            .putBoolean("sleep_theme_enabled", true)
            .putInt("sleep_awake_threshold", 20)
            .putInt("sleep_asleep_threshold", 80)
            .apply()
        applyThemePreference()
        SleepThemeManager.subscribe(this)
            .addOnSuccessListener { toast("Sleep Activity Theme enabled. Awake ≤20: Light; Asleep ≥80: Dark. Middle values keep the previous theme.") }
            .addOnFailureListener { e -> toast("Could not start Sleep API updates: ${e.localizedMessage ?: "Google Play services unavailable"}") }
        recreate()
    }

    @Suppress("MissingPermission")
    private fun showApproximateLocation() {
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        val location: Location? = providers.asSequence()
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .minByOrNull { it.accuracy }

        locationText.text = if (location == null) {
            "Approximate location: unavailable. Turn on Location and try again."
        } else {
            "Approximate location available (accuracy about ${location.accuracy.toInt()} metres)."
        }
    }

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
        cameraExecutor.shutdown()
    }
}
