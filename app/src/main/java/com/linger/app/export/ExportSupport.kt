package com.irin.flowlog.export

import android.content.Context
import android.provider.Settings
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.irin.flowlog.data.AppDatabase
import com.irin.flowlog.data.entity.EventEntity
import com.irin.flowlog.data.entity.EventType
import java.security.MessageDigest
import java.util.UUID

// Used only to deserialize stored payload JSON back into a Map. LONG_OR_DOUBLE keeps integer
// fields (durationMs, delta, etc.) as Long instead of Double, which Gson's default policy
// assigns to every JSON number regardless of whether it has a decimal point.
private val payloadGson = GsonBuilder()
    .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
    .create()

internal fun EventEntity.payloadMap(): Map<*, *> =
    payloadGson.fromJson(payload, Map::class.java) ?: emptyMap<String, Any>()

internal fun deviceIdHash(context: Context): String {
    val androidId   = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    val installUuid = getOrCreateInstallUuid(context)
    val raw         = "$androidId:$installUuid"
    val bytes       = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
    return "sha256-" + bytes.joinToString("") { "%02x".format(it) }
}

private fun getOrCreateInstallUuid(context: Context): String {
    val prefs = context.getSharedPreferences("flowlog_prefs", Context.MODE_PRIVATE)
    return prefs.getString("install_uuid", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("install_uuid", it).apply()
    }
}

// One slice of a session's events, bounded by consecutive ESM pings.
// esmPeriodId/pingShownAtUtc/responses are null for the unlabelled tail epoch (events after the
// last ping, or all events if no ping fired during the session).
internal data class SessionEpoch(
    val esmPeriodId: String?,
    val epochIndex: Int,
    val pingShownAtUtc: String?,
    val likingResponse: String?,
    val affectiveSalienceResponse: String?,
    val events: List<EventEntity>,
)

// Shared by JsonExporter and CsvExporter so both formats attach ESM labels to events identically.
internal class EpochAssembler(private val db: AppDatabase) {

    suspend fun assemble(events: List<EventEntity>): List<SessionEpoch> {
        val periodIds = events
            .filter { it.eventType == EventType.SELF_REPORT_SHOWN.name }
            .mapNotNull { it.payloadMap()["esmPeriodId"] as? String }
            .distinct()

        if (periodIds.isEmpty()) {
            return listOf(SessionEpoch(null, 0, null, null, null, events))
        }

        val submitted = db.eventDao().getSubmittedEventsForPeriods(periodIds)

        // Collect whatever responses arrived for each period (0, 1, or 2 — all are valid).
        val responsesByPeriod: Map<String, Pair<String?, String?>> = submitted
            .groupBy { it.payloadMap()["esmPeriodId"] as? String }
            .filterKeys { it != null }
            .mapKeys  { it.key!! }
            .mapValues { (_, subs) ->
                Pair(
                    subs.firstOrNull { it.payloadMap()["questionType"] == "liking_esm_val" }
                        ?.payloadMap()?.get("response") as? String,
                    subs.firstOrNull { it.payloadMap()["questionType"] == "affective_salience_esm_val" }
                        ?.payloadMap()?.get("response") as? String,
                )
            }

        // Epoch boundary = when the ping appeared (min SHOWN timestamp for that period).
        // Using ping time rather than submission time means every ping creates an epoch even if
        // 0 or 1 questions were answered — responses are attached by periodId, not by timing.
        data class PingBoundary(
            val periodId: String,
            val pingTimeUtc: String,
            val likingResponse: String?,
            val affectiveResponse: String?,
        )

        val boundaries = events
            .filter { it.eventType == EventType.SELF_REPORT_SHOWN.name }
            .groupBy { it.payloadMap()["esmPeriodId"] as? String }
            .mapNotNull { (periodId, shownEvents) ->
                if (periodId == null) return@mapNotNull null
                val (liking, affective) = responsesByPeriod[periodId] ?: Pair(null, null)
                PingBoundary(
                    periodId          = periodId,
                    pingTimeUtc       = shownEvents.minOf { it.timestampUtc },
                    likingResponse    = liking,
                    affectiveResponse = affective,
                )
            }
            .sortedBy { it.pingTimeUtc }

        val epochs = mutableListOf<SessionEpoch>()
        var openTime: String? = null  // null = start of session
        var index = 0

        for (boundary in boundaries) {
            val epochEvents = events.filter { event ->
                val afterOpen = if (openTime == null) true else event.timestampUtc > openTime
                afterOpen && event.timestampUtc <= boundary.pingTimeUtc
            }
            epochs += SessionEpoch(
                esmPeriodId               = boundary.periodId,
                epochIndex                = index++,
                pingShownAtUtc            = boundary.pingTimeUtc,
                likingResponse            = boundary.likingResponse,
                affectiveSalienceResponse = boundary.affectiveResponse,
                events                    = epochEvents,
            )
            openTime = boundary.pingTimeUtc
        }

        // Tail: events after the last ping (or all events if no ping fired).
        val tailEvents = events.filter { event ->
            if (openTime == null) true else event.timestampUtc > openTime
        }
        if (tailEvents.isNotEmpty()) {
            epochs += SessionEpoch(null, index, null, null, null, tailEvents)
        }

        return epochs
    }
}
