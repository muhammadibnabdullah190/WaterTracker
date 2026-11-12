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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class PermissionCenterActivity : AppCompatActivity() {
    private val readHydration = HealthPermission.getReadPermission(HydrationRecord::class)
    private val writeHydration = HealthPermission.getWritePermission(HydrationRecord::class)
    private lateinit var page: LinearLayout
    private lateinit var healthStatus: TextView
    private val runtimeStatus = mutableMapOf<String, TextView>()
    private var grantedHealth = emptySet<String>()
    private var healthAvailable = false

    private val runtimeRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshStatuses()
    }
    private val healthRequest = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        refreshHealth()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
        }
        root.addView(TextView(this).apply {
            text = "🔐 Permission Center"
            textSize = 26f
            setPadding(0, 0, 0, 6)
        })
        root.addView(TextView(this).apply {
            text = "Choose a category. Every permission is optional unless a feature you use needs it. Nothing is granted automatically."
            textSize = 14f
            setPadding(0, 0, 0, 14)
        })
        val tabs = TabLayout(this)
        listOf("Core", "Media", "Health", "Phone", "Special", "Theme").forEach { tabs.addTab(tabs.newTab().setText(it)) }
        root.addView(tabs)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 0)
        }
        root.addView(ScrollView(this).apply { addView(page) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) = showTab(tab.position)
        })
        showTab(0)
        refreshStatuses()
        refreshHealth()
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
        refreshHealth()
    }

    private fun applySavedTheme() {
        val mode = getSharedPreferences("water_tracker", MODE_PRIVATE).getString("theme", "system")
        val night = when (mode) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(night)
    }

    private fun showTab(index: Int) {
        page.removeAllViews()
        runtimeStatus.clear()
        when (index) {
            0 -> {
                heading("Core water-tracking features")
                permission("Camera", Manifest.permission.CAMERA, "Optional", "Only needed if you use bottle/barcode scanning or take a water photo. Deny by default to prevent unnecessary camera access.")
                permission("Microphone", Manifest.permission.RECORD_AUDIO, "Optional", "Only needed for voice logging. Deny by default because manual water entry works without recording audio.")
                permission("Notifications", Manifest.permission.POST_NOTIFICATIONS, "Optional", "Needed only for drinking reminders. Deny by default if you do not want alerts.")
                permission("Location (approximate)", Manifest.permission.ACCESS_COARSE_LOCATION, "Not required", "Water totals do not need location. Deny by default to avoid collecting location information.")
                permission("Location (precise)", Manifest.permission.ACCESS_FINE_LOCATION, "Not required", "No hydration feature needs precise location. Deny by default because it reveals more precise whereabouts.")
            }
            1 -> {
                heading("Photos, videos, audio and nearby devices")
                if (Build.VERSION.SDK_INT >= 33) {
                    permission("Photos / images", Manifest.permission.READ_MEDIA_IMAGES, "Optional", "Only if you add a feature to browse existing pictures. Prefer Android Photo Picker; deny broad library access by default.")
                    permission("Videos", Manifest.permission.READ_MEDIA_VIDEO, "Not required", "The tracker does not need to browse your video library. Deny by default to protect personal media.")
                    permission("Music and audio", Manifest.permission.READ_MEDIA_AUDIO, "Not required", "Water logging does not play or manage your music library. Deny by default.")
                } else permission("Photos, videos and media", Manifest.permission.READ_EXTERNAL_STORAGE, "Optional", "Only for selecting existing media on older Android. Prefer system pickers and deny broad storage access by default.")
                if (Build.VERSION.SDK_INT >= 34) permission("Selected photos access", Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, "Optional", "Lets you share selected photos only when using a photo feature. Grant only when needed.")
                if (Build.VERSION.SDK_INT >= 31) {
                    permission("Nearby devices – scan", Manifest.permission.BLUETOOTH_SCAN, "Not required", "Core hydration tracking does not scan nearby devices. Deny by default.")
                    permission("Nearby devices – connect", Manifest.permission.BLUETOOTH_CONNECT, "Not required", "No Bluetooth accessory is required for manual water logging. Deny by default.")
                }
            }
            2 -> {
                heading("Health Connect – hydration only")
                addText("Health Connect has its own approval screen. It will not appear in the normal Android app-permissions list. Read and Write are separate data permissions.")
                healthStatus = addText("Checking Health Connect…")
                healthCard("Read hydration", "Optional", "Allows Water Tracker to read hydration entries already saved in Health Connect. Deny by default if you only want manual tracking; it is not needed to add water in this app.", readHydration)
                healthCard("Write hydration", "Optional", "Allows Water Tracker to save water entries you log to Health Connect. Deny by default unless you want your logged drinks shared with Health Connect.", writeHydration)
                actionButton("Open Health Connect settings") { openHealthConnectSettings() }
                actionButton("Refresh Health Connect status") { refreshHealth() }
                addText("If the app is missing from Health Connect, update/install Health Connect, reopen this app, and request Read or Write here. Your Android version must support Health Connect.")
                if (Build.VERSION.SDK_INT >= 29) permission("Physical activity", Manifest.permission.ACTIVITY_RECOGNITION, "Not required", "A water tracker can set reminders without step/activity tracking. Deny by default unless an activity-based feature is added.")
                permission("Body sensors", Manifest.permission.BODY_SENSORS, "Not required", "Hydration logging does not require body-sensor readings. Deny by default.")
            }
            3 -> {
                heading("Phone, contacts, messages and calendar")
                permission("Contacts", Manifest.permission.READ_CONTACTS, "Not required", "Water tracking does not need your address book. Use Android's contact picker if a future share feature needs a contact.")
                permission("Phone state", Manifest.permission.READ_PHONE_STATE, "Not required", "No phone-state feature is needed for hydration. Deny by default.")
                permission("Make phone calls", Manifest.permission.CALL_PHONE, "Not required", "The app does not need to place calls. If support is added, opening the dialler is safer than direct calling.")
                permission("Call log – read", Manifest.permission.READ_CALL_LOG, "Not required", "Call history is highly sensitive and unrelated to water tracking. Deny by default.")
                permission("Call log – write", Manifest.permission.WRITE_CALL_LOG, "Not required", "Editing call history is unrelated to hydration. Deny by default.")
                permission("SMS – read", Manifest.permission.READ_SMS, "Not required", "SMS contents are private and not needed by this app. Deny by default.")
                permission("SMS – receive", Manifest.permission.RECEIVE_SMS, "Not required", "The app does not need to intercept messages. Deny by default.")
                permission("Calendar – read", Manifest.permission.READ_CALENDAR, "Not required", "Hydration logging does not need calendar events. Deny by default.")
                permission("Calendar – write", Manifest.permission.WRITE_CALENDAR, "Not required", "The app can remind you with notifications without editing your calendar. Deny by default.")
            }
            4 -> {
                heading("Special Android access")
                addText("These controls open Android Settings. You decide whether to enable each one. Special access is not required for basic water tracking.")
                special("Display over other apps", "Not required", "A floating overlay is unnecessary; use normal app notifications instead.", Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                special("Modify system settings", "Not required", "Changing device settings is unrelated to hydration.", Settings.ACTION_MANAGE_WRITE_SETTINGS)
                special("Usage access", "Not required", "The app does not need to inspect how you use other apps.", Settings.ACTION_USAGE_ACCESS_SETTINGS)
                special("All files access", "Not required", "Water Tracker can store its own data without access to all device files.", Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                special("Notification access", "Not required", "The app only sends its own reminders; it does not need to read other apps' notifications.", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                special("Accessibility access", "Not required", "Accessibility control is not part of water tracking and would expose broad device interaction.", Settings.ACTION_ACCESSIBILITY_SETTINGS)
                special("Exact alarms", "Optional", "Useful only if you choose precise scheduled reminders. Deny by default if ordinary notifications are sufficient.", Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                special("Device administrator", "Not required", "A water tracker should not control device-wide security or lock functions.", Settings.ACTION_SECURITY_SETTINGS)
                special("This app's Android settings", "Useful", "Open Android's app-info page to review or revoke permissions.", Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            }
            else -> {
                heading("Appearance")
                addText("Choose how Water Tracker looks. System Theme follows your phone's display setting.")
                actionButton("☀  Light Mode") { saveTheme("light") }
                actionButton("☾  Dark Mode") { saveTheme("dark") }
                actionButton("◐  System Theme") { saveTheme("system") }
                val selected = getSharedPreferences("water_tracker", MODE_PRIVATE).getString("theme", "system")
                addText("Current selection: ${selected?.replaceFirstChar { it.uppercase() } ?: "System"}")
            }
        }
    }

    private fun heading(value: String) {
        page.addView(TextView(this).apply { text = value; textSize = 20f; setPadding(0, 8, 0, 12) })
    }

    private fun addText(value: String): TextView {
        val view = TextView(this).apply { text = value; textSize = 14f; setPadding(0, 5, 0, 9) }
        page.addView(view)
        return view
    }

    private fun actionButton(label: String, action: () -> Unit) {
        page.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } })
    }

    private fun permission(label: String, name: String, need: String, reason: String) {
        if (name == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) return
        if (name == Manifest.permission.READ_MEDIA_IMAGES && Build.VERSION.SDK_INT < 33) return
        if (name == Manifest.permission.READ_MEDIA_VIDEO && Build.VERSION.SDK_INT < 33) return
        if (name == Manifest.permission.READ_MEDIA_AUDIO && Build.VERSION.SDK_INT < 33) return
        if (name == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && Build.VERSION.SDK_INT < 34) return
        if ((name == Manifest.permission.BLUETOOTH_SCAN || name == Manifest.permission.BLUETOOTH_CONNECT) && Build.VERSION.SDK_INT < 31) return
        if (name == Manifest.permission.ACTIVITY_RECOGNITION && Build.VERSION.SDK_INT < 29) return
        val status = addText("$label\n$need • Status: checking")
        runtimeStatus[name] = status
        addText(reason)
        actionButton("Request / manage $label") {
            if (name == Manifest.permission.MANAGE_EXTERNAL_STORAGE) {
                openAppSpecificSettings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            } else runtimeRequest.launch(name)
        }
        refreshOne(name)
    }

    private fun healthCard(label: String, need: String, reason: String, permission: String) {
        addText("$label\n$need • Status: checking")
        addText(reason)
        actionButton("Grant $label access") {
            val status = HealthConnectClient.getSdkStatus(this, "com.google.android.apps.healthdata")
            if (status != HealthConnectClient.SDK_AVAILABLE) {
                toast("Health Connect is unavailable. Install or update it, then try again.")
                openHealthConnectSettings()
            } else healthRequest.launch(setOf(permission))
        }
    }

    private fun special(label: String, need: String, reason: String, action: String) {
        addText("$label\n$need")
        addText(reason)
        actionButton("Open $label settings") {
            when (action) {
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION -> if (Build.VERSION.SDK_INT >= 30) openAppSpecificSettings(action) else toast("Not available on this Android version.")
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Settings.ACTION_MANAGE_WRITE_SETTINGS -> if (Build.VERSION.SDK_INT >= 23) openAppSpecificSettings(action) else toast("Not available on this Android version.")
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) openAppSpecificSettings(action) else toast("Not available on this Android version.")
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION -> openAppSpecificSettings(action)
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS -> openAppSpecificSettings(action)
                else -> try { startActivity(Intent(action)) } catch (_: Exception) { openAppSpecificSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
            }
        }
    }

    private fun openAppSpecificSettings(action: String) {
        try {
            startActivity(Intent(action).apply { data = Uri.parse("package:$packageName") })
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") })
        }
    }

    private fun refreshOne(permission: String) {
        runtimeStatus[permission]?.let { view ->
            val allowed = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            view.text = "${permission.substringAfterLast('.')} • Status: ${if (allowed) "Allowed" else "Not allowed"}"
        }
    }

    private fun refreshStatuses() { runtimeStatus.keys.toList().forEach { refreshOne(it) }; refreshHealth() }

    private fun refreshHealth() {
        if (!::healthStatus.isInitialized) return
        healthAvailable = HealthConnectClient.getSdkStatus(this, "com.google.android.apps.healthdata") == HealthConnectClient.SDK_AVAILABLE
        if (!healthAvailable) {
            healthStatus.text = "Health Connect status: unavailable – install/update Health Connect."
            return
        }
        lifecycleScope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(this@PermissionCenterActivity)
                grantedHealth = client.permissionController.getGrantedPermissions()
                healthStatus.text = "Health Connect available • Read hydration: ${if (readHydration in grantedHealth) "Allowed" else "Not allowed"} • Write hydration: ${if (writeHydration in grantedHealth) "Allowed" else "Not allowed"}"
            } catch (e: Exception) {
                healthStatus.text = "Health Connect is installed, but access status could not be checked. Open Health Connect settings to review."
            }
        }
    }

    private fun refreshHealthStatus() = refreshHealth()

    private fun openHealthConnectSettings() {
        try { startActivity(Intent("android.health.connect.action.HEALTH_HOME")) }
        catch (_: Exception) { openAppSpecificSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
    }

    private fun saveTheme(theme: String) {
        getSharedPreferences("water_tracker", MODE_PRIVATE).edit().putString("theme", theme).apply()
        applySavedTheme()
        recreate()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
