package com.linger.app.dev

sealed class TestAction(val label: String) {
    object DoubleTapCenter    : TestAction("Double-tap center")
    object TapFeedSaveButton  : TestAction("Tap feed save button")
    object TapFeedShareButton : TestAction("Tap feed share button")

    companion object {
        val all: List<TestAction> = listOf(DoubleTapCenter, TapFeedSaveButton, TapFeedShareButton)
    }
}
