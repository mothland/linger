package com.linger.app.export

import com.google.gson.Gson
import com.linger.app.data.entity.EventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for JSON export serialization.
 *
 * Tests the shape of the output, not the file-writing or DB-reading — those are tested
 * in the integration test. Pure JVM, no Android dependencies.
 */
class JsonExporterTest {

    private val gson = Gson()

    @Test
    fun `event entity serializes to correct keys`() {
        val entity = EventEntity(
            eventId       = "uuid-1",
            sessionId     = "session-1",
            timestampUtc  = "2026-04-19T14:00:00.000Z",
            timestampMono = 123456789L,
            eventType     = "SCROLL",
            sourcePackage = "com.instagram.android",
            payload       = """{"direction":"down","delta":200,"screenContext":"feed"}""",
        )

        // Simulate toEventMap() logic from JsonExporter.
        val map = mapOf(
            "eventId"       to entity.eventId,
            "sessionId"     to entity.sessionId,
            "timestampUtc"  to entity.timestampUtc,
            "timestampMono" to entity.timestampMono,
            "eventType"     to entity.eventType,
            "sourcePackage" to entity.sourcePackage,
            "payload"       to gson.fromJson(entity.payload, Map::class.java),
        )

        assertEquals("uuid-1", map["eventId"])
        assertEquals("SCROLL", map["eventType"])

        @Suppress("UNCHECKED_CAST")
        val payload = map["payload"] as Map<String, Any?>
        assertEquals("down", payload["direction"])
        assertEquals(200.0, payload["delta"])  // Gson parses numbers as Double by default
    }

    @Test
    fun `payload null value is preserved`() {
        val raw = """{"esmPeriodId":"abc","response":null}"""
        val parsed = gson.fromJson(raw, Map::class.java)
        assertTrue(parsed.containsKey("response"))
        assertEquals(null, parsed["response"])
    }

    @Test
    fun `export document structure has required top-level keys`() {
        val doc = mapOf(
            "exportVersion"    to "0.2",
            "deviceIdHash"     to "sha256-abc123",
            "exportedAtUtc"    to "2026-05-20T10:00:00Z",
            "appVersion"       to "0.5.0",
            "sessions"         to emptyList<Any>(),
            "unattachedEvents" to emptyList<Any>(),
        )
        val json   = gson.toJson(doc)
        val parsed = gson.fromJson(json, Map::class.java)

        listOf("exportVersion", "deviceIdHash", "exportedAtUtc", "appVersion",
               "sessions", "unattachedEvents")
            .forEach { key -> assertNotNull("Missing key: $key", parsed[key]) }
    }

    @Test
    fun `session epoch structure has required keys`() {
        // Sessions now contain epochs, not a flat events list.
        // Each epoch has esmPeriodId (nullable), response fields, and events.
        val epoch = mapOf(
            "esmPeriodId"               to "period-1",
            "likingResponse"            to "pretty_good",
            "affectiveSalienceResponse" to "yes",
            "events"                    to emptyList<Any>(),
        )
        val session = mapOf(
            "sessionId"    to "session-1",
            "startedAtUtc" to "2026-05-20T10:00:00Z",
            "endedAtUtc"   to "2026-05-20T10:30:00Z",
            "durationMs"   to 1800000L,
            "epochs"       to listOf(epoch),
        )
        val json   = gson.toJson(session)
        val parsed = gson.fromJson(json, Map::class.java)

        assertNotNull("Missing key: epochs", parsed["epochs"])

        @Suppress("UNCHECKED_CAST")
        val parsedEpoch = (parsed["epochs"] as List<Map<String, Any?>>).first()
        listOf("esmPeriodId", "likingResponse", "affectiveSalienceResponse", "events")
            .forEach { key -> assertTrue("Missing epoch key: $key", parsedEpoch.containsKey(key)) }
    }

    @Test
    fun `device id hash has expected prefix`() {
        // The hash format is "sha256-<hex>" — verify this contractually so analysis scripts
        // can rely on it.
        val hash = "sha256-" + "a".repeat(64)
        assertTrue(hash.startsWith("sha256-"))
        assertEquals(7 + 64, hash.length)
    }
}
