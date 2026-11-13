package com.example.watertracker

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Health Connect opens this screen from its permission UI so users can understand
 * how Water Tracker handles hydration records.
 */
class PermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = """
                Water Tracker – Health Connect privacy information

                Water Tracker requests access to Hydration records and, if you choose, read-only daily Steps records.

                Read hydration: used to display hydration records already stored in Health Connect.
                Write hydration: used to save water intake entries that you choose to log in Water Tracker.

                Step access is optional and used only to display the current day step total. Water Tracker does not write step records or access heart rate, location, contacts, or other health data.

                You control these permissions in Health Connect and can revoke them at any time. Denying access does not prevent manual water logging in this app.
            """.trimIndent()
            textSize = 17f
            setPadding(48, 48, 48, 48)
        })
    }
}
