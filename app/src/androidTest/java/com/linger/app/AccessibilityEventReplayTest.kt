package com.linger.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.linger.app.data.AppDatabase
import com.linger.app.data.entity.EventType
import com.linger.app.data.entity.SessionEntity
import com.linger.app.export.JsonExporter
import com.linger.app.privacy.PrivacyLayer
import com.linger.app.report.SelfReportTrigger
import com.linger.app.session.SessionManager
import com.google.gson.Gson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test: replays a scripted sequence of events and asserts that the resulting
 * DB rows and JSON export match expectations. Runs on a real device or emulator (androidTest).
 *
 * Android-ism: androidTest/ tests run on an Android device or emulator, not on the JVM.
 * They have access to real Android APIs (Room, Context, etc.). Use them for things that
 * can't be meaningfully tested without Android.
 *
 * This test uses an in-memory Room database — data is never written to disk and is discarded
 * when the test finishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AccessibilityEventReplayTest {

    private lateinit var db: AppDatabase
    private lateinit var privacy: PrivacyLayer
    private lateinit var sessionManager: SessionManager

    private val dispatcher = StandardTestDispatcher()
    private val scope      = TestScope(dispatcher)
    private val gson       = Gson()

    // Collects sessions that triggered the self-report prompt during this test.
    private val triggeredSessions = mutableListOf<String>()

    private val fakeTrigger = object : SelfReportTrigger {
        override suspend fun onSessionEnded(sessionId: String) {
            triggeredSessions.add(sessionId)
        }
    }

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        privacy        = PrivacyLayer(db)
        sessionManager = SessionManager(db, privacy, fakeTrigger, scope)
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun `scripted session produces correct DB rows and JSON export`() = scope.runTest {
        // ── Phase 1: Session starts ────────────────────────────────────────────
        sessionManager.onInstagramForeground()
        val sessionId = sessionManager.activeSessionId
        assertNotNull("Session should be active after foreground", sessionId)

        // ── Phase 2: A few events during the session ───────────────────────────
        privacy.writeEvent(
            eventType     = EventType.SCROLL,
            sessionId     = sessionId,
            sourcePackage = "com.instagram.android",
            rawPayload    = mapOf("direction" to "down", "delta" to 150, "screenContext" to "feed"),
        )
        privacy.writeEvent(
            eventType     = EventType.TAP,
            sessionId     = sessionId,
            sourcePackage = "com.instagram.android",
            rawPayload    = mapOf("elementType" to "image", "screenContext" to "feed"),
        )
        // A notification arrives mid-session — only the package name should be stored.
        privacy.writeEvent(
            eventType     = EventType.NOTIFICATION_RECEIVED,
            sessionId     = sessionId,
            sourcePackage = "com.whatsapp",
            rawPayload    = mapOf(
                "sourcePackage" to "com.whatsapp",
                "title"         to "New message from Bob",  // must be stripped
            ),
        )

        // ── Phase 3: Session ends after threshold ──────────────────────────────
        sessionManager.onInstagramBackground()
        advanceTimeBy(com.linger.app.session.SESSION_END_THRESHOLD_MS + 1_000)

        assertNull("Session should be ended after threshold", sessionManager.activeSessionId)
        assertEquals("Trigger should have fired once", 1, triggeredSessions.size)

        // ── Phase 4: Assert DB state ───────────────────────────────────────────
        val sessions = db.sessionDao().getAllSessions()
        assertEquals(1, sessions.size)
        assertNotNull(sessions[0].endedAtUtc)
        assertNotNull(sessions[0].durationMs)
        assertTrue(sessions[0].durationMs!! > 0)

        val events = db.eventDao().getEventsForSession(sessionId!!)
        // Expected: SESSION_START, SCROLL, TAP, NOTIFICATION_RECEIVED, SESSION_END
        assertEquals(5, events.size)

        val scrollEvent = events.first { it.eventType == EventType.SCROLL.name }
        val scrollPayload = gson.fromJson(scrollEvent.payload, Map::class.java)
        assertEquals("down", scrollPayload["direction"])
        assertFalse("Notification title must not be in payload", scrollPayload.containsKey("title"))

        val notifEvent = events.first { it.eventType == EventType.NOTIFICATION_RECEIVED.name }
        val notifPayload = gson.fromJson(notifEvent.payload, Map::class.java)
        assertFalse("title must be stripped from notification", notifPayload.containsKey("title"))
        assertEquals("com.whatsapp", notifPayload["sourcePackage"])

        // ── Phase 5: Assert JSON export shape ─────────────────────────────────
        val ctx      = ApplicationProvider.getApplicationContext<Context>()
        val exporter = JsonExporter(db)
        val file     = exporter.export(ctx)
        assertTrue(file.exists())
        assertTrue(file.length() > 0)

        val json   = file.readText()
        val parsed = gson.fromJson(json, Map::class.java)

        assertEquals("0.1", parsed["exportVersion"])
        assertNotNull(parsed["deviceIdHash"])

        @Suppress("UNCHECKED_CAST")
        val exportedSessions = parsed["sessions"] as List<Map<String, Any?>>
        assertEquals(1, exportedSessions.size)

        @Suppress("UNCHECKED_CAST")
        val exportedEvents = exportedSessions[0]["events"] as List<Map<String, Any?>>
        assertEquals(5, exportedEvents.size)
    }
}
