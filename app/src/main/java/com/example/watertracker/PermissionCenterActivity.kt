package com.example.watertracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity\nimport androidx.health.connect.client.PermissionController\nimport androidx.health.connect.client.permission.HealthPermission\nimport androidx.health.connect.client.records.HydrationRecord\nimport androidx.health.connect.client.HealthConnectClient

/**
 * Permission Center: permissions are grouped by purpose and requested only
 * after the user taps the relevant control. Special access is always granted
 * by the user in Android Settings.
 */
class PermissionCenterActivity : AppCompatActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.count { it.value }
        val denied = results.size - granted
        Toast.makeText(this, "Granted: $granted • Not granted: $denied", Toast.LENGTH_LONG).show()
    }

    private val hydrationPermissions = setOf(\n        HealthPermission.getReadPermission(HydrationRecord::class),\n        HealthPermission.getWritePermission(HydrationRecord::class)\n    )\n\n    private val requestHealthPermissions = registerForActivityResult(\n        PermissionController.createRequestPermissionResultContract()\n    ) { granted ->\n        val count = granted.intersect(hydrationPermissions).size\n        info("Health Connect hydration permissions granted: $count of 2")\n    }\n\n    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
        }

        root.addView(TextView(this).apply {
            text = "Permission Center"
            textSize = 27f
        })
        root.addView(TextView(this).apply {
            text = "Choose each permission group separately. Nothing is requested automatically. Grant only access you understand and need."
            textSize = 15f
            setPadding(0, 12, 0, 20)
        })

        section(root, "Core and personal data")
        permissionButton(root, "Camera", Manifest.permission.CAMERA)
        permissionButton(root, "Microphone", Manifest.permission.RECORD_AUDIO)
        permissionButton(root, "Location (approximate and precise)", Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        permissionButton(root, "Contacts", Manifest.permission.READ_CONTACTS)
        permissionButton(root, "Phone (phone state and calling)", Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)\n        permissionButton(root, "Call log (read and write)", Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)
        permissionButton(root, "SMS (read and receive)", Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
        permissionButton(root, "Calendar", Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        section(root, "Notifications and media")
        if (Build.VERSION.SDK_INT >= 33) {
            permissionButton(root, "Notifications", Manifest.permission.POST_NOTIFICATIONS)
            permissionButton(root, "Photos and videos", Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            permissionButton(root, "Music and audio", Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissionButton(root, "Photos, videos and audio (legacy storage)", Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            permissionButton(root, "Selected photos access", Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }

        section(root, "Devices and activity")
        if (Build.VERSION.SDK_INT >= 31) {
            permissionButton(root, "Nearby devices (Bluetooth scan/connect)", Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            permissionButton(root, "Physical activity", Manifest.permission.ACTIVITY_RECOGNITION)
        }

        section(root, "Special app access – Android Settings")
        settingsButton(root, "Display over other apps") { openSpecial(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
        settingsButton(root, "Modify system settings") { openSpecial(Settings.ACTION_MANAGE_WRITE_SETTINGS) }
        settingsButton(root, "Usage access") { openSpecial(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        settingsButton(root, "All files access") {
            if (Build.VERSION.SDK_INT >= 30) openSpecial(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            else info("All-files access is not available on this Android version.")
        }
        settingsButton(root, "Exact alarms and reminders") {
            if (Build.VERSION.SDK_INT >= 31) openSpecial(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            else info("Exact-alarm special access is not required on this Android version.")
        }
        settingsButton(root, "Notification access") { openSpecial(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        settingsButton(root, "Accessibility access") { openSpecial(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        settingsButton(root, "Device administrator settings") { openSpecial(Settings.ACTION_SECURITY_SETTINGS) }
        settingsButton(root, "Health Connect settings") {
            try {
                startActivity(Intent("android.health.connect.action.HEALTH_HOME"))
            } catch (_: Exception) {
                openSpecial(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            }
        }

        section(root, "Privacy notes")
        root.addView(TextView(this).apply {
            text = "• Android may deny, limit, or omit permissions depending on OS version and device policy.\n\n" +
                "• SMS and call permissions are highly sensitive and are normally restricted by Google Play policy. They are not needed for water tracking.\n\n" +
                "• Contacts, photos, videos and audio are better accessed with Android's system pickers when possible.\n\n" +
                "• Accessibility, notification listener, device administrator, overlay, usage access and all-files access require separate user action in Settings. This app does not activate them silently.\n\n" +
                "• Health Connect access here is limited to reading and writing hydration records, and you must approve it in the Health Connect permission screen."
            textSize = 14f
            setPadding(0, 8, 0, 24)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun section(root: LinearLayout, title: String) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setPadding(0, 24, 0, 8)
        })
    }

    private fun permissionButton(root: LinearLayout, label: String, vararg permissions: String) {
        root.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener {
                val available = permissions.filter { permission ->
                    // Do not request platform permissions that do not exist on this OS.
                    permission != Manifest.permission.READ_MEDIA_IMAGES || Build.VERSION.SDK_INT >= 33
                }.toTypedArray()
                if (available.isNotEmpty()) requestPermissions.launch(available)
            }
        })
    }

    private fun settingsButton(root: LinearLayout, label: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        })
    }

    private fun openSpecial(action: String) {
        try {
            val intent = Intent(action)
            if (action == Settings.ACTION_MANAGE_OVERLAY_PERMISSION ||
                action == Settings.ACTION_MANAGE_WRITE_SETTINGS ||
                (Build.VERSION.SDK_INT >= 30 && action == Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) ||
                (Build.VERSION.SDK_INT >= 31 && action == Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            ) {
                intent.data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun info(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
