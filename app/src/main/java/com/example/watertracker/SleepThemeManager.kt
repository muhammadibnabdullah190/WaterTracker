package com.example.watertracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.SleepClassifyEvent
import com.google.android.gms.location.SleepSegmentRequest

object SleepThemeManager {
    const val AWAKE_THRESHOLD = 20
    const val ASLEEP_THRESHOLD = 80

    fun subscribe(context: Context) =
        ActivityRecognition.getClient(context).requestSleepSegmentUpdates(
            pendingIntent(context),
            SleepSegmentRequest.getDefaultSleepSegmentRequest()
        )

    fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SleepThemeReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, 207, intent, flags)
    }

    fun process(context: Context, intent: Intent) {
        if (!SleepClassifyEvent.hasEvents(intent)) return
        val latest = SleepClassifyEvent.extractEvents(intent)
            .maxByOrNull { it.timestampMillis } ?: return
        val confidence = latest.confidence
        val dark = when {
            confidence <= AWAKE_THRESHOLD -> false
            confidence >= ASLEEP_THRESHOLD -> true
            else -> return // 21–79: preserve the previous theme
        }
        context.getSharedPreferences("water_tracker", Context.MODE_PRIVATE)
            .edit()
            .putInt("sleep_confidence", confidence)
            .putBoolean("sleep_theme_dark", dark)
            .apply()
        context.sendBroadcast(
            Intent(ACTION_THEME_CHANGED).setPackage(context.packageName)
        )
    }

    const val ACTION_THEME_CHANGED = "com.example.watertracker.SLEEP_THEME_CHANGED"
}

class SleepThemeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SleepThemeManager.process(context, intent)
    }
}
