package com.irin.flowlog.export

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonWriter
import com.irin.flowlog.BuildConfig
import com.irin.flowlog.data.AppDatabase
import com.irin.flowlog.data.entity.EventEntity
import com.irin.flowlog.data.entity.EventType
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
    private val epochAssembler = EpochAssembler(db)

    suspend fun export(context: Context): File {
        val outFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "flowlog_export_${System.currentTimeMillis()}.json",
        )

        // Stream the document straight to disk instead of materializing the whole export as one
        // in-memory object graph + one giant JSON string — with many sessions that doubled the
        // memory footprint of the entire dataset and reliably OOM'd (JsonExporter.export crash).
        JsonWriter(OutputStreamWriter(outFile.outputStream(), StandardCharsets.UTF_8)).use { writer ->
            writer.setIndent("  ")
            writer.beginObject()
            writer.name("exportVersion").value("0.2")
            writer.name("deviceIdHash").value(deviceIdHash(context))
            writer.name("exportedAtUtc").value(DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            writer.name("appVersion").value(BuildConfig.VERSION_NAME)

            writer.name("sessions").beginArray()
            for (session in db.sessionDao().getAllSessions()) {
                val events = db.eventDao().getEventsForSession(session.sessionId)
                val epochs = epochAssembler.assemble(events).map { ep ->
                    EsmEpochDto(
                        esmPeriodId               = ep.esmPeriodId,
                        likingResponse            = ep.likingResponse,
                        affectiveSalienceResponse = ep.affectiveSalienceResponse,
                        events                    = ep.events.map(::toEventMap),
                    )
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

                val dto = SessionDto(
                    sessionId    = session.sessionId,
                    startedAtUtc = session.startedAtUtc,
                    endedAtUtc   = endedAtUtc,
                    durationMs   = durationMs,
                    liveAtExport = session.endedAtUtc == null,
                    epochs       = epochs,
                )
                gson.toJson(dto, SessionDto::class.java, writer)
            }
            writer.endArray()

            writer.name("unattachedEvents").beginArray()
            // Exclude SELF_REPORT_SUBMITTED from unattached — responses are embedded in epoch metadata.
            for (event in db.eventDao().getUnattachedEvents()) {
                if (event.eventType == EventType.SELF_REPORT_SUBMITTED.name) continue
                gson.toJson(toEventMap(event), mapType, writer)
            }
            writer.endArray()

            writer.endObject()
        }
        return outFile
    }

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
}

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
