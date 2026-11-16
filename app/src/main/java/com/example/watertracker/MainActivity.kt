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
        if (it) photoPicker.launch(null) else toast("Camera permission was not granted.")
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
        updateLocationStatus()
        if (results.values.none { it }) toast("Location access was not granted.")
    }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            if (scanningExternalCamera) {
                val input = InputImage.fromBitmap(bitmap, 0)
                BarcodeScanning.getClient().process(input)
                    .addOnSuccessListener { codes ->
                        val value = codes.firstOrNull()?.rawValue
                        toast(if (value != null) "Barcode / QR: $value" else "No barcode or QR code found in the captured image.")
                    }
                    .addOnFailureListener { toast("Could not scan the captured image.") }
            } else toast("Photo captured successfully.")
        }
        scanningExternalCamera = false
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

        val dashboardGrid = android.widget.GridLayout(this).apply {
            columnCount = 2
            rowCount = android.widget.GridLayout.UNDEFINED
            useDefaultMargins = false
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(6)
            }
        }
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
        addDashboardButton(dashboardGrid, "Check for Update", ContextCompat.getDrawable(this, R.drawable.ic_display)!!) { checkForAppUpdate() }
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

        addSectionTitle(page, "Appearance", "Transparent theme with adjustable background transparency.")
        var pendingTransparency = getSharedPreferences("water_tracker", MODE_PRIVATE)
            .getInt("transparency_level", 75).coerceIn(0, 100)
        val transparencyLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.rgb(83, 112, 130))
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }
        page.addView(transparencyLabel)
        val transparencySeek = android.widget.SeekBar(this).apply {
            max = 100
            progress = pendingTransparency
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    pendingTransparency = progress
                    transparencyLabel.text = "Opacity: $pendingTransparency%  •  ${100 - pendingTransparency}% transparent"
                    applyViewOpacity(page, pendingTransparency)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        page.addView(transparencySeek)
        transparencyLabel.text = "Opacity: $pendingTransparency%  •  ${100 - pendingTransparency}% transparent"
        applyViewOpacity(page, pendingTransparency)
        addButton(page, "✓  Save theme changes") {
            getSharedPreferences("water_tracker", MODE_PRIVATE).edit()
                .putString("theme", "transparent")
                .putInt("transparency_level", pendingTransparency)
                .apply()
            toast("Theme changes saved.")
        }

        addButton(page, "♿  Accessibility settings", secondary = true) {
            showAccessibilityDialog()
        }
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
            strokeWidth = dp(1.4).toFloat()
            color = android.graphics.Color.argb(42, 0, 145, 190)
        }
        private val shine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.argb(28, 255, 255, 255)
        }
        private val positions = listOf(0.08f to 0.20f, 0.91f to 0.28f, 0.16f to 0.53f, 0.84f to 0.62f, 0.07f to 0.82f, 0.94f to 0.91f)
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            positions.forEachIndexed { index, point ->
                val size = dp(if (index % 2 == 0) 23 else 16).toFloat()
                val x = width * point.first
                val y = height * point.second
                val path = android.graphics.Path().apply {
                    moveTo(x, y - size)
                    cubicTo(x - size * 0.12f, y - size * 0.48f, x - size * 0.72f, y + size * 0.18f, x, y + size * 0.55f)
                    cubicTo(x + size * 0.72f, y + size * 0.18f, x + size * 0.12f, y - size * 0.48f, x, y - size)
                    close()
                }
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
                height = dp(44)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            icon = graphic
            iconSize = dp(25)
            graphic.setBounds(0, 0, dp(25), dp(25))
            iconPadding = dp(7)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            textSize = 11f
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
        getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putString("theme", "transparent").apply()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
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

    private fun showCameraToolsDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        addButton(content, "Scan barcode / QR code") {
            scanningExternalCamera = true
            if (hasPermission(Manifest.permission.CAMERA)) photoPicker.launch(null)
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        addButton(content, "Take a water bottle photo", secondary = true) {
            scanningExternalCamera = false
            if (hasPermission(Manifest.permission.CAMERA)) photoPicker.launch(null)
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        AlertDialog.Builder(this).setTitle("Camera Tools").setView(content)
            .setPositiveButton("Close", null).show()
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
        toast("Checking the latest verified Water Tracker build…")
        Thread {
            try {
                val connection = java.net.URL("https://api.github.com/repos/muhammadibnabdullah190/WaterTracker/releases/latest")
                    .openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "WaterTracker-Android")
                val code = connection.responseCode
                if (code != 200) throw IllegalStateException(if (code == 404) "No published verified release is available yet." else "GitHub returned HTTP $code.")
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
                val downloadUrl = apkUrl
                runOnUiThread {
                    if (downloadUrl == null) toast("Latest release has no APK attached. Please try again later.")
                    else {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                        try { startActivity(intent) }
                        catch (_: Exception) { toast("Unable to open the verified APK download.") }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Update check failed: ${e.localizedMessage ?: "Please try again later."}") }
            }
        }.start()
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
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            locationTaggingEnabled = true
            prefs.edit().putBoolean("location_tagging", true).apply()
            recreate()
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
        val table = android.widget.TableLayout(this).apply {
            isStretchAllColumns = true
            setPadding(dp(10), dp(10), dp(10), dp(14))
        }
        fun addCell(row: android.widget.TableRow, value: String, bold: Boolean = false, color: Int = android.graphics.Color.rgb(55, 83, 101)) {
            row.addView(TextView(this).apply {
                text = value
                textSize = 12f
                setTextColor(color)
                setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(7), dp(10), dp(7), dp(10))
            })
        }
        val header = android.widget.TableRow(this).apply { setBackgroundColor(android.graphics.Color.rgb(220, 242, 249)) }
        addCell(header, "Date", true)
        addCell(header, "Intake", true)
        addCell(header, "Goal", true)
        table.addView(header)
        for (offset in 0..29) {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -offset) }
            val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
            val label = SimpleDateFormat("dd MMM", Locale.getDefault()).format(cal.time)
            val total = if (key == todayKey()) totalMl else prefs.getInt("total_ml_$key", 0)
            val completed = total >= dailyGoalMl
            val row = android.widget.TableRow(this).apply {
                setBackgroundColor(if (offset % 2 == 0) android.graphics.Color.argb(24, 0, 135, 180) else android.graphics.Color.TRANSPARENT)
            }
            addCell(row, label)
            addCell(row, "$total ml")
            addCell(row, if (completed) "✓ Completed" else "✕ Not completed", true,
                if (completed) android.graphics.Color.rgb(35, 145, 75) else android.graphics.Color.rgb(205, 55, 55))
            table.addView(row)
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
        applyViewOpacity(table, getSharedPreferences("water_tracker", MODE_PRIVATE).getInt("transparency_level", 75).coerceIn(0, 100))
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
            toast("Allow Microphone for Water Tracker, then try Voice logging again.")
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
