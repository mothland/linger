package com.linger.app.session

import com.linger.app.data.AppDatabase
import com.linger.app.data.entity.SessionEntity
import com.linger.app.privacy.PrivacyLayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for the session lifecycle state machine.
 *
 * Uses Mockito stubs for AppDatabase and PrivacyLayer so we don't need Android or Room.
 * Uses TestScope + StandardTestDispatcher so we can fast-forward virtual time to test
 * the 30-second end-threshold without actually waiting 30 seconds.
 *
 * Android-ism: coroutines.test lets you control the clock in tests. advanceTimeBy(ms) moves
 * the virtual clock forward by that many milliseconds, triggering any delayed coroutines
 * that were scheduled to run in that window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope      = TestScope(dispatcher)

    private lateinit var db: AppDatabase
    private lateinit var privacy: PrivacyLayer
    private lateinit var manager: SessionManager
    private var sessionEndedCount = 0

    @Before
    fun setup() {
        db      = mock()
        privacy = mock()
        sessionEndedCount = 0

        val mockSessionDao = mock<com.linger.app.data.dao.SessionDao>()
        whenever(db.sessionDao()).thenReturn(mockSessionDao)
        runBlocking {
            whenever(mockSessionDao.getSessionById(any())).thenReturn(
                SessionEntity("id", "2026-01-01T00:00:00Z", null, null)
            )
        }

        manager = SessionManager(db, privacy, scope) { sessionEndedCount++ }
    }

    @Test
    fun `foreground starts a session`() = scope.runTest {
        assertNull(manager.activeSessionId)
        manager.onInstagramForeground()
        assertNotNull(manager.activeSessionId)
    }

    @Test
    fun `second foreground call while active is a no-op`() = scope.runTest {
        manager.onInstagramForeground()
        val firstId = manager.activeSessionId

        manager.onInstagramForeground()  // should be ignored
        assertEquals(firstId, manager.activeSessionId)
    }

    @Test
    fun `background then foreground within threshold keeps session alive`() = scope.runTest {
        manager.onInstagramForeground()
        val sessionId = manager.activeSessionId

        manager.onInstagramBackground()
        advanceTimeBy(SESSION_END_THRESHOLD_MS - 1_000)  // just before threshold

        manager.onInstagramForeground()                  // user came back
        assertEquals("Session should still be active", sessionId, manager.activeSessionId)
    }

    @Test
    fun `background past threshold ends session`() = scope.runTest {
        manager.onInstagramForeground()

        manager.onInstagramBackground()
        advanceTimeBy(SESSION_END_THRESHOLD_MS + 1_000)  // past threshold

        assertNull("Session should be ended after threshold", manager.activeSessionId)
    }

    @Test
    fun `screen off ends session immediately`() = scope.runTest {
        manager.onInstagramForeground()
        assertNotNull(manager.activeSessionId)

        manager.onScreenOff()
        runCurrent()  // flush the coroutine launched by onScreenOff()

        assertNull("Screen off must end session immediately", manager.activeSessionId)
    }

    @Test
    fun `callback is invoked after session ends`() = scope.runTest {
        manager.onInstagramForeground()
        manager.onInstagramBackground()
        advanceTimeBy(SESSION_END_THRESHOLD_MS + 1_000)

        assertEquals(1, sessionEndedCount)
    }

    @Test
    fun `callback is NOT invoked if user returns before threshold`() = scope.runTest {
        manager.onInstagramForeground()
        manager.onInstagramBackground()
        advanceTimeBy(SESSION_END_THRESHOLD_MS - 5_000)
        manager.onInstagramForeground()                  // came back
        advanceTimeBy(SESSION_END_THRESHOLD_MS + 5_000)  // well past original threshold

        assertEquals(0, sessionEndedCount)
    }
}
