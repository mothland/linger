package com.irin.flowlog.session

import android.util.Log
import com.irin.flowlog.data.AppDatabase
import com.irin.flowlog.data.entity.EventType
import com.irin.flowlog.data.entity.SessionEntity
import com.irin.flowlog.privacy.PrivacyLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val TAG = "FlowLog"

const val INSTAGRAM_PACKAGE = "com.instagram.android"

// How long (ms) the user must stay outside Instagram before the session is declared ended.
// Configurable here rather than hard-coded in the service — see spec §1 ("configurable, default 30s").
const val SESSION_END_THRESHOLD_MS = 30_000L

/**
 * State machine for Instagram session lifecycle.
 *
 * States:
 *   IDLE  →  onInstagramForeground()  →  ACTIVE
 *   ACTIVE →  onInstagramBackground()  →  COUNTDOWN (30s timer running)
 *   COUNTDOWN → onInstagramForeground() → ACTIVE     (user came back; cancel timer)
 *   COUNTDOWN → timer fires            → IDLE        (session finalized)
 *   ACTIVE / COUNTDOWN → onScreenOff() → IDLE        (immediate finalize)
 *
 * All DB writes go through PrivacyLayer — this class never touches the DAOs directly.
 */
class SessionManager(
    private val db: AppDatabase,
    private val privacyLayer: PrivacyLayer,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val onSessionFinalized: (() -> Unit)? = null,
) {
    // UUID of the in-progress session, or null when idle.
    // @Volatile ensures writes on the IO thread (finalizeSession) are visible on the main thread.
    @Volatile
    var activeSessionId: String? = null
        private set

    @Volatile
    private var sessionStartInstant: Instant? = null

    // Coroutine job for the end-threshold countdown; cancelled if the user returns to Instagram.
    @Volatile
    private var endJob: Job? = null

    /** Instagram entered foreground. */
    fun onInstagramForeground() {
        endJob?.cancel()  // user returned before the 30 s threshold — stay in the same session
        endJob = null

        if (activeSessionId != null) {
            Log.d(TAG, "Instagram foregrounded but session already active ($activeSessionId)")
            return
        }

        val sessionId = UUID.randomUUID().toString()
        activeSessionId = sessionId
        sessionStartInstant = Instant.now()
        Log.i(TAG, "Session started: $sessionId")

        scope.launch {
            db.sessionDao().insert(
                SessionEntity(
                    sessionId    = sessionId,
                    startedAtUtc = DateTimeFormatter.ISO_INSTANT.format(sessionStartInstant),
                    endedAtUtc   = null,
                    durationMs   = null,
                )
            )
            privacyLayer.writeEvent(
                eventType     = EventType.SESSION_START,
                sessionId     = sessionId,
                sourcePackage = INSTAGRAM_PACKAGE,
            )
        }
    }

    /** Instagram moved to background. Start the end-threshold countdown. */
    fun onInstagramBackground() {
        val sessionId = activeSessionId ?: return
        Log.d(TAG, "Session $sessionId backgrounded — finalizing in ${SESSION_END_THRESHOLD_MS}ms if user doesn't return")
        endJob = scope.launch {
            delay(SESSION_END_THRESHOLD_MS)
            finalizeSession(sessionId)
        }
    }

    /** Screen locked — end the session immediately without waiting for the threshold. */
    fun onScreenOff() {
        endJob?.cancel()
        val sessionId = activeSessionId ?: return
        scope.launch { finalizeSession(sessionId) }
    }

    /** Finalize the current session and suspend until done — used by stop+export. */
    suspend fun forceFinalize() {
        endJob?.cancel()
        endJob = null
        val sessionId = activeSessionId ?: return
        finalizeSession(sessionId)
    }

    private suspend fun finalizeSession(sessionId: String) {
        if (activeSessionId != sessionId) {
            Log.d(TAG, "finalizeSession called for $sessionId but activeSessionId=$activeSessionId — skipping")
            return
        }
        activeSessionId = null
        onSessionFinalized?.invoke()

        val startInstant = sessionStartInstant ?: return
        sessionStartInstant = null
        val endInstant   = Instant.now()
        val durationMs   = Duration.between(startInstant, endInstant).toMillis()
        val endedAtUtc   = DateTimeFormatter.ISO_INSTANT.format(endInstant)

        val session = db.sessionDao().getSessionById(sessionId) ?: return
        db.sessionDao().update(session.copy(endedAtUtc = endedAtUtc, durationMs = durationMs))

        Log.i(TAG, "Session ended: $sessionId durationMs=$durationMs")
        privacyLayer.writeEvent(
            eventType     = EventType.SESSION_END,
            sessionId     = sessionId,
            sourcePackage = INSTAGRAM_PACKAGE,
        )
    }
}
