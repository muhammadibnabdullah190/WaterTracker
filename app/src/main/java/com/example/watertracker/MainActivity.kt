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
    private lateinit var cameraPreviewCard: com.google.android.material.card.MaterialCardView
    private var scanningExternalCamera = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraStarted = false
    private lateinit var sensorManager: SensorManager
    private var lastMotionThemeUpdate = 0L

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) openCameraToolsSession() else toast("Camera permission was not granted.")
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
    private val voiceRecognitionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.lowercase(Locale.getDefault()).orEmpty()
            when {
                spoken.contains("500") -> { addWater(500); toast("500 ml added.") }
                spoken.contains("250") -> { addWater(250); toast("250 ml added.") }
                else -> toast("Please say 250 ml or 500 ml. Recognised: $spoken")
            }
        }
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val granted = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        getSharedPreferences("water_tracker", MODE_PRIVATE).edit()
            .putBoolean("location_tagging", granted).apply()
        locationTaggingEnabled = granted
        updateLocationStatus()
        if (!granted) toast("Location access was not granted. Location tagging remains off.")
        recreate()
    }
    private val backupFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeBackup(uri)
    }
    private val restoreFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore(uri)
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
        val transparentTheme = getSharedPreferences("water_tracker", MODE_PRIVATE).getString("theme", "system") == "transparent"
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(18))
            setBackgroundResource(R.drawable.bg_water_background)
        }

        val hero = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(26).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(-1, dp(112)).apply { bottomMargin = dp(10) }
        }
        val heroContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(18), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                if (transparentTheme) intArrayOf(android.graphics.Color.rgb(5, 105, 175), android.graphics.Color.rgb(0, 150, 160))
                else intArrayOf(android.graphics.Color.rgb(5, 105, 175), android.graphics.Color.rgb(0, 150, 160))
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
            cardElevation = 0f
            strokeWidth = 0
            setStrokeColor(android.graphics.Color.TRANSPARENT)
            setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
        }
        val summaryContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        val hydrationHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        hydrationHeader.addView(createSectionGraphic("💧", 38))
        hydrationHeader.addView(TextView(this).apply {
            text = "TODAY'S HYDRATION"
            textSize = 13f
            letterSpacing = 0.12f
            setTextColor(android.graphics.Color.rgb(65, 119, 150))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        })
        summaryContent.addView(hydrationHeader)
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
        waterProgress = CircularHydrationProgressView(this).apply {
            max = 100
            progress = 0
        }
        summaryContent.addView(waterProgress, LinearLayout.LayoutParams(dp(112), dp(112)).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        summary.addView(summaryContent)
        page.addView(summary)
        refreshWaterDisplay()

        // Two equal-width buttons per row. Keep every dashboard action in this grid.
        val dashboardGrid = android.widget.GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = false
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(6)
            }
        }
        addDashboardButton(dashboardGrid, "Theme", ContextCompat.getDrawable(this, R.drawable.ic_display)!!) { showThemeOptions() }
        addDashboardButton(dashboardGrid, "Water Entries", ContextCompat.getDrawable(this, R.drawable.ic_quick_add)!!) { showQuickAddOptions() }
        addDashboardButton(dashboardGrid, "Voice logging", ContextCompat.getDrawable(this, R.drawable.ic_voice)!!) { requestVoice() }
        addDashboardButton(
            dashboardGrid,
            if (getSharedPreferences("water_tracker", MODE_PRIVATE).getBoolean("location_tagging", false)) "Location tagging ON" else "Location tagging",
            ContextCompat.getDrawable(this, R.drawable.ic_location)!!
        ) { toggleLocationTagging() }
        addDashboardButton(dashboardGrid, "Camera Tools", ContextCompat.getDrawable(this, R.drawable.ic_camera_tools)!!) { showCameraToolsDialog() }
        addDashboardButton(dashboardGrid, "Display", ContextCompat.getDrawable(this, R.drawable.ic_display)!!) { showDisplayDialog() }
        addDashboardButton(dashboardGrid, "Monthly Records", ContextCompat.getDrawable(this, R.drawable.ic_entries)!!) { showDailyHistory() }
        addDashboardButton(dashboardGrid, "Check for Update", ContextCompat.getDrawable(this, R.drawable.ic_update)!!) { checkForAppUpdate() }
        addDashboardButton(dashboardGrid, "Backup & Restore", ContextCompat.getDrawable(this, R.drawable.ic_backup_restore)!!) { showBackupRestoreDialog() }
        addDashboardButton(dashboardGrid, "Accessibility Settings", ContextCompat.getDrawable(this, R.drawable.ic_display)!!) { showAccessibilityDialog() }
        page.addView(dashboardGrid)

        historyText = TextView(this).apply {
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(76, 103, 120))
            setPadding(dp(6), dp(6), dp(6), dp(8))
        }
        locationText = TextView(this).apply {
            text = ""
            visibility = android.view.View.GONE
            textSize = 12f
            setTextColor(android.graphics.Color.rgb(76, 103, 120))
            setPadding(dp(2), dp(1), dp(2), dp(2))
        }
        refreshHistoryDisplay()
        updateLocationStatus()

        applyDisplayPreferences(page)
        applyViewOpacity(page, getSharedPreferences("water_tracker", MODE_PRIVATE).getInt("transparency_level", 75).coerceIn(0, 100))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, 0)
            addView(page)
        }
        val root = FrameLayout(this)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(WaterDropletsView(this), FrameLayout.LayoutParams(-1, -1))
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


    private lateinit var waterProgress: CircularHydrationProgressView

    private inner class WaterDropletsView(context: Context) : android.view.View(context) {
        private val outline = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = android.graphics.Color.argb(58, 0, 145, 190)
        }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.argb(13, 0, 160, 205)
        }
        private val shine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.argb(48, 255, 255, 255)
        }
        private val positions = listOf(
            0.08f to 0.18f, 0.91f to 0.25f, 0.16f to 0.48f,
            0.84f to 0.60f, 0.07f to 0.79f, 0.94f to 0.88f,
            0.48f to 0.34f, 0.68f to 0.76f
        )
        private var animationProgress = 0f
        private val floatAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 10000L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                animationProgress = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!floatAnimator.isStarted) floatAnimator.start()
        }

        override fun onDetachedFromWindow() {
            floatAnimator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            positions.forEachIndexed { index, point ->
                val size = dp(if (index % 3 == 0) 23 else if (index % 2 == 0) 18 else 14).toFloat()
                val phase = (animationProgress + index * 0.137f) % 1f
                val drift = kotlin.math.sin(phase * Math.PI * 2.0).toFloat()
                val x = width * point.first + drift * dp(5)
                val y = height * point.second + drift * dp(8)
                val alpha = (0.65f + 0.35f * kotlin.math.sin(phase * Math.PI * 2.0).toFloat()).coerceIn(0.25f, 1f)
                outline.alpha = (58 * alpha).toInt()
                fill.alpha = (13 * alpha).toInt()
                shine.alpha = (48 * alpha).toInt()
                val path = android.graphics.Path().apply {
                    moveTo(x, y - size)
                    cubicTo(x - size * 0.12f, y - size * 0.48f, x - size * 0.72f, y + size * 0.18f, x, y + size * 0.55f)
                    cubicTo(x + size * 0.72f, y + size * 0.18f, x + size * 0.12f, y - size * 0.48f, x, y - size)
                    close()
                }
                canvas.drawPath(path, fill)
                canvas.drawPath(path, outline)
                canvas.drawCircle(x - size * 0.17f, y - size * 0.05f, size * 0.12f, shine)
            }
        }
    }

    private inner class CircularHydrationProgressView(context: Context) : android.view.View(context) {
        var max: Int = 100
        var progress: Int = 0
            set(value) { field = value.coerceIn(0, max); invalidate() }
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(10).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = android.graphics.Color.rgb(218, 239, 247)
        }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(10).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = android.graphics.Color.rgb(0, 145, 190)
        }
        private val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(20, 75, 102)
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = dp(23).toFloat()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val subLabel = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(83, 112, 130)
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = dp(11).toFloat()
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val side = minOf(width, height).toFloat()
            val inset = track.strokeWidth / 2f + dp(3)
            val rect = android.graphics.RectF(
                (width - side) / 2f + inset, (height - side) / 2f + inset,
                (width + side) / 2f - inset, (height + side) / 2f - inset
            )
            canvas.drawArc(rect, -90f, 360f, false, track)
            canvas.drawArc(rect, -90f, 360f * progress / max.coerceAtLeast(1), false, fill)
            val centerX = width / 2f
            val centerY = height / 2f
            canvas.drawText("$progress%", centerX, centerY + dp(1).toFloat(), label)
            canvas.drawText("of daily goal", centerX, centerY + dp(19).toFloat(), subLabel)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createActionButton(label: String, action: () -> Unit): com.google.android.material.button.MaterialButton =
        com.google.android.material.button.MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 11.5f
            maxLines = 1
            gravity = android.view.Gravity.CENTER
            ellipsize = android.text.TextUtils.TruncateAt.END
            cornerRadius = dp(20)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(34)
            maxWidth = dp(168)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(0, 125, 166))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    android.graphics.Color.argb(205, 125, 225, 245),
                    android.graphics.Color.argb(150, 0, 125, 166)
                )
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), android.graphics.Color.argb(220, 255, 255, 255))
            }
            elevation = dp(5).toFloat()
            stateListAnimator = android.animation.StateListAnimator()
            setOnClickListener { action() }
        }

    private fun addDashboardButton(
        grid: android.widget.GridLayout,
        label: String,
        graphic: android.graphics.drawable.Drawable,
        action: () -> Unit
    ) {
        val button = createActionButton(label, action).apply {
            layoutParams = android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = dp(58)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            icon = graphic
            iconSize = dp(23)
            graphic.setBounds(0, 0, dp(23), dp(23))
            iconPadding = dp(5)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            textSize = 12f
            maxWidth = Int.MAX_VALUE
            elevation = dp(7).toFloat()
            stateListAnimator = android.animation.StateListAnimator()
        }
        grid.addView(button)
    }

    private fun createDashboardGlyph(kind: String): android.graphics.drawable.Drawable {
        val background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(android.graphics.Color.rgb(245, 255, 255), android.graphics.Color.rgb(80, 190, 224))
        ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(1), android.graphics.Color.WHITE) }
        val ink = android.graphics.Color.rgb(0, 105, 151)
        val parts = mutableListOf<android.graphics.drawable.Drawable>(background)
        if (kind == "entries") {
            for (i in 0..2) {
                parts.add(android.graphics.drawable.GradientDrawable().apply {
                    setColor(ink); cornerRadius = dp(2).toFloat()
                })
            }
        } else {
            parts.add(android.graphics.drawable.GradientDrawable().apply {
                setColor(ink); cornerRadius = dp(4).toFloat()
            })
            parts.add(android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE); cornerRadius = dp(2).toFloat()
            })
        }
        return android.graphics.drawable.LayerDrawable(parts.toTypedArray()).apply {
            if (kind == "entries") {
                setLayerInset(1, dp(8), dp(8), dp(8), dp(17))
                setLayerInset(2, dp(8), dp(14), dp(8), dp(11))
                setLayerInset(3, dp(8), dp(20), dp(8), dp(5))
            } else {
                setLayerInset(1, dp(5), dp(9), dp(5), dp(8))
                setLayerInset(2, dp(11), dp(13), dp(11), dp(12))
            }
        }
    }

    private fun createLocationGraphic(): android.graphics.drawable.Drawable {
        val outer = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(android.graphics.Color.rgb(238, 253, 255), android.graphics.Color.rgb(82, 192, 224))
        ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(1), android.graphics.Color.WHITE) }
        val pin = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.rgb(0, 112, 157))
            shape = android.graphics.drawable.GradientDrawable.OVAL
        }
        val dot = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.WHITE)
            shape = android.graphics.drawable.GradientDrawable.OVAL
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(outer, pin, dot)).apply {
            setLayerInset(1, dp(8), dp(6), dp(8), dp(9))
            setLayerInset(2, dp(13), dp(11), dp(13), dp(14))
        }
    }

    private fun createQuickAddGraphic(): android.graphics.drawable.Drawable {
        val outer = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(android.graphics.Color.rgb(245, 255, 255), android.graphics.Color.rgb(86, 197, 226))
        ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(1), android.graphics.Color.WHITE) }
        val vertical = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.rgb(0, 105, 151)); cornerRadius = dp(2).toFloat()
        }
        val horizontal = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.rgb(0, 105, 151)); cornerRadius = dp(2).toFloat()
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(outer, vertical, horizontal)).apply {
            setLayerInset(1, dp(11), dp(5), dp(11), dp(19))
            setLayerInset(2, dp(5), dp(11), dp(19), dp(11))
        }
    }

    private fun createVoiceGraphic(): android.graphics.drawable.Drawable {
        val circle = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(android.graphics.Color.rgb(235, 252, 255), android.graphics.Color.rgb(89, 194, 222))
        ).apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setStroke(dp(1), android.graphics.Color.WHITE)
        }
        val microphone = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(android.graphics.Color.rgb(0, 104, 158), android.graphics.Color.rgb(0, 190, 190))
        ).apply { cornerRadius = dp(7).toFloat() }
        val stem = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.rgb(0, 104, 158))
            cornerRadius = dp(2).toFloat()
        }
        val base = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.rgb(0, 104, 158))
            cornerRadius = dp(3).toFloat()
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(circle, microphone, stem, base)).apply {
            setLayerInset(1, dp(14), dp(7), dp(14), dp(14))
            setLayerInset(2, dp(19), dp(23), dp(19), dp(9))
            setLayerInset(3, dp(13), dp(29), dp(13), dp(7))
        }
    }

    private fun applyViewOpacity(root: android.view.View, opacity: Int) {
        val alpha = opacity.coerceIn(0, 100) * 255 / 100
        fun visit(view: android.view.View) {
            view.background?.mutate()?.alpha = alpha
            if (view is Switch) {
                val thumbAlpha = alpha.coerceIn(0, 255)
                val trackAlpha = (thumbAlpha * 0.55f).toInt()
                view.thumbTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.argb(thumbAlpha, 0, 125, 166)
                )
                view.trackTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.argb(trackAlpha, 90, 160, 185)
                )
            }
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        visit(root)
    }

    private fun applyThemePreference() {
        val prefs = getSharedPreferences("water_tracker", MODE_PRIVATE)
        val stored = prefs.getString("theme", "system") ?: "system"
        val mode = when (stored) {
            "light", "choice_light" -> "light"
            "dark", "choice_dark" -> "dark"
            "system" -> "system"
            else -> "system"
        }
        if (stored != mode) prefs.edit().putString("theme", mode).apply()
        val nightMode = when (mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun saveThemeMode(mode: String) {
        getSharedPreferences("water_tracker", MODE_PRIVATE).edit()
            .putString("theme", mode)
            .apply()
        applyThemePreference()
        recreate()
    }

    private fun showThemeOptions() {
        val options = arrayOf("Light", "Dark", "System Theme")
        val current = getSharedPreferences("water_tracker", MODE_PRIVATE)
            .getString("theme", "system") ?: "system"
        val checked = when (current) { "light" -> 0; "dark" -> 1; else -> 2 }
        AlertDialog.Builder(this)
            .setTitle("Choose Theme")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val mode = when (which) { 0 -> "light"; 1 -> "dark"; else -> "system" }
                dialog.dismiss()
                saveThemeMode(mode)
            }
            .setNegativeButton("Close", null)
            .show()
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
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
            val movementDetected = kotlin.math.abs(magnitude - SensorManager.GRAVITY_EARTH) > 0.8f
            val prefs = getSharedPreferences("water_tracker", MODE_PRIVATE)
            if (movementDetected) {
                lastMotionThemeUpdate = now
                if (prefs.getBoolean("motion_theme_dark", true)) {
                    prefs.edit().putBoolean("motion_theme_dark", false).apply()
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
            } else if (lastMotionThemeUpdate == 0L) {
                lastMotionThemeUpdate = now
            } else if (now - lastMotionThemeUpdate >= 10_000L && !prefs.getBoolean("motion_theme_dark", true)) {
                prefs.edit().putBoolean("motion_theme_dark", true).apply()
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
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

    private fun addThemeTile(parent: LinearLayout, graphic: String, label: String, action: () -> Unit) {
        val tile = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(4).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(android.graphics.Color.rgb(211, 231, 241))
            setCardBackgroundColor(android.graphics.Color.rgb(239, 248, 252))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, dp(88), 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(7))
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(2), dp(5), dp(2), dp(5))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(android.graphics.Color.rgb(255, 255, 255), android.graphics.Color.rgb(220, 240, 249))
            ).apply { cornerRadius = dp(16).toFloat() }
        }
        content.addView(TextView(this).apply {
            text = graphic
            textSize = 27f
            gravity = android.view.Gravity.CENTER
            setShadowLayer(dp(3).toFloat(), dp(1).toFloat(), dp(2).toFloat(), android.graphics.Color.argb(90, 35, 89, 120))
            contentDescription = "$label theme graphic"
        })
        content.addView(TextView(this).apply {
            text = label
            textSize = 10.5f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.rgb(31, 76, 101))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        })
        tile.addView(content)
        parent.addView(tile)
    }

    private fun createSectionGraphic(emoji: String, size: Int): android.view.View {
        val graphic = TextView(this).apply {
            text = emoji
            textSize = if (size >= 44) 25f else 23f
            gravity = android.view.Gravity.CENTER
            setShadowLayer(dp(3).toFloat(), dp(1).toFloat(), dp(2).toFloat(), android.graphics.Color.argb(110, 25, 86, 120))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(android.graphics.Color.rgb(255, 255, 255), android.graphics.Color.rgb(202, 234, 246))
            ).apply {
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), android.graphics.Color.rgb(213, 235, 244))
            }
            contentDescription = "Decorative $emoji graphic"
        }
        graphic.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        graphic.elevation = dp(3).toFloat()
        return graphic
    }

    private fun addSectionTitle(parent: LinearLayout, title: String, subtitle: String? = null) {
        val graphic = when (title.lowercase(Locale.ROOT)) {
            "quick add" -> "🥤"
            "voice logging" -> "🎙️"
            "camera tools" -> "📸"
            "location tagging" -> "📍"
            "themes" -> "🌈"
            "display & accessibility" -> "✨"
            "screen dim" -> "🌘"
            "blue light filter" -> "🌙"
            "font size" -> "🔠"
            "font style" -> "✍️"
            "screen orientation" -> "📱"
            "screen awake setting" -> "☀️"
            "app updates" -> "🔄"
            else -> "💠"
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, dp(3))
        }
        header.addView(createSectionGraphic(graphic, 38))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), 0, 0, 0)
        }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(android.graphics.Color.rgb(22, 65, 91))
            setTypeface(typeface, Typeface.BOLD)
        })
        if (subtitle != null) labels.addView(TextView(this).apply {
            text = subtitle
            textSize = 11.5f
            setTextColor(android.graphics.Color.rgb(100, 127, 143))
            setPadding(0, dp(1), 0, 0)
        })
        header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(header)
    }

    private fun addButton(parent: LinearLayout, label: String, secondary: Boolean = false, action: () -> Unit) {
        val grid = (parent.getChildAt(parent.childCount - 1) as? android.widget.GridLayout)
            ?: android.widget.GridLayout(this).apply {
                columnCount = 2
                useDefaultMargins = false
                alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
                rowCount = android.widget.GridLayout.UNDEFINED
                parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(3)
                })
            }
        val transparent = getSharedPreferences("water_tracker", MODE_PRIVATE).getString("theme", "transparent") == "transparent"
        val top = if (secondary) android.graphics.Color.rgb(248, 253, 255) else android.graphics.Color.rgb(0, 151, 190)
        val bottom = if (secondary) android.graphics.Color.rgb(205, 231, 243) else android.graphics.Color.rgb(0, 91, 139)
        val button = com.google.android.material.button.MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 11.5f
            maxLines = 1
            gravity = android.view.Gravity.CENTER
            ellipsize = android.text.TextUtils.TruncateAt.END
            cornerRadius = dp(20)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(34)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            maxWidth = dp(168)
            setTextColor(if (secondary) android.graphics.Color.rgb(24, 91, 121) else android.graphics.Color.WHITE)
            backgroundTintList = null
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                if (secondary) intArrayOf(
                    android.graphics.Color.argb(205, 255, 255, 255),
                    android.graphics.Color.argb(145, 205, 238, 249)
                ) else intArrayOf(
                    android.graphics.Color.argb(205, 125, 225, 245),
                    android.graphics.Color.argb(150, 0, 125, 166)
                )
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), android.graphics.Color.argb(220, 255, 255, 255))
            }
            elevation = dp(5).toFloat()
            stateListAnimator = android.animation.StateListAnimator()
            setOnClickListener { action() }
        }
        val column = grid.childCount % 2
        val params = android.widget.GridLayout.LayoutParams(
            android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
            android.widget.GridLayout.spec(column)
        ).apply {
            width = -2
            height = -2
            setMargins(dp(3), dp(3), dp(3), dp(3))
        }
        grid.addView(button, params)
    }

    private var cameraImageCapture: androidx.camera.core.ImageCapture? = null
    private var cameraAnalysis: ImageAnalysis? = null
    private var cameraDialog: AlertDialog? = null
    private var cameraScanner: com.google.mlkit.vision.barcode.BarcodeScanner? = null
    private var lastScannedValue: String? = null

    private fun showCameraToolsDialog() {
        if (hasPermission(Manifest.permission.CAMERA)) {
            openCameraToolsSession()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCameraToolsSession() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        val preview = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(330))
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        cameraPreview = preview
        content.addView(preview)
        val status = TextView(this).apply {
            text = "Starting camera…"
            textSize = 14f
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        content.addView(status)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scanButton = com.google.android.material.button.MaterialButton(this).apply {
            text = "Scan barcode / QR code"
            isAllCaps = false
            setOnClickListener {
                lastScannedValue = null
                status.text = "Scanning… Point the camera at a barcode or QR code."
                cameraAnalysis?.clearAnalyzer()
                cameraAnalysis?.setAnalyzer(cameraExecutor) { proxy ->
                    val media = proxy.image
                    if (media == null) { proxy.close(); return@setAnalyzer }
                    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    (cameraScanner ?: BarcodeScanning.getClient().also { cameraScanner = it })
                        .process(input)
                        .addOnSuccessListener { codes ->
                            val value = codes.firstOrNull()?.rawValue
                            if (!value.isNullOrBlank() && value != lastScannedValue) {
                                lastScannedValue = value
                                runOnUiThread { status.text = "Barcode / QR result: $value" }
                            }
                        }
                        .addOnFailureListener { error ->
                            runOnUiThread { status.text = "Scan failed: ${error.localizedMessage ?: "Please try again."}" }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
            }
        }
        val photoButton = com.google.android.material.button.MaterialButton(this).apply {
            text = "Take a water bottle photo"
            isAllCaps = false
            setOnClickListener {
                val capture = cameraImageCapture
                if (capture == null) {
                    status.text = "Camera is not ready yet."
                } else {
                    status.text = "Capturing photo…"
                    capture.takePicture(cameraExecutor,
                        object : androidx.camera.core.ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                                val bitmap = image.toBitmap()
                                image.close()
                                runOnUiThread {
                                    val imageView = android.widget.ImageView(this@MainActivity).apply {
                                        setImageBitmap(bitmap)
                                        adjustViewBounds = true
                                        maxHeight = dp(420)
                                    }
                                    AlertDialog.Builder(this@MainActivity)
                                        .setTitle("Captured water bottle photo")
                                        .setView(ScrollView(this@MainActivity).apply { addView(imageView) })
                                        .setPositiveButton("Close", null)
                                        .show()
                                    status.text = "Photo captured successfully."
                                }
                            }
                            override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                                runOnUiThread { status.text = "Photo capture failed: ${exception.localizedMessage ?: "Please try again."}" }
                            }
                        })
                }
            }
        }
        actions.addView(scanButton, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        actions.addView(photoButton, LinearLayout.LayoutParams(-1, -2))
        content.addView(actions)
        cameraDialog = AlertDialog.Builder(this).setTitle("Camera Tools").setView(content)
            .setNegativeButton("Close", null).create()
        cameraDialog?.setOnDismissListener {
            try {
                ProcessCameraProvider.getInstance(this).get().unbindAll()
            } catch (_: Exception) { }
            cameraStarted = false
            cameraImageCapture = null
            cameraAnalysis = null
            cameraScanner?.close()
            cameraScanner = null
        }
        cameraDialog?.show()
        startCameraToolsPreview(preview, status)
    }

    private fun startCameraToolsPreview(previewView: PreviewView, status: TextView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = androidx.camera.core.ImageCapture.Builder()
                    .setCaptureMode(androidx.camera.core.ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                cameraImageCapture = capture
                cameraAnalysis = analysis
                cameraStarted = true
                status.text = "Camera ready. Choose an action below."
            } catch (error: Exception) {
                cameraStarted = false
                status.text = "Unable to start camera: ${error.localizedMessage ?: "Camera unavailable."}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showWaterToolsDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }
        addSectionTitle(content, "Quick entries")
        addButton(content, "＋  Manual water entry", secondary = true) { showManualWaterEntry() }
        addButton(content, "↶  Undo last entry", secondary = true) { undoLastWaterEntry() }
        addButton(content, "Reset today's total", secondary = true) {
            totalMl = 0
            saveTodayEntries(JSONArray())
            saveTodayTotal()
            refreshWaterDisplay()
            refreshHistoryDisplay()
        }
        addButton(content, "View daily history", secondary = true) { showDailyHistory() }
        content.addView(historyText)
        addSectionTitle(content, "Camera tools")
        addButton(content, "▦  Scan barcode / QR code") {
            scanningExternalCamera = true
            if (hasPermission(Manifest.permission.CAMERA)) photoPicker.launch(null)
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        addButton(content, "📷  Take a water bottle photo", secondary = true) {
            scanningExternalCamera = false
            if (hasPermission(Manifest.permission.CAMERA)) photoPicker.launch(null)
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        updateLocationStatus()
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle("Water tools")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
        applyViewOpacity(content, getSharedPreferences("water_tracker", MODE_PRIVATE).getInt("transparency_level", 75).coerceIn(0, 100))
    }

    private fun createDisplayToggle(
        label: String,
        preferenceKey: String,
        defaultValue: Boolean,
        onToggle: (Boolean) -> Unit
    ): Switch {
        val checked = intArrayOf(android.R.attr.state_checked)
        val unchecked = intArrayOf()
        return Switch(this).apply {
            text = label
            isChecked = getSharedPreferences("water_tracker", MODE_PRIVATE)
                .getBoolean(preferenceKey, defaultValue)
            setTextColor(android.graphics.Color.rgb(35, 75, 99))
            switchMinWidth = dp(58)
            minHeight = dp(52)
            setPadding(dp(4), dp(8), dp(8), dp(8))
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(checked, unchecked),
                intArrayOf(
                    android.graphics.Color.rgb(0, 132, 190),
                    android.graphics.Color.rgb(235, 243, 247)
                )
            )
            trackTintList = android.content.res.ColorStateList(
                arrayOf(checked, unchecked),
                intArrayOf(
                    android.graphics.Color.rgb(105, 190, 220),
                    android.graphics.Color.rgb(135, 155, 168)
                )
            )
            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences("water_tracker", MODE_PRIVATE).edit()
                    .putBoolean(preferenceKey, enabled).apply()
                onToggle(enabled)
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(56))
        }
    }

    private fun showDisplayDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }
        addSectionTitle(content, "Screen dim", "Turn app screen dimming on or off.")
        content.addView(createDisplayToggle("Dim screen", "screen_dim_enabled", false) { enabled ->
            applyScreenDim(enabled)
        })
        addSectionTitle(content, "Blue light filter", "Apply a gentle warm tint while using Water Tracker.")
        content.addView(createDisplayToggle("Blue light filter", "blue_filter_enabled", false) { enabled ->
            blueFilterOverlay.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
        })
        addSectionTitle(content, "Screen orientation")
        addButton(content, "Portrait", secondary = true) { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        addButton(content, "Landscape", secondary = true) { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        addButton(content, "Auto (follow device)", secondary = true) { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        addSectionTitle(content, "Screen awake setting")
        content.addView(createDisplayToggle(
            "Keep screen awake while Water Tracker is visible",
            "keep_screen_awake",
            true
        ) { enabled ->
            applyKeepScreenAwake()
            toast(if (enabled) "Screen will stay awake while Water Tracker is visible." else "Normal Android screen timeout will apply.")
        })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        AlertDialog.Builder(this).setTitle("Display settings").setView(scroll)
            .setPositiveButton("Close", null).show()
        applyViewOpacity(content, getSharedPreferences("water_tracker", MODE_PRIVATE)
            .getInt("transparency_level", 75).coerceIn(0, 100))
    }

    private fun checkForAppUpdate() {
        toast("Checking GitHub for the latest Water Tracker update…")
        Thread {
            try {
                val connection = java.net.URL("https://api.github.com/repos/muhammadibnabdullah190/WaterTracker/releases/latest")
                    .openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "WaterTracker-Android")
                val code = connection.responseCode
                if (code != 200) throw IllegalStateException(if (code == 404) "No published release is available yet." else "GitHub returned HTTP $code.")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val release = JSONObject(body)
                val assets = release.optJSONArray("assets") ?: JSONArray()
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name") == "app-debug.apk") {
                        apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                        break
                    }
                }
                val downloadUrl = apkUrl ?: throw IllegalStateException("The latest GitHub release does not contain app-debug.apk.")
                val releaseName = release.optString("name").takeIf { it.isNotBlank() } ?: release.optString("tag_name")
                val notes = release.optString("body").trim()
                runOnUiThread { showUpdateConsentDialog(releaseName, notes, downloadUrl) }
            } catch (e: Exception) {
                runOnUiThread { toast("Update check failed: ${e.localizedMessage ?: "Please try again later."}") }
            }
        }.start()
    }

    private fun showUpdateConsentDialog(releaseName: String, notes: String, downloadUrl: String) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        content.addView(TextView(this).apply {
            text = "An update is available: $releaseName"
            textSize = 17f
            setTextColor(android.graphics.Color.rgb(25, 75, 100))
            setTypeface(typeface, Typeface.BOLD)
        })
        val updateItems = if (notes.isNotBlank()) {
            notes.lines().map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("<!--") }
                .map { it.removePrefix("- ").removePrefix("* ").removePrefix("• ").trim() }
                .filter { it.isNotBlank() }
        } else {
            listOf(
                "Improved individual water-entry records and location tagging.",
                "Monthly records now show each entry separately with its own time and location.",
                "Daily goal completion is clearly marked.",
                "General app stability and usability improvements."
            )
        }
        content.addView(TextView(this).apply {
            text = "Updates included:\n" + updateItems.joinToString("\n") { "• $it" }
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(55, 83, 101))
            setPadding(0, dp(12), 0, dp(14))
        })
        val consent = android.widget.CheckBox(this).apply {
            text = "I manually and consciously allow app updates."
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(35, 75, 99))
            isChecked = false
        }
        content.addView(consent)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Water Tracker update")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Done", null)
            .create()
        dialog.setOnShowListener {
            val done = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            done.isEnabled = false
            consent.setOnCheckedChangeListener { _, checked -> done.isEnabled = checked }
            done.setOnClickListener {
                dialog.dismiss()
                downloadAndInstallUpdate(downloadUrl)
            }
        }
        dialog.show()
    }

    private fun downloadAndInstallUpdate(downloadUrl: String) {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("Downloading update")
            setMessage("Preparing download…")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = true
            max = 100
            setProgress(0)
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val apkConnection = java.net.URL(downloadUrl).openConnection() as java.net.HttpURLConnection
                apkConnection.connectTimeout = 20000
                apkConnection.readTimeout = 30000
                apkConnection.instanceFollowRedirects = true
                apkConnection.setRequestProperty("User-Agent", "WaterTracker-Android")
                if (apkConnection.responseCode != 200) throw IllegalStateException("APK download failed (HTTP ${apkConnection.responseCode}).")
                val totalBytes = apkConnection.contentLengthLong
                val updateDir = java.io.File(cacheDir, "updates").apply { mkdirs() }
                val apkFile = java.io.File(updateDir, "watertracker-update.apk")
                apkConnection.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (totalBytes > 0L) {
                                val percent = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                runOnUiThread {
                                    progressDialog.isIndeterminate = false
                                    progressDialog.progress = percent
                                    progressDialog.setMessage("Downloaded ${downloaded / 1024} KB of ${totalBytes / 1024} KB")
                                }
                            } else {
                                runOnUiThread { progressDialog.setMessage("Downloaded ${downloaded / 1024} KB…") }
                            }
                        }
                    }
                }
                apkConnection.disconnect()
                if (apkFile.length() < 1024L * 1024L) {
                    apkFile.delete()
                    throw IllegalStateException("Downloaded APK is incomplete. Please try again.")
                }
                runOnUiThread {
                    progressDialog.isIndeterminate = true
                    progressDialog.setMessage("Download complete. Verifying update…")
                }
                java.util.zip.ZipFile(apkFile).use { zip ->
                    if (zip.getEntry("AndroidManifest.xml") == null) throw IllegalStateException("Downloaded file is not a valid Android APK.")
                }
                getSharedPreferences("water_tracker", MODE_PRIVATE).edit()
                    .putString("pending_update_apk", apkFile.absolutePath).apply()
                runOnUiThread {
                    progressDialog.dismiss()
                    val installProgress = android.app.ProgressDialog(this).apply {
                        setTitle("Update ready")
                        setMessage("Opening Android installer. Android will display the installation progress and ask you to confirm installation.")
                        setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER)
                        setCancelable(false)
                        show()
                    }
                    window.decorView.postDelayed({ installProgress.dismiss(); installDownloadedUpdate(apkFile) }, 1200)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    toast("Update failed: ${e.localizedMessage ?: "Please try again later."}")
                }
            }
        }.start()
    }

    private fun installDownloadedUpdate(apkFile: java.io.File) {
        if (!apkFile.exists()) {
            getSharedPreferences("water_tracker", MODE_PRIVATE).edit().remove("pending_update_apk").apply()
            toast("Downloaded update is no longer available. Please check again.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            startActivity(settingsIntent)
            toast("Allow Water Tracker to install this update, then return to the app.")
            return
        }
        try {
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
            getSharedPreferences("water_tracker", MODE_PRIVATE).edit().remove("pending_update_apk").apply()
        } catch (e: Exception) {
            toast("Could not open Android installer: ${e.localizedMessage ?: "Please try again."}")
        }
    }

    private fun showBackupRestoreDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        content.addView(TextView(this).apply {
            text = "Create a backup file containing your water entries and app settings, or restore them later. Keep the file somewhere safe. It may contain saved location details."
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(65, 85, 100))
            setPadding(0, 0, 0, dp(12))
        })
        val createButton = Button(this).apply {
            text = "Create backup"
            isAllCaps = false
        }
        val restoreButton = Button(this).apply {
            text = "Restore from backup"
            isAllCaps = false
        }
        content.addView(createButton, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
        content.addView(restoreButton, LinearLayout.LayoutParams(-1, -2))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Backup and restore")
            .setView(content)
            .setNegativeButton("Close", null)
            .create()
        createButton.setOnClickListener {
            dialog.dismiss()
            val fileName = "WaterTracker-Backup-" +
                SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
            try {
                backupFileLauncher.launch(fileName)
            } catch (e: Exception) {
                toast("Could not open the backup save screen: ${e.localizedMessage ?: "Please try again."}")
            }
        }
        restoreButton.setOnClickListener {
            dialog.dismiss()
            try {
                restoreFileLauncher.launch(arrayOf("application/json", "text/*"))
            } catch (e: Exception) {
                toast("Could not open the restore file screen: ${e.localizedMessage ?: "Please try again."}")
            }
        }
        dialog.show()
    }

    private fun writeBackup(uri: Uri) {
        try {
            val prefs = getSharedPreferences("water_tracker", MODE_PRIVATE)
            val values = JSONObject()
            for ((key, value) in prefs.all) {
                if (key == "pending_update_apk") continue
                val item = JSONObject()
                when (value) {
                    is String -> item.put("type", "string").put("value", value)
                    is Boolean -> item.put("type", "boolean").put("value", value)
                    is Int -> item.put("type", "int").put("value", value)
                    is Long -> item.put("type", "long").put("value", value)
                    is Float -> item.put("type", "float").put("value", value.toDouble())
                    is Set<*> -> {
                        val strings = JSONArray()
                        value.filterIsInstance<String>().forEach { strings.put(it) }
                        item.put("type", "string_set").put("value", strings)
                    }
                    else -> continue
                }
                values.put(key, item)
            }
            val backup = JSONObject()
                .put("format", "watertracker-backup")
                .put("schemaVersion", 1)
                .put("createdAt", System.currentTimeMillis())
                .put("preferences", values)
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(backup.toString(2))
            } ?: throw IllegalStateException("Could not open the selected backup file.")
            toast("Backup created successfully.")
        } catch (e: Exception) {
            toast("Backup failed: ${e.localizedMessage ?: "Please try another location."}")
        }
    }

    private fun confirmRestore(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Restore this backup?")
            .setMessage("Restoring replaces the current Water Tracker entries and settings with the contents of the selected backup. Create a backup of the current data first if you may need it.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ -> restoreBackup(uri) }
            .show()
    }

    private fun restoreBackup(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalStateException("Could not read the selected backup file.")
            val backup = JSONObject(raw)
            if (backup.optString("format") != "watertracker-backup" ||
                backup.optInt("schemaVersion", -1) != 1) {
                throw IllegalArgumentException("This is not a supported Water Tracker backup.")
            }
            val values = backup.optJSONObject("preferences")
                ?: throw IllegalArgumentException("The backup does not contain app data.")
            val editor = getSharedPreferences("water_tracker", MODE_PRIVATE).edit().clear()
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key == "pending_update_apk") continue
                val item = values.optJSONObject(key)
                    ?: throw IllegalArgumentException("The backup contains an invalid setting.")
                val value = item.get("value")
                when (item.optString("type")) {
                    "string" -> editor.putString(key, value as? String
                        ?: throw IllegalArgumentException("Invalid text value in backup."))
                    "boolean" -> editor.putBoolean(key, value as? Boolean
                        ?: throw IllegalArgumentException("Invalid setting value in backup."))
                    "int" -> editor.putInt(key, (value as? Number)?.toInt()
                        ?: throw IllegalArgumentException("Invalid number in backup."))
                    "long" -> editor.putLong(key, (value as? Number)?.toLong()
                        ?: throw IllegalArgumentException("Invalid number in backup."))
                    "float" -> editor.putFloat(key, (value as? Number)?.toFloat()
                        ?: throw IllegalArgumentException("Invalid number in backup."))
                    "string_set" -> {
                        val array = value as? JSONArray
                            ?: throw IllegalArgumentException("Invalid list in backup.")
                        val set = linkedSetOf<String>()
                        for (i in 0 until array.length()) set.add(array.getString(i))
                        editor.putStringSet(key, set)
                    }
                    else -> throw IllegalArgumentException("The backup contains an unknown data type.")
                }
            }
            if (!editor.commit()) throw IllegalStateException("Android could not save the restored data.")
            loadTodayTotal()
            recreate()
            toast("Backup restored successfully.")
        } catch (e: Exception) {
            toast("Restore failed: ${e.localizedMessage ?: "The backup could not be read."}")
        }
    }

    private fun showAccessibilityDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }
        addSectionTitle(content, "Color and visual accessibility")
        addButton(content, "Color correction / color filters", secondary = true) {
            toast("Enable color correction in Android accessibility settings.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addSectionTitle(content, "Font size")
        addButton(content, "Smaller font", secondary = true) { changeFontSize(-0.1f) }
        addButton(content, "Larger font", secondary = true) { changeFontSize(0.1f) }
        addButton(content, "Reset font size", secondary = true) { getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putFloat("font_scale", 1f).apply(); recreate() }
        addSectionTitle(content, "Font style")
        addButton(content, "Default font", secondary = true) { getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putString("font_style", "default").apply(); recreate() }
        addButton(content, "Serif font", secondary = true) { getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putString("font_style", "serif").apply(); recreate() }
        addButton(content, "Sans-serif font", secondary = true) { getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putString("font_style", "sans").apply(); recreate() }
        addButton(content, "Toggle bold text", secondary = true) { val p = getSharedPreferences("water_tracker", MODE_PRIVATE); p.edit().putBoolean("bold_text", !p.getBoolean("bold_text", false)).apply(); recreate() }
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this).setTitle("Accessibility settings").setView(scroll)
            .setPositiveButton("Close", null).show()
        applyViewOpacity(content, getSharedPreferences("water_tracker", MODE_PRIVATE).getInt("transparency_level", 75).coerceIn(0, 100))
    }

    private fun toggleLocationTagging() {
        val prefs = getSharedPreferences("water_tracker", MODE_PRIVATE)
        val enabled = prefs.getBoolean("location_tagging", false)
        if (enabled) {
            prefs.edit().putBoolean("location_tagging", false).apply()
            locationTaggingEnabled = false
            toast("Location tagging disabled.")
            recreate()
        } else {
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        }
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
        val entryId = java.util.UUID.randomUUID().toString()
        val entry = JSONObject()
            .put("entry_id", entryId)
            .put("amount", amount)
            .put("time", timestamp)
        entries.put(entry)
        totalMl += amount
        saveTodayEntries(entries)

        if (getSharedPreferences("water_tracker", MODE_PRIVATE).getBoolean("location_tagging", false) &&
            (hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))) {
            runCatching {
                LocationServices.getFusedLocationProviderClient(this).lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            val latest = loadTodayEntries()
                            for (i in 0 until latest.length()) {
                                val saved = latest.optJSONObject(i)
                                if (saved?.optString("entry_id") == entryId) {
                                    saved.put("latitude", location.latitude)
                                    saved.put("longitude", location.longitude)
                                    saved.put("location_accuracy_m", location.accuracy.toInt())
                                    saveTodayEntries(latest)
                                    refreshHistoryDisplay()
                                    break
                                }
                            }
                        } else {
                            runOnUiThread { toast("Entry saved, but location was unavailable. Check that Android Location is enabled and try the next entry.") }
                        }
                    }
                    .addOnFailureListener {
                        runOnUiThread { toast("Entry saved, but its location could not be retrieved.") }
                    }
            }.onFailure {
                toast("Entry saved, but its location could not be retrieved.")
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
        val table = android.widget.TableLayout(this).apply {
            isStretchAllColumns = true
            setPadding(dp(6), dp(8), dp(6), dp(12))
        }
        fun addCell(row: android.widget.TableRow, value: String, bold: Boolean = false, color: Int = android.graphics.Color.rgb(55, 83, 101)) {
            row.addView(TextView(this).apply {
                text = value
                textSize = 11f
                setTextColor(color)
                setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(5), dp(8), dp(5), dp(8))
            })
        }
        val header = android.widget.TableRow(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(220, 242, 249))
        }
        listOf("Date", "Time", "Entry", "Location", "Daily goal").forEach { addCell(header, it, true) }
        table.addView(header)

        for (offset in 0..29) {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -offset) }
            val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
            val dateLabel = SimpleDateFormat("dd MMM", Locale.getDefault()).format(cal.time)
            val dayTotal = if (key == todayKey()) totalMl else prefs.getInt("total_ml_$key", 0)
            val dayEntries = runCatching {
                JSONArray(prefs.getString("water_entries_$key", "[]") ?: "[]")
            }.getOrElse { JSONArray() }
            val completed = dayTotal >= dailyGoalMl
            val goalLabel = if (completed) "✓ Done" else "✕ Not done"
            val goalColor = if (completed) android.graphics.Color.rgb(35, 145, 75)
                else android.graphics.Color.rgb(205, 55, 55)

            if (dayEntries.length() == 0) {
                val row = android.widget.TableRow(this)
                addCell(row, dateLabel)
                addCell(row, "—")
                addCell(row, "No entries")
                addCell(row, "—")
                addCell(row, goalLabel, true, goalColor)
                table.addView(row)
            } else {
                for (entryIndex in 0 until dayEntries.length()) {
                    val entry = dayEntries.optJSONObject(entryIndex) ?: continue
                    val row = android.widget.TableRow(this).apply {
                        setBackgroundColor(if ((offset + entryIndex) % 2 == 0)
                            android.graphics.Color.argb(24, 0, 135, 180)
                        else android.graphics.Color.TRANSPARENT)
                    }
                    val time = SimpleDateFormat("hh:mm a", Locale.getDefault())
                        .format(Date(entry.optLong("time")))
                    val location = if (entry.has("latitude") && entry.has("longitude")) {
                        String.format(Locale.US, "%.4f, %.4f",
                            entry.optDouble("latitude"), entry.optDouble("longitude"))
                    } else "—"
                    addCell(row, dateLabel)
                    addCell(row, time)
                    addCell(row, "${entry.optInt("amount")} ml")
                    addCell(row, location)
                    addCell(row, goalLabel, true, goalColor)
                    table.addView(row)
                }
            }
            table.addView(android.view.View(this).apply {
                setBackgroundColor(android.graphics.Color.argb(35, 40, 100, 125))
                layoutParams = android.widget.TableLayout.LayoutParams(-1, dp(1))
            })
        }
        val scroll = ScrollView(this).apply {
            addView(table)
            layoutParams = android.view.ViewGroup.LayoutParams(-1, dp(440))
        }
        AlertDialog.Builder(this).setTitle("Monthly records • Last 30 days")
            .setView(scroll).setPositiveButton("Close", null).show()
        applyViewOpacity(table, prefs.getInt("transparency_level", 75).coerceIn(0, 100))
    }

        private fun requestVoice() {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            startVoiceInput()
        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                AlertDialog.Builder(this)
                    .setTitle("Microphone access")
                    .setMessage("Voice logging needs microphone access only while you use this feature. No audio is recorded in the background.")
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Continue") { _, _ -> audioPermission.launch(Manifest.permission.RECORD_AUDIO) }
                    .show()
            } else {
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun showQuickAddOptions() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        addSectionTitle(content, "Quick add")
        val amounts = arrayOf("100 ml", "150 ml", "200 ml", "250 ml", "300 ml", "350 ml", "400 ml", "500 ml", "750 ml")
        val values = intArrayOf(100, 150, 200, 250, 300, 350, 400, 500, 750)
        val amountGrid = android.widget.GridLayout(this).apply { columnCount = 3; useDefaultMargins = false }
        values.forEachIndexed { index, amount ->
            amountGrid.addView(createActionButton(amounts[index]) {
                addWater(amount)
                toast("$amount ml added.")
            }.apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(42)
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
            })
        }
        content.addView(amountGrid)
        addSectionTitle(content, "Other entry actions")
        addButton(content, "Manual water entry", secondary = true) { showManualWaterEntry() }
        addButton(content, "Undo last entry", secondary = true) { undoLastWaterEntry() }
        addButton(content, "Reset today's total", secondary = true) {
            AlertDialog.Builder(this).setTitle("Reset today's total?")
                .setMessage("This will remove today's saved water entries.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset") { _, _ ->
                    totalMl = 0
                    saveTodayEntries(JSONArray())
                    saveTodayTotal()
                    refreshWaterDisplay()
                    refreshHistoryDisplay()
                    toast("Today's entries reset.")
                }.show()
        }
        addButton(content, "Monthly records (last 30 days)", secondary = true) { showDailyHistory() }
        content.addView(historyText)
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this).setTitle("Water Entries").setView(scroll)
            .setPositiveButton("Close", null).show()
        applyViewOpacity(content, getSharedPreferences("water_tracker", MODE_PRIVATE).getInt("transparency_level", 75).coerceIn(0, 100))
    }

    private fun startVoiceInput() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            toast("Water Tracker cannot access the microphone. Check Settings → Apps → Water Tracker → Permissions.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say: add 250 ml or add 500 ml")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        if (intent.resolveActivity(packageManager) == null) {
            toast("No speech recognition app is available. Install or enable Google Speech Services.")
            return
        }
        try {
            voiceRecognitionLauncher.launch(intent)
        } catch (e: Exception) {
            toast("Could not open speech recognition. Check Google Speech Services and its Microphone permission.")
        }
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
            .putBoolean("sleep_theme_dark", false)
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
        val pendingUpdatePath = getSharedPreferences("water_tracker", MODE_PRIVATE).getString("pending_update_apk", null)
        if (!pendingUpdatePath.isNullOrBlank() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls())) {
            installDownloadedUpdate(java.io.File(pendingUpdatePath))
        }
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
