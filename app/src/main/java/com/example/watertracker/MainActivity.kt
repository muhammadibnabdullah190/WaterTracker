package com.example.watertracker

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var totalMl = 0
    private lateinit var totalText: TextView
    private lateinit var cameraPreview: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraStarted = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else toast("Camera permission is needed for scanning.")
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startVoiceInput() else toast("Microphone permission is needed for voice logging.")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) showReminderNotification()
    }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) {
        if (it != null) toast("Photo captured.")
    }
    private val contactPicker = registerForActivityResult(ActivityResultContracts.PickContact()) { uri: Uri? ->
        if (uri != null) toast("Contact selected.")
    }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) toast("Audio selected.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        buildUi()
    }

    private fun buildUi() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 24)
        }
        layout.addView(TextView(this).apply {
            text = "💧 Water Tracker"
            textSize = 26f
        })
        totalText = TextView(this).apply {
            text = "Today's water: 0 ml"
            textSize = 22f
            setPadding(0, 24, 0, 24)
        }
        layout.addView(totalText)
        addButton(layout, "Add 250 ml") { addWater(250) }
        addButton(layout, "Add 500 ml") { addWater(500) }
        addButton(layout, "Voice log water") { requestVoice() }
        addButton(layout, "Open barcode / QR scanner") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        cameraPreview = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 420)
        }
        layout.addView(cameraPreview)
        addButton(layout, "Take a photo") { photoPicker.launch(null) }
        addButton(layout, "Choose music / audio") { audioPicker.launch(arrayOf("audio/*")) }
        addButton(layout, "Choose a contact") { contactPicker.launch(null) }
        addButton(layout, "Open phone dialler") {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
        }
        addButton(layout, "Test reminder notification") { requestNotification() }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            setOnClickListener { action() }
        })
    }

    private fun addWater(amount: Int) {
        totalMl += amount
        totalText.text = "Today's water: $totalMl ml"
    }

    private fun requestVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput()
        } else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Speech recognition is not available.")
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
                else -> toast("Say 250 or 500 millilitres to log water.")
            }
        }
    }

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(cameraPreview.surfaceProvider) }
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
                                runOnUiThread { toast("Scanned: $value") }
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

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else showReminderNotification()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("water_reminders", "Water reminders", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showReminderNotification() {
        val notification = NotificationCompat.Builder(this, "water_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Water Tracker")
            .setContentText("Time to drink some water.")
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < 33 ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            manager.notify(1, notification)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
