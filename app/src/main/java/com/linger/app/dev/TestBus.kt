package com.irin.flowlog.dev

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

object TestBus {
    /** UI → service: which action to execute. */
    val triggerAction = MutableSharedFlow<TestAction>(extraBufferCapacity = 1)

    /** service → service: any POST_INTERACTION detected (feeds the result check). */
    val postInteractionSignal = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** service → UI: final pass/fail outcome. Null while idle. */
    val testResult = MutableStateFlow<TestResult?>(null)
}

sealed class TestResult {
    data class Pass(val detected: String) : TestResult()
    object Fail : TestResult()
}
