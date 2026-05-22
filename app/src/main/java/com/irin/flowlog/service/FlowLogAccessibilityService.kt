package com.irin.flowlog.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.irin.flowlog.DevConfig
import com.irin.flowlog.FlowLogApplication
import com.irin.flowlog.dev.TestAction
import com.irin.flowlog.dev.TestBus
import com.irin.flowlog.dev.TestResult
import com.irin.flowlog.R
import com.irin.flowlog.ServiceStats
import com.irin.flowlog.data.AppDatabase
import com.irin.flowlog.privacy.PrivacyLayer
import com.irin.flowlog.report.EsmTrigger
import com.irin.flowlog.session.INSTAGRAM_PACKAGE
import com.irin.flowlog.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private const val TAG        = "FlowLog"
private const val TAG_TEST   = "FlowLog_Test"
private const val TAG_TAP    = "FlowLog_Tap"
private const val TAG_SCROLL = "FlowLog_Scroll"
private const val HEARTBEAT_INTERVAL_MS = 30_000L
private const val PAUSE_THRESHOLD_MS = 1_500L
private const val REELS_PAGER_ID    = "com.instagram.android:id/clips_viewer_view_pager"

class FlowLogAccessibilityService : AccessibilityService() {

    private lateinit var db: AppDatabase
    private lateinit var sessionManager: SessionManager
    private lateinit var dispatcher: EventDispatcher
    private lateinit var trigger: EsmTrigger

    private val job       = SupervisorJob()
    private val scope     = CoroutineScope(job + Dispatchers.IO)
    private val mainScope = CoroutineScope(job + Dispatchers.Main)

    private var lastInstagramEventMonoMs: Long = 0L

    // State-flip tracker for post interaction buttons. Key = "action:nodeHash" — per node, so
    // buttons belonging to different posts (e.g. an inline reel's save_button and the centered
    // post's row_feed_button_save) don't bleed into each other's state.
    private val postInteractionTracker = mutableMapOf<String, Boolean>()
    // Short-window dedup: "postViewId:toggleBucket" → last fire time (SystemClock.elapsedRealtime ms).
    // toggleBucket normalises liked/unliked → "like", saved/unsaved → "save", etc.
    // Prevents the same logical action firing twice within TOGGLE_DEDUP_WINDOW_MS when Instagram
    // renders multiple button IDs for the same post in the same window tree simultaneously.
    private val recentToggleFires = mutableMapOf<String, Long>()
    private val TOGGLE_DEDUP_WINDOW_MS = 100L
    private var likeCheckJob: Job? = null

    // Last scroll/tap context — carried into scanForPostInteractions() which has no live event.
    private var lastScreenContext = "unknown"
    // Prevents firing comment_section_opened more than once per sheet instance.
    private var commentSectionOpen = false
    private var shareSheetOpen     = false

    // Post-view tracking — a new UUID is assigned when the post centered on screen changes.
    // Feed: event.source gives us the feed RecyclerView node; we walk its children (~4-6 nodes),
    //   find the one whose center Y is closest to screen center, and use its hashCode() as the
    //   post identity. Same View object = same post = same hash regardless of wiggles. When the
    //   user genuinely scrolls to a new post, a different child View is centered → new hash → new ID.
    //   Carousel swipes happen inside a nested ViewPager2 — the outer feed child stays the same → same hash.
    // Reels: assigned on clips_viewer_view_pager fromIndex change (completed swipe).
    // Initial ID is seeded on Instagram foreground so events before the first scroll are never null.
    private var currentPostViewId: String? = null
    private var lastReelIndex: Int = -1
    private var lastFeedItemHash: Int = -1

    // ── Broadcast receivers ───────────────────────────────────────────────────

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { resetPostTracking(); dispatcher.onScreenOff() }
                Intent.ACTION_SCREEN_ON  -> dispatcher.onScreenOn()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")

        db             = AppDatabase.getInstance(applicationContext)
        val privacy    = PrivacyLayer(db)
        trigger        = EsmTrigger(applicationContext, db)
        sessionManager = SessionManager(db, privacy, scope) {
            ServiceStats.activeSessionId.value = null
            trigger.onSessionEnded()
        }
        dispatcher     = EventDispatcher(privacy, sessionManager, scope)
        trigger.sessionManager = sessionManager
        trigger.start(scope)

        // ── Foreground notification ───────────────────────────────────────────
        val initialNotif = buildForegroundNotification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FlowLogApplication.NOTIF_FOREGROUND, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FlowLogApplication.NOTIF_FOREGROUND, initialNotif)
        }

        // ── Shared state ──────────────────────────────────────────────────────
        ServiceStats.isRunning.value = true

        val prefs = getSharedPreferences(FlowLogApplication.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(FlowLogApplication.KEY_SERVICE_RUNNING, true)
            .putLong(FlowLogApplication.KEY_LAST_HEARTBEAT_MS, System.currentTimeMillis())
            .apply()

        // ── Broadcast receivers ───────────────────────────────────────────────
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)

        // ── Accessibility event filter ────────────────────────────────────────
        serviceInfo = serviceInfo?.apply {
            eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED   or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED          or
                AccessibilityEvent.TYPE_VIEW_CLICKED           or
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            )
            feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags               = (
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS               or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS  or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            )
            notificationTimeout = 100
        }

        // ── Background coroutines ─────────────────────────────────────────────
        scope.launch { runHeartbeat() }
        scope.launch { runNotificationUpdates() }
        if (DevConfig.enabled) scope.launch { collectTestActions() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        if (DevConfig.enabled && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val node = event.source
            if (node != null) {
                try {
                    val sb = StringBuilder()
                    sb.append("desc=${node.contentDescription}")
                    sb.append(" | viewId=${node.viewIdResourceName}")
                    sb.append(" | class=${node.className}")
                    sb.append(" | selected=${node.isSelected}")
                    sb.append(" | clickable=${node.isClickable}")
                    repeat(node.childCount.coerceAtMost(5)) { i ->
                        val c = node.getChild(i) ?: return@repeat
                        sb.append("\n  child[$i]:   viewId=${c.viewIdResourceName} | class=${c.className} | desc=${c.contentDescription} | selected=${c.isSelected}")
                        recycleNode(c)
                    }
                    val parent = node.parent
                    if (parent != null) {
                        sb.append("\n  parent:      viewId=${parent.viewIdResourceName} | class=${parent.className} | desc=${parent.contentDescription} | selected=${parent.isSelected}")
                        repeat(parent.childCount.coerceAtMost(5)) { i ->
                            val sib = parent.getChild(i) ?: return@repeat
                            sb.append("\n  sibling[$i]: viewId=${sib.viewIdResourceName} | class=${sib.className} | desc=${sib.contentDescription} | selected=${sib.isSelected}")
                            recycleNode(sib)
                        }
                        val gp = parent.parent
                        if (gp != null) {
                            sb.append("\n  grandparent: viewId=${gp.viewIdResourceName} | class=${gp.className} | desc=${gp.contentDescription} | selected=${gp.isSelected}")
                            recycleNode(gp)
                        }
                        recycleNode(parent)
                    }
                    Log.d(TAG_TAP, "$sb")
                } finally {
                    recycleNode(node)
                }
            }
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED       -> {
                if (DevConfig.enabled && pkg == INSTAGRAM_PACKAGE)
                    Log.d(TAG, "WINDOW_STATE cls=${event.className} txt=${event.text.firstOrNull()}")
                handleWindowChange(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED     -> handleContentChanged(pkg, event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED              -> handleScroll(pkg, event)
            AccessibilityEvent.TYPE_VIEW_CLICKED               -> handleTap(pkg, event)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (pkg != packageName) dispatcher.onNotificationReceived(pkg == INSTAGRAM_PACKAGE)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()

        ServiceStats.isRunning.value = false
        getSharedPreferences(FlowLogApplication.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(FlowLogApplication.KEY_SERVICE_RUNNING, false).apply()

        job.cancel()

        unregisterReceiver(screenReceiver)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    private fun handleWindowChange(pkg: String) {
        if (pkg == INSTAGRAM_PACKAGE) {
            Log.d(TAG, "Instagram foregrounded — starting/resuming session")
            sessionManager.onInstagramForeground()
            ServiceStats.activeSessionId.value = sessionManager.activeSessionId
            // Reset pause timer so stale inter-session gap doesn't fire as a PAUSE at session start.
            lastInstagramEventMonoMs = 0L
            // Seed an initial post_view_id so events before the first scroll are still linked.
            if (currentPostViewId == null) currentPostViewId = UUID.randomUUID().toString()
        } else if (sessionManager.activeSessionId != null) {
            // System overlays (IME, system UI, accessibility overlays) are not genuine app switches.
            if (windows?.any {
                    it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD         ||
                    it.type == AccessibilityWindowInfo.TYPE_SYSTEM                ||
                    it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                } == true) return
            Log.d(TAG, "Instagram backgrounded — switched to $pkg")
            resetPostTracking()
            sessionManager.onInstagramBackground()
            dispatcher.onAppSwitch()
        }
    }

    private fun handleScroll(pkg: String, event: AccessibilityEvent) {
        if (pkg != INSTAGRAM_PACKAGE) return
        checkAndFirePause()

        val rawEvent  = event.toRawMap()
        val viewId    = rawEvent["viewIdResourceName"] as? String
        val deltaX    = event.scrollDeltaX ?: 0
        val deltaY    = event.scrollDeltaY ?: 0
        val direction = when {
            Math.abs(deltaX) > Math.abs(deltaY) && deltaX != 0 -> if (deltaX > 0) "right" else "left"
            deltaY < 0 -> "up"
            else -> "down"
        }
        val rawContext = inferScreenContext(event, viewId)
        val context   = if ((direction == "left" || direction == "right") && rawContext == "unknown") "carousel" else rawContext
        lastScreenContext = context

        when {
            viewId == REELS_PAGER_ID -> trackReelIndex(event.fromIndex)
            context == "feed"        -> trackFeedItem(event)
            // carousel: horizontal swipe inside a post — outer feed child unchanged → no ID update
        }

        if (DevConfig.enabled) {
            Log.d(TAG_SCROLL, "viewId=$viewId fromIndex=${event.fromIndex} dir=$direction ctx=$context delta=${event.scrollDeltaY}")
        }

        if (viewId == "com.instagram.android:id/direct_private_share_recipients_recycler_view" && !shareSheetOpen) {
            shareSheetOpen = true
            Log.d(TAG, "POST_INTERACTION action=share_sheet_opened context=$lastScreenContext")
            dispatcher.onPostInteraction(INSTAGRAM_PACKAGE, "share_sheet_opened", lastScreenContext, currentPostViewId)
        }

        Log.d(TAG, "scroll direction=$direction delta=${event.scrollDeltaY} context=$context")
        dispatcher.onScroll(
            sourcePackage = pkg,
            direction     = direction,
            delta         = event.scrollDeltaY,
            screenContext = context,
            postViewId    = currentPostViewId,
            rawEvent      = rawEvent,
        )
    }

    private fun handleTap(pkg: String, event: AccessibilityEvent) {
        if (pkg != INSTAGRAM_PACKAGE) return
        checkAndFirePause()

        val rawEvent    = event.toRawMap()
        val sourceId    = rawEvent["viewIdResourceName"] as? String
        val context     = inferScreenContext(event, sourceId)
        lastScreenContext = context
        val elementType = inferElementType(event)

        // Elevate known share button taps to named POST_INTERACTION events.
        val shareAction = when (sourceId) {
            "com.instagram.android:id/direct_share_sheet_grid_view_pog" -> "share_recipient_selected"
            "com.instagram.android:id/direct_send_button_multi_select"  -> "share_sent"
            else -> null
        }
        if (shareAction != null) {
            Log.d(TAG, "POST_INTERACTION action=$shareAction context=$context")
            dispatcher.onPostInteraction(pkg, shareAction, context, currentPostViewId)
        }

        // Feed share button: ViewGroup with no viewId/contentDesc in the post action bar.
        // Confirmed via Logcat: className=android.view.ViewGroup, no ID, right of the like button.
        // Uses findFeedShareButton() for a structural bounds check rather than hardcoded pixels.
        if (sourceId == null && rawEvent["nodeClassName"] == "android.view.ViewGroup" && context == "feed") {
            val tappedLeft = rawEvent["nodeBoundsLeft"] as? Int ?: -1
            val tappedTop  = rawEvent["nodeBoundsTop"]  as? Int ?: -1
            mainScope.launch {
                val root     = rootInActiveWindow ?: return@launch
                val shareBtn = try { findFeedShareButton(root) } finally { recycleNode(root) }
                shareBtn ?: return@launch
                val (bounds, _) = shareBtn
                if (Math.abs(bounds.left - tappedLeft) < 40 && Math.abs(bounds.top - tappedTop) < 40) {
                    Log.d(TAG, "POST_INTERACTION action=share_tapped context=feed")
                    dispatcher.onPostInteraction(INSTAGRAM_PACKAGE, "share_tapped", "feed", currentPostViewId)
                    if (DevConfig.enabled) TestBus.postInteractionSignal.tryEmit("share_tapped")
                }
            }
        }

        // Reels repost button: no viewId, identified by content description only.
        // isSelected never flips on any node in the hierarchy — direction not detectable.
        // Locale-limited: confirmed "Repost" (en) and "Republier" (fr) only.
        if (sourceId == null && rawEvent["nodeClassName"] == "android.widget.ImageView" && context == "reels") {
            val desc = event.contentDescription?.toString()?.lowercase() ?: ""
            if ("repost" in desc || "republier" in desc) {
                Log.d(TAG, "POST_INTERACTION action=repost_tapped context=reels")
                dispatcher.onPostInteraction(INSTAGRAM_PACKAGE, "repost_tapped", "reels", currentPostViewId)
                if (DevConfig.enabled) TestBus.postInteractionSignal.tryEmit("repost_tapped")
            }
        }

        Log.d(TAG, "tap element=$elementType context=$context")
        dispatcher.onTap(
            sourcePackage = pkg,
            elementType   = elementType,
            screenContext = context,
            postViewId    = currentPostViewId,
            rawEvent      = rawEvent,
        )
    }

    private fun handleContentChanged(pkg: String, event: AccessibilityEvent) {
        if (pkg != INSTAGRAM_PACKAGE) return
        // SeekBar events are the Reels video scrubber firing on every playback tick — ignore them
        // or the debounce never settles while a video is playing.
        if (event.className?.toString() == "android.widget.SeekBar") return
        // Debounce: Instagram fires TYPE_WINDOW_CONTENT_CHANGED rapidly. The scan only runs
        // after a 200ms quiet period.
        likeCheckJob?.cancel()
        likeCheckJob = mainScope.launch {
            delay(200)
            scanForPostInteractions()
        }
    }

    // Must run on main thread — rootInActiveWindow is only safe to call there.
    private fun scanForPostInteractions() {
        val root = rootInActiveWindow ?: return
        try {
            // Both button IDs accepted for each action — whichever Instagram renders fires the event.
            // Context comes from lastScreenContext (scroll-derived), not from the button ID found.
            scanToggleButton(root, "com.instagram.android:id/row_feed_button_like", "liked",    "unliked")
            scanToggleButton(root, "com.instagram.android:id/like_button",          "liked",    "unliked")
            scanToggleButton(root, "com.instagram.android:id/row_feed_button_save", "saved",    "unsaved")
            scanToggleButton(root, "com.instagram.android:id/save_button",          "saved",    "unsaved")
            scanToggleButton(root, "com.instagram.android:id/reposts_ufi_icon",     "reposted", "unreposted")
            // Reels repost: handled via content description in handleTap (direction not detectable).
            checkForCommentSection(root)
        } finally {
            root.recycle()
        }
    }

    /**
     * Fires comment_section_opened once per sheet instance.
     * Detection: bottom_sheet_container appearing without the share-sheet grid child.
     * Resets when the sheet disappears from the window.
     */
    private fun checkForCommentSection(root: AccessibilityNodeInfo) {
        val containers = root.findAccessibilityNodeInfosByViewId(
            "com.instagram.android:id/bottom_sheet_container"
        )
        val hasBottomSheet = containers.isNotEmpty()
        containers.forEach { recycleNode(it) }

        if (!hasBottomSheet) {
            commentSectionOpen = false
            shareSheetOpen     = false
            return
        }

        // bottom_sheet_container is also used by the share sheet — exclude it.
        val shareNodes = root.findAccessibilityNodeInfosByViewId(
            "com.instagram.android:id/direct_share_sheet_grid_view_pog"
        )
        val isShareSheet = shareNodes.isNotEmpty()
        shareNodes.forEach { recycleNode(it) }

        if (!isShareSheet) shareSheetOpen = false
        if (!isShareSheet && !commentSectionOpen) {
            commentSectionOpen = true
            Log.d(TAG, "POST_INTERACTION action=comment_section_opened context=$lastScreenContext")
            dispatcher.onPostInteraction(INSTAGRAM_PACKAGE, "comment_section_opened", lastScreenContext, currentPostViewId)
        }
    }

    /**
     * Scans for a toggle-state button (isSelected) and fires a POST_INTERACTION event when
     * it flips. Accepts any button ID — context is always lastScreenContext (scroll-derived),
     * so a like button found in either the feed or reels layout emits the right context.
     * Key uses node.hashCode() so two consecutive posts by the same author are tracked independently.
     */
    private fun scanToggleButton(
        root: AccessibilityNodeInfo,
        buttonId: String,
        onActivate: String,
        onDeactivate: String,
    ) {
        val nodes = root.findAccessibilityNodeInfosByViewId(buttonId)
        for (node in nodes) {
            try {
                val isActive  = node.isSelected
                // Per-node key so buttons from different posts (e.g. inline reel's save_button
                // and the centered post's row_feed_button_save) don't overwrite each other's state.
                val nodeKey   = "$onActivate:${node.hashCode()}"
                val wasActive = postInteractionTracker.getOrDefault(nodeKey, false)
                // Time-window key groups liked/unliked (and saved/unsaved, etc.) together because
                // they are two sides of the same toggle — suppresses duplicate fires from sibling
                // button IDs that represent the same action on the same post within one scan.
                val dedupKey  = "$currentPostViewId:$onActivate"
                val now       = android.os.SystemClock.elapsedRealtime()
                when {
                    !wasActive && isActive -> {
                        if (now - recentToggleFires.getOrDefault(dedupKey, 0L) >= TOGGLE_DEDUP_WINDOW_MS) {
                            Log.d(TAG, "POST_INTERACTION action=$onActivate context=$lastScreenContext")
                            dispatcher.onPostInteraction(INSTAGRAM_PACKAGE, onActivate, lastScreenContext, currentPostViewId)
                            if (DevConfig.enabled) TestBus.postInteractionSignal.tryEmit(onActivate)
                            recentToggleFires[dedupKey] = now
                        }
                    }
                    wasActive && !isActive -> {
                        val deactivateDedupKey = "$currentPostViewId:$onDeactivate"
                        if (now - recentToggleFires.getOrDefault(deactivateDedupKey, 0L) >= TOGGLE_DEDUP_WINDOW_MS) {
                            Log.d(TAG, "POST_INTERACTION action=$onDeactivate context=$lastScreenContext")
                            dispatcher.onPostInteraction(INSTAGRAM_PACKAGE, onDeactivate, lastScreenContext, currentPostViewId)
                            if (DevConfig.enabled) TestBus.postInteractionSignal.tryEmit(onDeactivate)
                            recentToggleFires[deactivateDedupKey] = now
                        }
                    }
                }
                postInteractionTracker[nodeKey] = isActive
            } finally {
                recycleNode(node)
            }
        }
    }

    // ── Dev test runner ───────────────────────────────────────────────────────

    private suspend fun collectTestActions() {
        TestBus.triggerAction.collect { action ->
            when (action) {
                TestAction.DoubleTapCenter    -> runDoubleTapCenterTest()
                TestAction.TapFeedSaveButton  -> runTapViewByIdTest(
                    viewId   = "com.instagram.android:id/row_feed_button_save",
                    logLabel = "tap_feed_save",
                )
                TestAction.TapFeedShareButton -> runFeedShareButtonTest()
            }
        }
    }

    private suspend fun runDoubleTapCenterTest() {
        Log.i(TAG_TEST, "START double_tap_center")
        val cx = resources.displayMetrics.widthPixels / 2f
        val cy = resources.displayMetrics.heightPixels / 2f
        dispatchTapAt(cx, cy)
        mainScope.launch { delay(150L); dispatchTapAt(cx, cy) }
        awaitAndReport("double_tap_center")
    }

    private suspend fun runTapViewByIdTest(viewId: String, logLabel: String) {
        Log.i(TAG_TEST, "START $logLabel")
        val tapPoint = withContext(Dispatchers.Main) {
            val root = rootInActiveWindow ?: return@withContext null
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                val node  = nodes.firstOrNull()
                if (node == null) { nodes.forEach { recycleNode(it) }; return@withContext null }
                val rect = Rect()
                node.getBoundsInScreen(rect)
                nodes.forEach { recycleNode(it) }
                rect
            } finally {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION") root.recycle()
                }
            }
        }
        if (tapPoint == null) {
            Log.i(TAG_TEST, "FAIL $logLabel node_not_found")
            TestBus.testResult.value = TestResult.Fail
            return
        }
        dispatchTapAt(tapPoint.exactCenterX(), tapPoint.exactCenterY())
        awaitAndReport(logLabel)
    }

    private suspend fun runFeedShareButtonTest() {
        Log.i(TAG_TEST, "START tap_feed_share")
        val found = withContext(Dispatchers.Main) {
            val root = rootInActiveWindow ?: return@withContext null
            try { findFeedShareButton(root) } finally {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION") root.recycle()
                }
            }
        }
        if (found == null) {
            Log.i(TAG_TEST, "FAIL tap_feed_share node_not_found")
            TestBus.testResult.value = TestResult.Fail
            return
        }
        val (bounds, details) = found
        Log.i(TAG_TEST, "tap_feed_share node — $details")
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        dispatchTapAt(cx, cy)
        delay(370L)
        dispatchTapAt(cx, cy)
        awaitAndReport("tap_feed_share")
    }

    /**
     * Locates the feed share button by navigating up 2 levels from the known like button to
     * reach the action bar, then scans children for the rightmost no-ID node that sits between
     * the like and save buttons horizontally. Returns the node bounds + a full detail string.
     */
    private fun findFeedShareButton(root: AccessibilityNodeInfo): Pair<Rect, String>? {
        val likeNodes = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/row_feed_button_like")
        val likeNode  = likeNodes.firstOrNull()
        likeNodes.drop(1).forEach { recycleNode(it) }
        if (likeNode == null) return null

        val likeBounds = Rect().also { likeNode.getBoundsInScreen(it) }
        val p1        = likeNode.parent; recycleNode(likeNode)
        if (p1 == null) return null
        val actionBar = p1.parent; recycleNode(p1)
        if (actionBar == null) return null

        val saveNodes  = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/row_feed_button_save")
        val saveBounds = Rect().also { saveNodes.firstOrNull()?.getBoundsInScreen(it) }
        saveNodes.forEach { recycleNode(it) }

        var bestNode   : AccessibilityNodeInfo? = null
        var bestBounds = Rect()

        fun scan(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 2) return
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val cb = Rect().also { child.getBoundsInScreen(it) }
                val inRow       = cb.top  >= likeBounds.top    - 20 && cb.bottom <= likeBounds.bottom + 20
                val noId        = child.viewIdResourceName == null
                val rightOfLike = cb.left > likeBounds.right
                val leftOfSave  = saveBounds.isEmpty() || cb.right < saveBounds.left
                if (inRow && noId && rightOfLike && leftOfSave) {
                    // Among all candidates pick the rightmost — share sits further right than comment
                    if (bestNode == null || cb.left > bestBounds.left) {
                        bestNode?.let { recycleNode(it) }
                        bestNode   = child
                        bestBounds = cb
                    } else {
                        recycleNode(child)
                    }
                } else {
                    scan(child, depth + 1)
                    recycleNode(child)
                }
            }
        }
        scan(actionBar, 0)
        recycleNode(actionBar)

        val node = bestNode ?: return null
        val details = buildString {
            append("viewId=${node.viewIdResourceName}")
            append(" | contentDesc=${node.contentDescription}")
            append(" | className=${node.className}")
            append(" | bounds=$bestBounds")
            append(" | isClickable=${node.isClickable}")
            append(" | isCheckable=${node.isCheckable}")
            append(" | isSelected=${node.isSelected}")
            append(" | text=${node.text}")
            append(" | childCount=${node.childCount}")
            for (j in 0 until minOf(node.childCount, 3)) {
                val c = node.getChild(j) ?: continue
                append(" | child[$j](class=${c.className} id=${c.viewIdResourceName} desc=${c.contentDescription} selected=${c.isSelected})")
                recycleNode(c)
            }
        }
        recycleNode(node)
        return Pair(bestBounds, details)
    }

    private suspend fun awaitAndReport(logLabel: String) {
        val detected = withTimeoutOrNull(3_000L) {
            TestBus.postInteractionSignal.first()
        }
        if (detected != null) {
            Log.i(TAG_TEST, "PASS $logLabel detected=$detected")
            TestBus.testResult.value = TestResult.Pass(detected)
        } else {
            Log.i(TAG_TEST, "FAIL $logLabel detected=none")
            TestBus.testResult.value = TestResult.Fail
        }
    }

    private fun dispatchTapAt(cx: Float, cy: Float) {
        val p = Path().apply { moveTo(cx, cy) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0L, 50L))
                .build(),
            null, null,
        )
    }

    // Assigns a new post_view_id whenever the Reels ViewPager snaps to a different index.
    private fun trackReelIndex(currentIndex: Int) {
        if (currentIndex < 0) return
        if (currentIndex != lastReelIndex) {
            currentPostViewId = UUID.randomUUID().toString()
            Log.d(TAG, "new post_view_id=$currentPostViewId (reel index $lastReelIndex → $currentIndex)")
        }
        lastReelIndex = currentIndex
    }

    // Assigns a new post_view_id when the feed post centered on screen changes.
    // Uses event.source (the RecyclerView node) to find which child View is closest to screen
    // center. Same View object = same post regardless of pixel-level wiggles. A genuine scroll
    // to the next post loads a different child View → different hashCode → new ID.
    // Carousel swipes fire on an inner ViewPager2, not the outer feed RecyclerView, so the
    // centered child hash is unaffected — carousel slides share the same post_view_id.
    private fun trackFeedItem(event: AccessibilityEvent) {
        val hash = findCenteredFeedItemHash(event) ?: return
        if (hash != lastFeedItemHash) {
            lastFeedItemHash = hash
            currentPostViewId = UUID.randomUUID().toString()
            Log.d(TAG, "new post_view_id=$currentPostViewId (feed centered item changed)")
        }
    }

    private fun findCenteredFeedItemHash(event: AccessibilityEvent): Int? {
        val feedNode = event.source ?: return null
        val screenCenterY = resources.displayMetrics.heightPixels / 2
        val bounds = Rect()
        var closestHash: Int? = null
        var minDist = Int.MAX_VALUE
        for (i in 0 until feedNode.childCount) {
            val child = feedNode.getChild(i) ?: continue
            child.getBoundsInScreen(bounds)
            val dist = Math.abs((bounds.top + bounds.bottom) / 2 - screenCenterY)
            if (dist < minDist) {
                minDist = dist
                closestHash = child.hashCode()
            }
            recycleNode(child)
        }
        recycleNode(feedNode)
        return closestHash
    }

    // Clears post-view state on session end, app switch, or screen off.
    private fun resetPostTracking() {
        currentPostViewId = null
        lastReelIndex = -1
        lastFeedItemHash = -1
    }

    private fun checkAndFirePause() {
        val nowMs = SystemClock.elapsedRealtime()
        val gapMs = nowMs - lastInstagramEventMonoMs
        if (lastInstagramEventMonoMs > 0 && gapMs >= PAUSE_THRESHOLD_MS) {
            dispatcher.onPause(INSTAGRAM_PACKAGE, gapMs, lastScreenContext, currentPostViewId)
        }
        lastInstagramEventMonoMs = nowMs
    }

    private fun recycleNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION") node.recycle()
        }
    }

    // ── Heuristics ────────────────────────────────────────────────────────────

    private fun inferScreenContext(event: AccessibilityEvent, viewId: String? = null): String {
        val fromId = inferContextFromViewId(viewId)
        if (fromId != null) return fromId
        val cls  = event.className?.toString()?.lowercase() ?: ""
        val text = event.text.joinToString(" ").lowercase()
        return when {
            "reel"    in cls || "reel"    in text -> "reels"
            "story"   in cls || "story"   in text -> "story"
            "direct"  in cls || "message" in text -> "dm"
            "share"   in cls || "send to" in text -> "share"
            "profile" in cls                      -> "profile"
            "feed"    in cls                      -> "feed"
            else                                  -> "unknown"
        }
    }

    private fun inferElementType(event: AccessibilityEvent): String {
        val cls = event.className?.toString()?.lowercase() ?: ""
        return when {
            "button"    in cls -> "button"
            "imageview" in cls -> "image"
            "videoview" in cls -> "video"
            "textview"  in cls -> "text"
            else               -> "unknown"
        }
    }

    // ── Background coroutines ─────────────────────────────────────────────────

    private suspend fun runHeartbeat() {
        val prefs = getSharedPreferences(FlowLogApplication.PREFS_NAME, Context.MODE_PRIVATE)
        while (true) {
            delay(HEARTBEAT_INTERVAL_MS)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putLong(FlowLogApplication.KEY_LAST_HEARTBEAT_MS, now)
                .apply()
            // Mirror last event time to SharedPreferences so FlowLogApplication can read it
            // on the next cold start for the crash notification.
            val lastMs = ServiceStats.lastEventAtMs.value
            if (lastMs > 0L) {
                prefs.edit().putLong(FlowLogApplication.KEY_LAST_EVENT_MS, lastMs).apply()
            }
        }
    }

    private suspend fun runNotificationUpdates() {
        val nm       = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val todayStr = todayStartUtc()
        combine(
            ServiceStats.lastEventAtMs,
            db.sessionDao().observeTodaySessionCount(todayStr),
            tickerFlow(30_000L),
        ) { lastMs, sessionsToday, _ ->
            val elapsed = elapsedText(lastMs)
            "Last event: $elapsed • $sessionsToday session${if (sessionsToday == 1) "" else "s"} today"
        }.collect { statusText ->
            nm.notify(FlowLogApplication.NOTIF_FOREGROUND, buildForegroundNotification(statusText))
        }
    }

    // ── Notification builder ──────────────────────────────────────────────────

    private fun buildForegroundNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, FlowLogApplication.CHANNEL_SERVICE_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("FlowLog is recording")
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}

// ── Screen-context inference ──────────────────────────────────────────────────

internal fun inferContextFromViewId(viewId: String?): String? {
    if (viewId == null) return null
    return when {
        "clips_viewer"  in viewId -> "reels"
        "like_button"   in viewId -> "reels"
        "save_button"   in viewId -> "reels"
        "row_feed"      in viewId -> "feed"
        "android:id/list" in viewId -> "feed"   // main feed RecyclerView
        "comment"       in viewId -> "comments"
        "sticky_header" in viewId -> "comments"
        // DM tab and inbox — must come before the general "direct_" share rule
        "direct_tab"    in viewId -> "dm"
        "inbox"         in viewId -> "dm"
        "direct_"       in viewId -> "share"
        "share"         in viewId -> "share"
        "story"         in viewId -> "story"
        "profile"       in viewId -> "profile"
        else                      -> null
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

private fun todayStartUtc(): String {
    val today = LocalDate.now(ZoneOffset.UTC)
    return "${today}T00:00:00Z"
}

private fun elapsedText(lastEventMs: Long): String {
    if (lastEventMs == 0L) return "no events yet"
    val secs = (System.currentTimeMillis() - lastEventMs) / 1_000
    return when {
        secs < 60   -> "${secs}s ago"
        secs < 3600 -> "${secs / 60}m ago"
        else        -> "${secs / 3600}h ago"
    }
}

private fun tickerFlow(periodMs: Long) = flow {
    while (true) { emit(Unit); delay(periodMs) }
}

/**
 * Serialises all scalar AccessibilityEvent fields plus node info from event.source.
 *
 * Node fields: viewIdResourceName, nodeBounds*, nodeClassName.
 * Text fingerprinting is handled separately in textFields() and merged by the caller.
 *
 * event.source is acquired once and recycled on API < 33 (deprecated in API 33).
 * If source is null all node fields are explicitly set to null.
 */
private fun AccessibilityEvent.toRawMap(): Map<String, Any?> = buildMap {
    put("rawEventType",        AccessibilityEvent.eventTypeToString(eventType))
    put("contentChangeTypes",  contentChangeTypes)
    put("className",           className?.toString())
    put("scrollDeltaX",        scrollDeltaX)
    put("scrollDeltaY",        scrollDeltaY)
    put("scrollX",             scrollX)
    put("scrollY",             scrollY)
    put("fromIndex",           fromIndex)
    put("toIndex",             toIndex)
    put("itemCount",           itemCount)
    put("currentItemIndex",    currentItemIndex)
    put("windowId",            windowId)
    put("action",              action)
    put("movementGranularity", movementGranularity)
    put("isEnabled",           isEnabled)
    put("isChecked",           isChecked)
    put("isPassword",          isPassword)
    put("isFullScreen",        isFullScreen)
    put("recordCount",         recordCount)

    val node = source
    if (node != null) {
        try {
            put("viewIdResourceName", node.viewIdResourceName)
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            put("nodeBoundsLeft",     bounds.left)
            put("nodeBoundsTop",      bounds.top)
            put("nodeBoundsRight",    bounds.right)
            put("nodeBoundsBottom",   bounds.bottom)
            put("nodeClassName",      node.className?.toString())
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
    } else {
        put("viewIdResourceName", null)
        put("nodeBoundsLeft",     null)
        put("nodeBoundsTop",      null)
        put("nodeBoundsRight",    null)
        put("nodeBoundsBottom",   null)
        put("nodeClassName",      null)
    }
}
