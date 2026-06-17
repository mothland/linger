package com.irin.flowlog.report

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.irin.flowlog.FlowLogApplication
import com.irin.flowlog.R
import com.irin.flowlog.ServiceStats
import com.irin.flowlog.data.AppDatabase
import com.irin.flowlog.data.entity.EventType
import com.irin.flowlog.privacy.PrivacyLayer
import com.irin.flowlog.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

const val ESM_INTERVAL_MS = 5 * 60 * 1_000L

enum class EsmQuestion(
    val notifId: Int,
    val undoNotifId: Int,
    val questionText: String,
    val actions: List<Pair<String, String>>,  // label to responseValue
) {
    LIKING_ESM_VAL(
        notifId      = 1002,
        undoNotifId  = 1003,
        questionText = "How was the last few minutes of scrolling?",
        actions      = listOf("Pretty good" to "pretty_good", "Eh" to "eh", "It sucked" to "it_sucked"),
    ),
    AFFECTIVE_SALIENCE_ESM_VAL(
        notifId      = 1004,
        undoNotifId  = 1005,
        questionText = "Did anything catch your attention or feel strong, positively or negatively?",
        actions      = listOf("Yesss" to "yes", "Not really" to "not_really"),
    ),
}

/**
 * Fires ESM ping notifications on a fixed interval, but only during active Instagram sessions.
 * Each ping shows both questions simultaneously; they share an esmPeriodId so responses from
 * the same moment can be grouped in analysis.
 *
 * Calling onSessionEnded() resets the countdown so the user is never pinged right after
 * leaving Instagram or during an idle gap between sessions.
 */
class EsmTrigger(
    private val context: Context,
    private val db: AppDatabase,
) {
    private var timerJob: Job? = null
    private lateinit var scope: CoroutineScope
    var sessionManager: SessionManager? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
        scheduleTimer()
    }

    fun onSessionStarted() = scheduleTimer()

    fun onSessionEnded() = scheduleTimer()

    suspend fun fireForTesting() = fireNotifications()

    private fun scheduleTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(ESM_INTERVAL_MS)
                if (sessionManager?.activeSessionId != null) {
                    fireNotifications()
                }
            }
        }
    }

    private suspend fun fireNotifications() {
        val periodId = UUID.randomUUID().toString()
        val privacy  = PrivacyLayer(db)
        for (question in EsmQuestion.values()) {
            privacy.writeEvent(
                eventType     = EventType.SELF_REPORT_SHOWN,
                sessionId     = sessionManager?.activeSessionId ?: ServiceStats.activeSessionId.value,
                sourcePackage = context.packageName,
                rawPayload    = mapOf(
                    "esmPeriodId"  to periodId,
                    "questionType" to question.name.lowercase(),
                ),
            )
            postEsmNotification(context, periodId, question)
        }
    }

    companion object {
        const val EXTRA_ESM_PERIOD_ID = "esm_period_id"
        const val EXTRA_ESM_QUESTION  = "esm_question"

        fun postEsmNotification(context: Context, periodId: String, question: EsmQuestion) {
            fun actionPending(response: String): PendingIntent {
                val intent = Intent(EsmResponseReceiver.ACTION_ESM_RESPONSE).apply {
                    setPackage(context.packageName)
                    putExtra(EsmResponseReceiver.EXTRA_RESPONSE, response)
                    putExtra(EsmResponseReceiver.EXTRA_NOTIFICATION_ID, question.notifId)
                    putExtra(EXTRA_ESM_PERIOD_ID, periodId)
                    putExtra(EXTRA_ESM_QUESTION, question.name)
                }
                return PendingIntent.getBroadcast(
                    context,
                    (question.name + response).hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

            val builder = NotificationCompat.Builder(context, FlowLogApplication.CHANNEL_SELF_REPORT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(question.questionText)
                .setAutoCancel(true)
                .setDeleteIntent(actionPending("dismissed"))
            for ((label, value) in question.actions) {
                builder.addAction(0, label, actionPending(value))
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(question.notifId, builder.build())
        }
    }
}
