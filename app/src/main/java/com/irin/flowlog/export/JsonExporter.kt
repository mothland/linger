package com.irin.flowlog.export

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.irin.flowlog.BuildConfig
import com.irin.flowlog.data.AppDatabase
import com.irin.flowlog.data.entity.EventEntity
import com.irin.flowlog.data.entity.EventType
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Assembles all data from Room and writes a single JSON file.
 *
 * Export structure: each session is broken into ESM epochs — slices of behavioral events ending
 * at each ESM ping. The epoch boundary is when the ping appeared (SELF_REPORT_SHOWN timestamp),
 * not the submission time. Every ping creates an epoch regardless of whether 0, 1, or 2 questions
 * were answered; null response fields indicate unanswered questions.
 * Events after the last ping become an unlabelled tail epoch (null esmPeriodId, null responses).
 *
 * Call export() from a coroutine — it performs DB reads on the calling thread.
 */
class JsonExporter(private val db: AppDatabase) {

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    // Used only to deserialize stored payload JSON back into a Map. LONG_OR_DOUBLE keeps integer
    // fields (durationMs, delta, etc.) as Long instead of Double, which Gson's default policy
    // assigns to every JSON number regardless of whether it has a decimal point.
    private val payloadGson = GsonBuilder()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .create()

    suspend fun export(context: Context): File {
        val sessions   = db.sessionDao().getAllSessions()
        // Exclude SELF_REPORT_SUBMITTED from unattached — responses are embedded in epoch metadata.
        val unattached = db.eventDao().getUnattachedEvents()
            .filter { it.eventType != EventType.SELF_REPORT_SUBMITTED.name }

        val sessionDtos = sessions.map { session ->
            val events    = db.eventDao().getEventsForSession(session.sessionId)
            val periodIds = events
                .filter { it.eventType == EventType.SELF_REPORT_SHOWN.name }
                .mapNotNull { it.payloadMap()["esmPeriodId"] as? String }
                .distinct()

            val epochs = if (periodIds.isEmpty()) {
                listOf(epochDto(null, null, null, events))
            } else {
                buildEpochs(events, periodIds)
            }

            // If the session was still live at export time, endedAtUtc and durationMs are null.
            // Fall back to the last event's timestamp so the session has a usable time range.
            val endedAtUtc: String?
            val durationMs: Long?
            if (session.endedAtUtc != null) {
                endedAtUtc = session.endedAtUtc
                durationMs = session.durationMs
            } else {
                val lastEventUtc = events.maxOfOrNull { it.timestampUtc }
                endedAtUtc = lastEventUtc
                durationMs = if (lastEventUtc != null) {
                    ChronoUnit.MILLIS.between(
                        Instant.parse(session.startedAtUtc),
                        Instant.parse(lastEventUtc),
                    )
                } else null
            }

            SessionDto(
                sessionId    = session.sessionId,
                startedAtUtc = session.startedAtUtc,
                endedAtUtc   = endedAtUtc,
                durationMs   = durationMs,
                liveAtExport = session.endedAtUtc == null,
                epochs       = epochs,
            )
        }

        val doc = ExportDocument(
            exportVersion    = "0.2",
            deviceIdHash     = deviceIdHash(context),
            exportedAtUtc    = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            appVersion       = BuildConfig.VERSION_NAME,
            sessions         = sessionDtos,
            unattachedEvents = unattached.map(::toEventMap),
        )

        val outFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "flowlog_export_${System.currentTimeMillis()}.json",
        )
        outFile.writeText(gson.toJson(doc))
        return outFile
    }

    private suspend fun buildEpochs(
        events: List<EventEntity>,
        periodIds: List<String>,
    ): List<EsmEpochDto> {
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

        val epochs = mutableListOf<EsmEpochDto>()
        var openTime: String? = null  // null = start of session

        for (boundary in boundaries) {
            val epochEvents = events.filter { event ->
                val afterOpen = if (openTime == null) true else event.timestampUtc > openTime
                afterOpen && event.timestampUtc <= boundary.pingTimeUtc
            }
            epochs += epochDto(boundary.periodId, boundary.likingResponse, boundary.affectiveResponse, epochEvents)
            openTime = boundary.pingTimeUtc
        }

        // Tail: events after the last ping (or all events if no ping fired).
        val tailEvents = events.filter { event ->
            if (openTime == null) true else event.timestampUtc > openTime
        }
        if (tailEvents.isNotEmpty()) {
            epochs += epochDto(null, null, null, tailEvents)
        }

        return epochs
    }

    private fun epochDto(
        periodId: String?,
        likingResponse: String?,
        affectiveResponse: String?,
        events: List<EventEntity>,
    ) = EsmEpochDto(
        esmPeriodId               = periodId,
        likingResponse            = likingResponse,
        affectiveSalienceResponse = affectiveResponse,
        events                    = events.map(::toEventMap),
    )

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun viewIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/json")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun EventEntity.payloadMap(): Map<*, *> =
        payloadGson.fromJson(payload, Map::class.java) ?: emptyMap<String, Any>()

    private fun toEventMap(entity: EventEntity): Map<String, Any?> = mapOf(
        "eventId"       to entity.eventId,
        "sessionId"     to entity.sessionId,
        "postViewId"    to entity.postViewId,
        "timestampUtc"  to entity.timestampUtc,
        "timestampMono" to entity.timestampMono,
        "eventType"     to entity.eventType,
        "sourcePackage" to entity.sourcePackage,
        "payload"       to entity.payloadMap(),
    )

    private fun deviceIdHash(context: Context): String {
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
}

private data class ExportDocument(
    val exportVersion: String,
    val deviceIdHash: String,
    val exportedAtUtc: String,
    val appVersion: String,
    val sessions: List<SessionDto>,
    val unattachedEvents: List<Map<String, Any?>>,
)

private data class SessionDto(
    val sessionId: String,
    val startedAtUtc: String,
    val endedAtUtc: String?,   // null only if session had zero events and was live at export
    val durationMs: Long?,     // null only if session had zero events and was live at export
    val liveAtExport: Boolean, // true = session was still open; endedAtUtc/durationMs are inferred from last event
    val epochs: List<EsmEpochDto>,
)

private data class EsmEpochDto(
    val esmPeriodId: String?,
    val likingResponse: String?,
    val affectiveSalienceResponse: String?,
    val events: List<Map<String, Any?>>,
)
