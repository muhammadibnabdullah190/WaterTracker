package com.example.watertracker

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord

class PermissionCenterActivity : AppCompatActivity() {

    private val hydrationPermissions = setOf(
        HealthPermission.getReadPermission(HydrationRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class)
    )

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.count { it }
        Toast.makeText(this, "Granted: $granted of ${result.size}", Toast.LENGTH_LONG).show()
    }

    private val requestHealthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        Toast.makeText(
            this,
            "Health Connect hydration permissions granted: ${granted.intersect(hydrationPermissions).size} of 2",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
        permissionButton(root, "Location", Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        permissionButton(root, "Contacts", Manifest.permission.READ_CONTACTS)
        permissionButton(root, "Phone", Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)
        permissionButton(root, "Call log", Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)
        permissionButton(root, "SMS", Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
        permissionButton(root, "Calendar", Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        section(root, "Notifications and media")
        if (Build.VERSION.SDK_INT >= 33) {
            permissionButton(root, "Notifications", Manifest.permission.POST_NOTIFICATIONS)
            permissionButton(root, "Photos and videos", Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            permissionButton(root, "Music and audio", Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissionButton(root, "Photos, videos and audio", Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            permissionButton(root, "Selected photos access", Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }

        section(root, "Devices and activity")
        if (Build.VERSION.SDK_INT >= 31) {
            permissionButton(root, "Nearby devices", Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            permissionButton(root, "Physical activity", Manifest.permission.ACTIVITY_RECOGNITION)
        }

        section(root, "Health Connect")
        button(root, "Allow hydration read/write access") {
            val status = HealthConnectClient.getSdkStatus(this, "com.google.android.apps.healthdata")
            if (status == HealthConnectClient.SDK_AVAILABLE) {
                requestHealthPermissions.launch(hydrationPermissions)
            } else {
                toast("Install or update Health Connect, then try again.")
                openHealthConnectSettings()
            }
        }
        button(root, "Open Health Connect settings") { openHealthConnectSettings() }

        section(root, "Special app access – Android Settings")
        button(root, "Display over other apps") { openSpecial(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }
        button(root, "Modify system settings") { openSpecial(Settings.ACTION_MANAGE_WRITE_SETTINGS, true) }
        button(root, "Usage access") { openSpecial(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        button(root, "All files access") {
            if (Build.VERSION.SDK_INT >= 30) openSpecial(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, true)
            else toast("Not available on this Android version.")
        }
        button(root, "Exact alarms and reminders") {
            if (Build.VERSION.SDK_INT >= 31) openSpecial(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true)
            else toast("Not required on this Android version.")
        }
        button(root, "Notification access") { openSpecial(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        button(root, "Accessibility settings") { openSpecial(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        button(root, "Device administrator settings") { openSpecial(Settings.ACTION_SECURITY_SETTINGS) }

        section(root, "Privacy notes")
        root.addView(TextView(this).apply {
            text = "Android may restrict permissions by OS version and policy. Call Log and SMS permissions are highly sensitive and usually restricted by Google Play; they are not needed for water tracking. Special access must be enabled manually in Settings. Health Connect access is limited to hydration records and requires your separate approval."
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

    private fun button(root: LinearLayout, label: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        })
    }

    private fun permissionButton(root: LinearLayout, label: String, vararg permissions: String) {
        button(root, label) { requestPermissions.launch(permissions.map { it.toString() }.toTypedArray()) }
    }

    private fun openSpecial(action: String, withPackage: Boolean = false) {
        try {
            val intent = Intent(action)
            if (withPackage) intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun openHealthConnectSettings() {
        try {
            startActivity(Intent("android.health.connect.action.HEALTH_HOME"))
        } catch (_: Exception) {
            openSpecial(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}