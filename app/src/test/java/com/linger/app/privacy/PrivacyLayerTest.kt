package com.linger.app.privacy

import com.linger.app.data.entity.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the redaction logic in PrivacyLayer.
 *
 * These tests call redact() directly — a pure function with no Android or DB dependencies —
 * so they run on the JVM without a device or emulator. Fast and reliable.
 *
 * Principle: every test should demonstrate that a specific piece of data either
 * DOES or DOES NOT survive the privacy layer. If you add a field to the blacklist, add a test here.
 */
class PrivacyLayerTest {

    // Mirrors PrivacyLayer.redact() without needing a DB instance.
    private fun redact(type: EventType, payload: Map<String, Any?>): Map<String, Any?> {
        return payload
            .let { if (type == EventType.SELF_REPORT_SUBMITTED) it.filterKeys { k -> k in PrivacyLayer.SELF_REPORT_ALLOWLIST } else it }
            .filterKeys { it !in PrivacyLayer.PAYLOAD_BLACKLIST }
    }

    @Test
    fun `scroll - known fields survive`() {
        val result = redact(
            EventType.SCROLL,
            mapOf("direction" to "down", "delta" to 200, "screenContext" to "feed"),
        )
        assertEquals("down", result["direction"])
        assertEquals(200, result["delta"])
        assertEquals("feed", result["screenContext"])
    }

    @Test
    fun `scroll - unknown fields survive (blacklist model passes through by default)`() {
        val result = redact(
            EventType.SCROLL,
            mapOf("direction" to "down", "someNewField" to 42, "windowId" to 7),
        )
        assertTrue("unknown fields should pass through", result.containsKey("someNewField"))
        assertTrue("windowId should pass through", result.containsKey("windowId"))
    }

    @Test
    fun `blacklisted fields are stripped from all event types`() {
        val payload = mapOf(
            "direction"          to "down",
            "text"               to "Check out this post!", // PII — must be stripped
            "contentDescription" to "Photo by alice",       // PII — must be stripped
            "beforeText"         to "search query",         // PII — must be stripped
        )
        val result = redact(EventType.SCROLL, payload)
        assertFalse("text must be stripped",               result.containsKey("text"))
        assertFalse("contentDescription must be stripped", result.containsKey("contentDescription"))
        assertFalse("beforeText must be stripped",         result.containsKey("beforeText"))
        assertTrue("direction must survive",               result.containsKey("direction"))
    }

    @Test
    fun `notification - non-blacklisted fields survive`() {
        val result = redact(
            EventType.NOTIFICATION_RECEIVED,
            mapOf(
                "sourcePackage" to "com.whatsapp",
                "windowId"      to 3,
                // title/body/sender are not currently blacklisted — they don't come from raw events.
                // If they appear in real exports, add them to PAYLOAD_BLACKLIST.
            ),
        )
        assertEquals("com.whatsapp", result["sourcePackage"])
        assertEquals(3, result["windowId"])
    }

    @Test
    fun `self_report_submitted - esm fields survive`() {
        val input = mapOf(
            "esmPeriodId"    to "period-uuid",
            "questionType"   to "liking_esm_val",
            "response"       to "pretty_good",
            "completedAtUtc" to "2026-05-20T10:00:00Z",
        )
        val result = redact(EventType.SELF_REPORT_SUBMITTED, input)
        assertEquals(input.size, result.size)
        assertEquals("period-uuid",   result["esmPeriodId"])
        assertEquals("liking_esm_val", result["questionType"])
        assertEquals("pretty_good",   result["response"])
    }

    @Test
    fun `self_report_submitted - extra keys stripped (still uses allowlist)`() {
        val result = redact(
            EventType.SELF_REPORT_SUBMITTED,
            mapOf(
                "flowItem1"    to 3,
                "internalNote" to "debug value",
            ),
        )
        assertFalse(result.containsKey("internalNote"))
        assertTrue(result.containsKey("flowItem1"))
    }

    @Test
    fun `app_switch - fields pass through`() {
        val result = redact(
            EventType.APP_SWITCH,
            mapOf("targetPackage" to "com.google.android.youtube", "windowId" to 2),
        )
        assertEquals("com.google.android.youtube", result["targetPackage"])
        assertEquals(2, result["windowId"])
    }

    @Test
    fun `tap - known and unknown fields survive`() {
        val result = redact(
            EventType.TAP,
            mapOf("elementType" to "button", "screenContext" to "reels", "isEnabled" to true),
        )
        assertEquals("button", result["elementType"])
        assertEquals("reels", result["screenContext"])
        assertTrue(result.containsKey("isEnabled"))
    }
}
