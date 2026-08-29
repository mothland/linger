package com.linger.app.report

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.linger.app.LingerApplication
import com.linger.app.R
import com.linger.app.data.AppDatabase
import com.linger.app.data.entity.EventType
import com.linger.app.privacy.PrivacyLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

private const val UNDO_WINDOW_MS = 10_000L

/**
 * Handles ESM notification button taps and swipe-dismisses.
 *
 * Button tap: cancel original → show "Logged: X — Undo?" for 10 s → commit to DB.
 * Undo tap:   cancel pending commit → re-post original question notification.
 * Swipe:      write SELF_REPORT_DISMISSED immediately (no undo).
 *
 * Each question (LIKE, AFFECTIVE_SALIENCE) maintains its own pending commit job and its own
 * undo notification so either can be undone independently. esmPeriodId and questionType are
 * threaded through all intents to tie submissions back to the originating ping.
 */
class EsmResponseReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ESM_RESPONSE   = "com.linger.app.ACTION_ESM_RESPONSE"
        const val ACTION_ESM_UNDO       = "com.linger.app.ACTION_ESM_UNDO"
        const val EXTRA_RESPONSE        = "response"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        // Outlives the receiver; safe because the service keeps the process alive.
        private val pendingCommits = mutableMapOf<String, Job>()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (intent.action) {
            ACTION_ESM_UNDO -> {
                val periodId     = intent.getStringExtra(EsmTrigger.EXTRA_ESM_PERIOD_ID)
                val questionName = intent.getStringExtra(EsmTrigger.EXTRA_ESM_QUESTION) ?: return
                val question     = runCatching { EsmQuestion.valueOf(questionName) }.getOrNull() ?: return

                pendingCommits[questionName]?.cancel()
                pendingCommits.remove(questionName)
                nm.cancel(question.undoNotifId)
                if (periodId != null) EsmTrigger.postEsmNotification(context, periodId, question)
            }

            ACTION_ESM_RESPONSE -> {
                val response     = intent.getStringExtra(EXTRA_RESPONSE) ?: return
                val notifId      = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
                val periodId     = intent.getStringExtra(EsmTrigger.EXTRA_ESM_PERIOD_ID)
                val questionName = intent.getStringExtra(EsmTrigger.EXTRA_ESM_QUESTION) ?: return
                val question     = runCatching { EsmQuestion.valueOf(questionName) }.getOrNull() ?: return

                nm.cancel(notifId)

                if (response == "dismissed") {
                    CoroutineScope(Dispatchers.IO).launch {
                        PrivacyLayer(AppDatabase.getInstance(context)).writeEvent(
                            eventType     = EventType.SELF_REPORT_DISMISSED,
                            sessionId     = null,
                            sourcePackage = context.packageName,
                            rawPayload    = mapOf(
                                "esmPeriodId"    to periodId,
                                "questionType"   to questionName.lowercase(),
                                "completedAtUtc" to now(),
                            ),
                        )
                    }
                    return
                }

                // Cancel any in-flight commit for this question (rapid re-tap).
                pendingCommits[questionName]?.cancel()
                nm.cancel(question.undoNotifId)

                val label = question.actions.firstOrNull { it.second == response }?.first ?: response

                val undoIntent = Intent(ACTION_ESM_UNDO).apply {
                    setPackage(context.packageName)
                    putExtra(EsmTrigger.EXTRA_ESM_PERIOD_ID, periodId)
                    putExtra(EsmTrigger.EXTRA_ESM_QUESTION, questionName)
                }
                val undoPending = PendingIntent.getBroadcast(
                    context,
                    questionName.hashCode(),
                    undoIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                nm.notify(
                    question.undoNotifId,
                    NotificationCompat.Builder(context, LingerApplication.CHANNEL_SELF_REPORT)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("Logged: $label")
                        .setContentText("Tap Undo to change your answer.")
                        .addAction(0, "Undo", undoPending)
                        .setTimeoutAfter(UNDO_WINDOW_MS)
                        .setOnlyAlertOnce(true)
                        .build()
                )

                pendingCommits[questionName] = CoroutineScope(Dispatchers.IO).launch {
                    delay(UNDO_WINDOW_MS)
                    PrivacyLayer(AppDatabase.getInstance(context)).writeEvent(
                        eventType     = EventType.SELF_REPORT_SUBMITTED,
                        sessionId     = null,
                        sourcePackage = context.packageName,
                        rawPayload    = mapOf(
                            "esmPeriodId"    to periodId,
                            "questionType"   to questionName.lowercase(),
                            "response"       to response,
                            "completedAtUtc" to now(),
                        ),
                    )
                    nm.cancel(question.undoNotifId)
                }
            }
        }
    }

    private fun now(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
}
