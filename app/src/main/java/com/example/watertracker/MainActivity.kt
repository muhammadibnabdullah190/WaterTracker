package com.example.watertracker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

class MainActivity : AppCompatActivity() {
    private var totalMl = 0
    private val dailyGoalMl = 2000
    private lateinit var totalText: TextView
    private lateinit var progressText: TextView
    private lateinit var locationText: TextView
    private lateinit var cameraPreview: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraStarted = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else toast("Camera permission was not granted.")
    }
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
        super.onCreate(savedInstanceState)
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

        addButton(layout, "Permission Center – manage app access") {
            startActivity(Intent(this, PermissionCenterActivity::class.java))
        }

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

        setContentView(ScrollView(this).apply { addView(layout) })
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

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
