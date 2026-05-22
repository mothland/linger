package com.irin.flowlog

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.irin.flowlog.R
import java.time.Instant
import java.time.format.DateTimeFormatter

class FlowLogApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        checkForUnexpectedStop()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // ESM prompt — fires every 5 minutes while recording is active.
        // IMPORTANCE_DEFAULT so it makes a sound/badge (user needs to notice it).
        if (nm.getNotificationChannel(CHANNEL_SELF_REPORT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SELF_REPORT, "Scrolling check-in", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Periodically asks how your recent scrolling felt. Tap a button to respond without opening the app." }
            )
        }

        // Foreground service status — persistent, silent. IMPORTANCE_LOW suppresses sound/badge.
        if (nm.getNotificationChannel(CHANNEL_SERVICE_STATUS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE_STATUS, "Recording status", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Persistent notification shown while FlowLog is actively recording." }
            )
        }
    }

    /**
     * Detects whether the service was killed unexpectedly by comparing the heartbeat timestamp
     * written every 30 s with the current time. If the gap exceeds 60 s and the service was
     * supposed to be running, it was killed without a clean shutdown.
     */
    private fun checkForUnexpectedStop() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val wasRunning    = prefs.getBoolean(KEY_SERVICE_RUNNING, false)
        val lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT_MS, 0L)
        val now           = System.currentTimeMillis()

        if (wasRunning && (now - lastHeartbeat) > 60_000L) {
            val lastEventMs = prefs.getLong(KEY_LAST_EVENT_MS, 0L)
            val timeStr = if (lastEventMs > 0L) {
                DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(lastEventMs))
            } else "unknown"

            val nm = getSystemService(NotificationManager::class.java)
            val notif = NotificationCompat.Builder(this, CHANNEL_SERVICE_STATUS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("FlowLog was stopped unexpectedly")
                .setContentText("Your data up to $timeStr was saved.")
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_CRASH, notif)

            // Clear the flag so this notification doesn't reappear on the next launch.
            prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply()
        }
    }

    companion object {
        const val PREFS_NAME             = "flowlog_prefs"
        const val KEY_SERVICE_RUNNING    = "service_running"
        const val KEY_LAST_HEARTBEAT_MS  = "last_heartbeat_ms"
        const val KEY_LAST_EVENT_MS      = "last_event_ms"
        const val CHANNEL_SELF_REPORT    = "self_report"
        const val CHANNEL_SERVICE_STATUS = "service_status"
        const val NOTIF_FOREGROUND       = 2001
        const val NOTIF_CRASH            = 2002
    }
}
