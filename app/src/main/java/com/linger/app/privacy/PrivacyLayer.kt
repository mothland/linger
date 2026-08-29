package com.irin.flowlog.privacy

import android.util.Log
import com.google.gson.Gson
import com.irin.flowlog.ServiceStats
import com.irin.flowlog.data.AppDatabase
import com.irin.flowlog.data.entity.EventEntity
import com.irin.flowlog.data.entity.EventType
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val TAG = "FlowLog"

/**
 * The single write chokepoint for all event data. No other module may write to the DB directly.
 * See spec §4.2 and §6.
 *
 * How it works:
 *   1. Caller passes a raw payload map (key → value).
 *   2. redact() strips every key on the blacklist — everything else reaches the DB.
 *   3. The cleaned record is written to the DB.
 *
 * To block a new field, add it to PAYLOAD_BLACKLIST.
 * Fields not listed pass through — capture by default, redact when confirmed as PII.
 */
class PrivacyLayer(private val db: AppDatabase) {

    private val gson = Gson()

    companion object {
        // Keys that must never reach the DB regardless of event type — add here when a field
        // turns out to carry PII after inspecting real exports.
        val PAYLOAD_BLACKLIST: Set<String> = setOf(
            "text",               // on-screen text — can contain post captions, DM content
            "contentDescription", // accessibility labels — may echo post or profile text
            "beforeText",         // previous text field value — PII in search / DM flows
        )

        // Self-report events still use an explicit allowlist — the form fields are well-known
        // and any extra keys would be a bug, not useful signal.
        val SELF_REPORT_ALLOWLIST: Set<String> = setOf(
            "flowItem1", "flowItem2", "flowItem3", "flowItem4", "flowItem5",
            "valence", "timeDistortion", "freeText", "completedAtUtc",
            "response",      // ESM quick-tap response value
            "esmPeriodId",   // links SELF_REPORT_SHOWN → SUBMITTED and the events in between
            "questionType",  // distinguishes liking_esm_val from affective_salience_esm_val
        )
    }

    /**
     * Drops blacklisted keys (and, for self-report events, keys outside the allowlist).
     * Exposed as internal so unit tests can verify redaction without hitting the DB.
     */
    internal fun redact(eventType: EventType, rawPayload: Map<String, Any?>): Map<String, Any?> {
        if (eventType == EventType.SELF_REPORT_SUBMITTED) {
            return rawPayload.filterKeys { it in SELF_REPORT_ALLOWLIST }
        }
        return rawPayload.filterKeys { it !in PAYLOAD_BLACKLIST }
    }

    /**
     * Write a single event. All payload fields not on the whitelist are silently dropped.
     *
     * @param eventType     What happened.
     * @param sessionId     Active session UUID, or null for SCREEN_ON/OFF.
     * @param sourcePackage The app package the event originates from.
     * @param postViewId    Synthetic UUID grouping events on the same post/reel; null outside a post context.
     * @param rawPayload    Arbitrary key→value pairs; only whitelisted keys survive.
     */
    suspend fun writeEvent(
        eventType: EventType,
        sessionId: String?,
        sourcePackage: String,
        postViewId: String? = null,
        rawPayload: Map<String, Any?> = emptyMap(),
    ) {
        val cleanPayload = redact(eventType, rawPayload)
        Log.d(TAG, "writeEvent type=$eventType sessionId=$sessionId postViewId=$postViewId payload=$cleanPayload")
        val entity = EventEntity(
            eventId       = UUID.randomUUID().toString(),
            sessionId     = sessionId,
            timestampUtc  = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            timestampMono = System.nanoTime(),
            eventType     = eventType.name,
            sourcePackage = sourcePackage,
            payload       = gson.toJson(cleanPayload),
            postViewId    = postViewId,
        )
        db.eventDao().insert(entity)
        ServiceStats.lastEventAtMs.value = System.currentTimeMillis()
    }
}
