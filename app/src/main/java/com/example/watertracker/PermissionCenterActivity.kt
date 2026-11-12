package com.example.watertracker

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class PermissionCenterActivity : AppCompatActivity() {

    private val hydrationPermissions = setOf(
        HealthPermission.getReadPermission(HydrationRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class)
    )
    private lateinit var content: LinearLayout
    private var healthGranted: Set<String> = emptySet()
    private var healthAvailable = false
    private val runtimeRows = mutableListOf<Pair<TextView, String>>()
    private val specialRows = mutableListOf<() -> Unit>()

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshRuntimeStatuses()
    }

    private val requestHealthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        healthGranted = granted
        refreshHealthStatus()
        toast("Health Connect access updated.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 36, 40, 36)
        }
        root.addView(TextView(this).apply {
            text = "🔐 Permission Center"
            textSize = 27f
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "Manage each permission yourself. Tap Grant or Open settings beside an item. Nothing is enabled automatically."
            textSize = 15f
            setPadding(0, 0, 0, 18)
        })

        // Health Connect is intentionally prominent: it uses its own approval screen,
        // not the standard Android runtime permission dialog.
        section(root, "💧 Health Connect")
        val healthStatus = TextView(this).apply {
            text = "Checking Health Connect…"
            textSize = 14f
            setPadding(0, 4, 0, 8)
        }
        root.addView(healthStatus)
        button(root, "Grant hydration read and write access") {
            requestHydrationAccess()
        }
        button(root, "Open Health Connect app settings") {
            openHealthConnectSettings()
        }
        button(root, "Refresh Health Connect status") { refreshHealthStatus() }
        specialRows.add { healthStatus.text = healthStatusText() }

        section(root, "Runtime permissions")
        root.addView(TextView(this).apply {
            text = "Each item has its own Android permission request. Some permissions may be unavailable or restricted on your Android version."
            textSize = 13f
            setPadding(0, 0, 0, 10)
        })

        permissionRow(root, "Camera", Manifest.permission.CAMERA)
        permissionRow(root, "Microphone", Manifest.permission.RECORD_AUDIO)
        permissionRow(root, "Location – approximate", Manifest.permission.ACCESS_COARSE_LOCATION)
        permissionRow(root, "Location – precise", Manifest.permission.ACCESS_FINE_LOCATION)
        permissionRow(root, "Contacts", Manifest.permission.READ_CONTACTS)
        permissionRow(root, "Phone state", Manifest.permission.READ_PHONE_STATE)
        permissionRow(root, "Make phone calls", Manifest.permission.CALL_PHONE)
        permissionRow(root, "Call log – read", Manifest.permission.READ_CALL_LOG)
        permissionRow(root, "Call log – write", Manifest.permission.WRITE_CALL_LOG)
        permissionRow(root, "SMS – read", Manifest.permission.READ_SMS)
        permissionRow(root, "SMS – receive", Manifest.permission.RECEIVE_SMS)
        permissionRow(root, "Calendar – read", Manifest.permission.READ_CALENDAR)
        permissionRow(root, "Calendar – write", Manifest.permission.WRITE_CALENDAR)

        if (Build.VERSION.SDK_INT >= 33) {
            permissionRow(root, "Notifications", Manifest.permission.POST_NOTIFICATIONS, 33)
            permissionRow(root, "Photos – images", Manifest.permission.READ_MEDIA_IMAGES, 33)
            permissionRow(root, "Videos", Manifest.permission.READ_MEDIA_VIDEO, 33)
            permissionRow(root, "Music and audio", Manifest.permission.READ_MEDIA_AUDIO, 33)
        } else {
            permissionRow(root, "Photos, videos and media (legacy)", Manifest.permission.READ_EXTERNAL_STORAGE, 23, 32)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            permissionRow(root, "Selected photos access", Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, 34)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            permissionRow(root, "Nearby devices – scan", Manifest.permission.BLUETOOTH_SCAN, 31)
            permissionRow(root, "Nearby devices – connect", Manifest.permission.BLUETOOTH_CONNECT, 31)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            permissionRow(root, "Physical activity", Manifest.permission.ACTIVITY_RECOGNITION, 29)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permissionRow(root, "Body sensors", Manifest.permission.BODY_SENSORS, 33)
        } else {
            permissionRow(root, "Body sensors", Manifest.permission.BODY_SENSORS, 23, 32)
        }

        section(root, "Special app access")
        root.addView(TextView(this).apply {
            text = "These are not normal runtime permissions. Android opens Settings and you decide whether to enable each one."
            textSize = 13f
            setPadding(0, 0, 0, 10)
        })
        specialRow(root, "Display over other apps", { if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(this) else true }) {
            openSpecial(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true)
        }
        specialRow(root, "Modify system settings", { if (Build.VERSION.SDK_INT >= 23) Settings.System.canWrite(this) else true }) {
            openSpecial(Settings.ACTION_MANAGE_WRITE_SETTINGS, true)
        }
        specialRow(root, "Usage access", { hasUsageAccess() }) {
            openSpecial(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }
        specialRow(root, "All files access", { Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager() }) {
            if (Build.VERSION.SDK_INT >= 30) openSpecial(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, true)
            else toast("All-files special access is not used on this Android version.")
        }
        specialRow(root, "Exact alarms and reminders", {
            Build.VERSION.SDK_INT < 31 || (getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        }) {
            if (Build.VERSION.SDK_INT >= 31) openSpecial(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true)
            else toast("Exact-alarm special access is not required on this Android version.")
        }
        specialRow(root, "Notification access", { hasNotificationListenerAccess() }) {
            openSpecial(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        specialRow(root, "Accessibility access", { hasAccessibilityAccess() }) {
            openSpecial(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        button(root, "Device administrator settings") { openSpecial(Settings.ACTION_SECURITY_SETTINGS) }
        button(root, "Open this app's Android settings") {
            openSpecial(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
        }

        section(root, "Important limitations")
        root.addView(TextView(this).apply {
            text = "Call Log and SMS access are highly sensitive and commonly restricted by Google Play policy. They are not needed for water tracking. Android may deny or hide permissions based on OS version, app role, and policy. Health Connect access shown here is only for hydration records. Special access always requires your confirmation in Settings."
            textSize = 13f
            setPadding(0, 4, 0, 20)
        })

        content = root
        setContentView(ScrollView(this).apply { addView(root) })
        refreshRuntimeStatuses()
        refreshHealthStatus()
        refreshSpecialStatuses()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) {
            refreshRuntimeStatuses()
            refreshHealthStatus()
            refreshSpecialStatuses()
        }
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setPadding(0, 24, 0, 8)
        })
    }

    private fun button(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        })
    }

    private fun permissionRow(parent: LinearLayout, label: String, permission: String, minApi: Int = 23, maxApi: Int = Int.MAX_VALUE) {
        if (Build.VERSION.SDK_INT < minApi || Build.VERSION.SDK_INT > maxApi) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        row.addView(TextView(this).apply { text = label; textSize = 15f })
        val status = TextView(this).apply { textSize = 12f; setPadding(0, 2, 0, 2) }
        row.addView(status)
        button(row, "Grant / manage") { requestPermission.launch(permission) }
        parent.addView(row)
        runtimeRows.add(status to permission)
        updateRuntimeStatus(status, permission)
    }

    private fun updateRuntimeStatus(view: TextView, permission: String) {
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        view.text = if (granted) "Status: Allowed" else "Status: Not allowed"
    }

    private fun refreshRuntimeStatuses() {
        if (!::content.isInitialized) return
        runtimeRows.forEach { (view, permission) -> updateRuntimeStatus(view, permission) }
    }

    private fun requestHydrationAccess() {
        val status = HealthConnectClient.getSdkStatus(this, "com.google.android.apps.healthdata")
        if (status == HealthConnectClient.SDK_AVAILABLE) {
            requestHealthPermissions.launch(hydrationPermissions)
        } else {
            toast("Health Connect is not installed or available. Install/update it, then try again.")
            openHealthConnectSettings()
        }
    }

    private fun refreshHealthStatus() {
        if (!::content.isInitialized) return
        val sdkStatus = HealthConnectClient.getSdkStatus(this, "com.google.android.apps.healthdata")
        healthAvailable = sdkStatus == HealthConnectClient.SDK_AVAILABLE
        if (healthAvailable) {
            lifecycleScope.launch {
                try {
                    val client = HealthConnectClient.getOrCreate(this@PermissionCenterActivity)
                    healthGranted = client.permissionController.getGrantedPermissions()
                } catch (_: Exception) {
                    // Keep the status screen usable even if the Health Connect provider is temporarily unavailable.
                }
                refreshSpecialStatuses()
            }
        }
        refreshSpecialStatuses()
    }

    private fun healthStatusText(): String {
        if (!healthAvailable) return "Status: Health Connect not available on this device"
        val read = HealthPermission.getReadPermission(HydrationRecord::class) in healthGranted
        val write = HealthPermission.getWritePermission(HydrationRecord::class) in healthGranted
        return "Status: Read ${if (read) "Allowed" else "Not allowed"} • Write ${if (write) "Allowed" else "Not allowed"}"
    }

    private fun specialRow(parent: LinearLayout, label: String, granted: () -> Boolean, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        row.addView(TextView(this).apply { text = label; textSize = 15f })
        val status = TextView(this).apply { textSize = 12f; setPadding(0, 2, 0, 2) }
        row.addView(status)
        button(row, "Open settings") { action() }
        parent.addView(row)
        specialRows.add { status.text = if (granted()) "Status: Allowed" else "Status: Not allowed" }
        specialRows.last().invoke()
    }

    private fun refreshSpecialStatuses() {
        if (!::content.isInitialized) return
        specialRows.forEach { it.invoke() }
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasNotificationListenerAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    private fun hasAccessibilityAccess(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true

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