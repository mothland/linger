package com.irin.flowlog

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-process shared state between the AccessibilityService and the UI.
 * Lives as long as the app process — no persistence across process restarts.
 * For cross-restart state (last event time, crash detection) use SharedPreferences.
 */
object ServiceStats {
    val isRunning       = MutableStateFlow(false)
    val lastEventAtMs   = MutableStateFlow(0L)        // epoch ms; 0 = no events yet this process
    val activeSessionId = MutableStateFlow<String?>(null)
}
