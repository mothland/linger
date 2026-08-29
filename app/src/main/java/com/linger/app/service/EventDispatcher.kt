package com.linger.app.service

import com.linger.app.data.entity.EventType
import com.linger.app.privacy.PrivacyLayer
import com.linger.app.session.INSTAGRAM_PACKAGE
import com.linger.app.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Translates raw accessibility events into structured writeEvent() calls.
 * Sits between LingerAccessibilityService (raw Android events) and PrivacyLayer (DB writes).
 * Never touches the DB directly.
 */
class EventDispatcher(
    private val privacyLayer: PrivacyLayer,
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {

    fun onScroll(
        sourcePackage: String,
        direction: String,
        delta: Int?,
        screenContext: String,
        postViewId: String? = null,
        rawEvent: Map<String, Any?> = emptyMap(),
    ) {
        scope.launch {
            privacyLayer.writeEvent(
                eventType     = EventType.SCROLL,
                sessionId     = sessionManager.activeSessionId,
                sourcePackage = sourcePackage,
                postViewId    = postViewId,
                rawPayload    = rawEvent + mapOf(
                    "direction"     to direction,
                    "delta"         to delta,
                    "screenContext" to screenContext,
                ),
            )
        }
    }

    fun onTap(
        sourcePackage: String,
        elementType: String,
        screenContext: String,
        postViewId: String? = null,
        rawEvent: Map<String, Any?> = emptyMap(),
    ) {
        scope.launch {
            privacyLayer.writeEvent(
                eventType     = EventType.TAP,
                sessionId     = sessionManager.activeSessionId,
                sourcePackage = sourcePackage,
                postViewId    = postViewId,
                rawPayload    = rawEvent + mapOf(
                    "elementType"   to elementType,
                    "screenContext" to screenContext,
                ),
            )
        }
    }

    fun onPause(sourcePackage: String, durationMs: Long, screenContext: String, postViewId: String? = null) {
        scope.launch {
            privacyLayer.writeEvent(
                eventType     = EventType.PAUSE,
                sessionId     = sessionManager.activeSessionId,
                sourcePackage = sourcePackage,
                postViewId    = postViewId,
                rawPayload    = mapOf("durationMs" to durationMs, "screenContext" to screenContext),
            )
        }
    }

    fun onAppSwitch() {
        scope.launch {
            privacyLayer.writeEvent(
                eventType     = EventType.APP_SWITCH,
                sessionId     = sessionManager.activeSessionId,
                sourcePackage = INSTAGRAM_PACKAGE,
            )
        }
    }

    fun onPostInteraction(sourcePackage: String, action: String, screenContext: String, postViewId: String? = null) {
        scope.launch {
            privacyLayer.writeEvent(
                eventType     = EventType.POST_INTERACTION,
                sessionId     = sessionManager.activeSessionId,
                sourcePackage = sourcePackage,
                postViewId    = postViewId,
                rawPayload    = mapOf(
                    "action"        to action,
                    "screenContext" to screenContext,
                ),
            )
        }
    }

    fun onNotificationReceived(fromInstagram: Boolean) {
        val sessionId = sessionManager.activeSessionId ?: return
        scope.launch {
            privacyLayer.writeEvent(
                eventType     = EventType.NOTIFICATION_RECEIVED,
                sessionId     = sessionId,
                sourcePackage = "android",
                rawPayload    = mapOf("fromInstagram" to fromInstagram),
            )
        }
    }

    fun onScreenOn() {
        if (sessionManager.activeSessionId == null) return
        scope.launch {
            privacyLayer.writeEvent(
                eventType     = EventType.SCREEN_ON,
                sessionId     = null,
                sourcePackage = "android",
            )
        }
    }

    fun onScreenOff() {
        val hadSession = sessionManager.activeSessionId != null
        sessionManager.onScreenOff()   // ends any active session immediately
        if (!hadSession) return
        scope.launch {
            privacyLayer.writeEvent(
                eventType     = EventType.SCREEN_OFF,
                sessionId     = null,
                sourcePackage = "android",
            )
        }
    }
}
