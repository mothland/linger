package com.linger.app.export

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.linger.app.data.AppDatabase
import com.linger.app.data.entity.EventType
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Flattens all data from Room into one denormalized CSV — one row per event, with session and
 * ESM epoch/label context repeated on every row (same join the JSON export nests, done via
 * EpochAssembler so both formats attach labels identically).
 *
 * Built for stacking across participants: each export is self-contained (no join keys an analyst
 * has to get right by hand across N files), and deviceIdHash is the first column so participants
 * stay distinguishable after concatenating everyone's CSVs together.
 *
 * Call export() from a coroutine — it performs DB reads on the calling thread.
 */
class CsvExporter(private val db: AppDatabase) {

    private val epochAssembler = EpochAssembler(db)

    private val columns = listOf(
        "deviceIdHash", "sessionId", "sessionStartedAtUtc",
        "esmPeriodId", "epochIndex", "pingShownAtUtc", "likingResponse", "affectiveSalienceResponse",
        "eventId", "timestampUtc", "timestampMono", "eventType", "sourcePackage", "postViewId", "payloadJson",
    )

    suspend fun export(context: Context): File {
        val outFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "linger_export_${System.currentTimeMillis()}.csv",
        )
        val deviceId = deviceIdHash(context)

        // Stream row-by-row rather than building the CSV as one in-memory string — same reason
        // the JSON exporter streams: with many sessions the whole-dataset string reliably OOM'd.
        BufferedWriter(OutputStreamWriter(outFile.outputStream(), StandardCharsets.UTF_8)).use { writer ->
            writer.appendLine(columns.joinToString(",") { csvField(it) })

            for (session in db.sessionDao().getAllSessions()) {
                val events = db.eventDao().getEventsForSession(session.sessionId)
                for (epoch in epochAssembler.assemble(events)) {
                    for (event in epoch.events) {
                        writer.appendLine(
                            csvRow(
                                deviceId, session.sessionId, session.startedAtUtc,
                                epoch.esmPeriodId, epoch.epochIndex.toString(), epoch.pingShownAtUtc,
                                epoch.likingResponse, epoch.affectiveSalienceResponse,
                                event.eventId, event.timestampUtc, event.timestampMono.toString(),
                                event.eventType, event.sourcePackage, event.postViewId, event.payload,
                            )
                        )
                    }
                }
            }

            // Events with no sessionId (SCREEN_ON/OFF, NOTIFICATION_RECEIVED) — no session/epoch context.
            // Exclude SELF_REPORT_SUBMITTED for consistency with the JSON export — responses are
            // embedded in epoch metadata above, not emitted as standalone rows.
            for (event in db.eventDao().getUnattachedEvents()) {
                if (event.eventType == EventType.SELF_REPORT_SUBMITTED.name) continue
                writer.appendLine(
                    csvRow(
                        deviceId, null, null,
                        null, null, null, null, null,
                        event.eventId, event.timestampUtc, event.timestampMono.toString(),
                        event.eventType, event.sourcePackage, event.postViewId, event.payload,
                    )
                )
            }
        }
        return outFile
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun viewIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/csv")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun csvRow(vararg values: String?): String =
        values.joinToString(",") { csvField(it) }

    // RFC 4180: quote any field containing a comma, quote, or newline; escape quotes by doubling.
    private fun csvField(value: String?): String {
        val s = value ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${s.replace("\"", "\"\"")}\""
        } else {
            s
        }
    }
}
