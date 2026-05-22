# Changelog

All code changes are logged here. Bump `versionName` in `app/build.gradle.kts` with each edit session.

---

## [1.0.2] — 2026-05-21

### Fixed
- `PAUSE` with implausibly large `durationMs` (e.g. 268 s) fired as the first event of a new session. `lastInstagramEventMonoMs` was never reset between sessions, so `checkAndFirePause()` measured the gap back to the last event of the *previous* session. Fixed by resetting `lastInstagramEventMonoMs = 0` in `handleWindowChange` when Instagram foregrounds; the existing `> 0` guard in `checkAndFirePause()` then blocks any fire until the first real event of the new session.
- Payload integer fields (`durationMs`, `delta`, `timestampMono`, etc.) were exported as floats (`1500.0`, `604.0`) because Gson's default `Map<String, Any>` deserialization coerces every JSON number to `Double`. `payloadGson` now uses `ToNumberPolicy.LONG_OR_DOUBLE`, which preserves integers as `Long` and floats as `Double`.
- Live sessions (exported while Instagram was still open) had `endedAtUtc: null` and `durationMs: null`. The exporter now falls back to `max(timestampUtc)` across the session's events as `endedAtUtc` and computes `durationMs` accordingly. A `liveAtExport: true` flag is added to the session object so the analysis side can distinguish inferred from real end times.
- `POST_INTERACTION` liked/saved/reposted events could fire twice (~34 ms apart) when Instagram rendered both button IDs (`row_feed_button_like` and `like_button`, or `row_feed_button_save` and `save_button`) simultaneously in the view tree. Fixed with a two-layer approach: node state tracked per `"$action:${node.hashCode()}"` (prevents state bleed between buttons from different posts), plus a 100ms time-window per `"$postViewId:$action"` that suppresses same-action duplicate fires within a single scan. A pure `postViewId`-only key was tried first but caused save oscillation: `findAccessibilityNodeInfosByViewId` finds buttons across all visible posts (including inline reels), so an inline reel's `save_button` (still saved) fired phantom saves after every real unsave on the centered post. See D20.

### Decisions
- D20 updated: two-layer dedup (node hash for state + 100ms time window for same-post sibling-ID dedup).

---

## [1.0.1] — 2026-05-21

### Fixed
- `direct_tab` tap was labeled `screenContext=share` instead of `dm` — `direct_` matched the share rule before any DM-specific rule existed.
- DM inbox scrolling (`inbox_refreshable_thread_list_recyclerview`) was labeled `screenContext=unknown`. Both the tab tap and the inbox list are now correctly labeled `dm`, so PAUSEs while in the DM inbox carry `screenContext=dm` and can be filtered in analysis.

### Documentation corrections
- DATA_AUDIT §10.7 corrected: previous text said "SCROLL and TAP may fire incidentally" in stories — confirmed false. Full logcat produced zero events of any kind during story navigation. Story time appears as a `PAUSE` with `screenContext=feed` and elevated `durationMs`; root cause not investigated. See D19.

### Decisions
- D18 added: DM context rules (`direct_tab`, `inbox`) must appear before the general `"direct_"` → `"share"` rule in `inferContextFromViewId`. Further DM rule additions require confirmed view IDs from `FlowLog_Tap` — generic substrings risk false matches against share-sheet recipient rows.
- D19 added: story time is undetectable at the instrument level — no accessibility event of any kind fires during story viewing. No workaround exists.

---

## [1.0.0] — 2026-05-21

First stable release. Study-ready. No functional changes from 0.9.1 — this bump marks the instrument as complete for data collection.

---

## [0.9.1] — 2026-05-21

### Fixed
- `appVersion` in the JSON export was hardcoded to `"0.5.0"`. Now reads from `BuildConfig.VERSION_NAME` so the export always reflects the running version.

### Changed
- **`post_view_id` for feed** — replaced PAUSE-based generation with a node-hash approach. On every feed scroll event, `trackFeedItem()` walks the feed RecyclerView's children (~4-6 nodes) via `event.source`, finds the child whose center Y is closest to screen center using `getBoundsInScreen()`, and generates a new UUID only when that child's `hashCode()` changes. The same View object = the same post regardless of wiggles. Carousel swipes happen inside a nested ViewPager2 — the outer feed child stays unchanged → same hash → same `post_view_id` for all carousel slides on one post. An initial UUID is seeded on Instagram foreground entry so events before the first scroll are never null.
- **`PAUSE` no longer generates a new `post_view_id`** — it inherits the current ID. Feed dwell = `MAX(timestamp_mono) − MIN(timestamp_mono)` across all events sharing the same `post_view_id`.
- **Carousel `screenContext` override** — horizontal scroll events whose inferred context is `"unknown"` are now overridden to `"carousel"`, matching the actual screen state and preventing false `"unknown"` entries in feed-adjacent carousel swipes.
- **Button detection uses OR instead of XOR** — `scanForPostInteractions()` now scans both `row_feed_button_like` and `like_button` for liked/unliked, and both `row_feed_button_save` and `save_button` for saved/unsaved. Whichever ID Instagram renders fires the event; both are accepted. The hardcoded `screenContext` parameter was removed from `scanToggleButton` — context always comes from `lastScreenContext` (scroll-derived), which is correct regardless of whether the interaction is in feed, reels, or an inline reel embedded in the feed.

---

## [0.9.0] — 2026-05-21

### Added
- `post_view_id` — nullable UUID column on every event row. Groups events that occurred while the same post/reel was centered on screen.
  - **Feed**: a new UUID is generated each time a `PAUSE` fires (user settled on a post). Subsequent taps and scrolls carry the same ID until the next PAUSE.
  - **Reels**: a new UUID is generated each time `clips_viewer_view_pager` `fromIndex` changes (completed swipe to a new reel).
  - `null` for session lifecycle events, screen events, notifications, and ESM events.
- DB schema version bumped to 2 with a non-destructive migration (`ALTER TABLE events ADD COLUMN postViewId TEXT DEFAULT NULL`).

### Removed
- `MEDIA_DWELL` event type — replaced by `post_view_id` grouping. Reel dwell is now `MAX(timestamp) - MIN(timestamp)` grouped by `post_view_id` on the analysis side.

### Changed
- `reelDwellStartMonoMs` and `flushReelDwell()` removed from the service; replaced by `trackReelIndex()` + `resetPostTracking()`.

---

## [0.8.0] — 2026-05-20

### Changed
- `NOTIFICATION_RECEIVED` is now dropped when no session is active — only recorded during Instagram sessions. Out-of-session notifications are out of scope.
- `SCREEN_ON` / `SCREEN_OFF` are now dropped when no session is active — only written when adjacent to a session. `SCREEN_OFF` fires only when it terminates an active session. `SCREEN_ON` fires only when the screen turns on during an active session; since sessions always end on `SCREEN_OFF`, this is effectively never written in practice (kept for future use).

---

## [0.7.0] — 2026-05-20

### Added
- `TAG_SCROLL = "FlowLog_Scroll"` — dev-mode Logcat tag on every Instagram scroll event with `viewId`, `fromIndex`, direction, context, and delta
- `screenContext` field on `PAUSE` events — captures the screen context the user was in *during* the inactivity gap (same values as SCROLL/TAP); `PAUSE` with `screenContext = "feed"` is the feed post dwell signal

### Changed
- `EventDispatcher.onPause()` signature extended with `screenContext: String`; `checkAndFirePause()` passes `lastScreenContext` (the context of the preceding event — correct semantics for a gap)
- `NOTIFICATION_RECEIVED` payload changed from `{ sourcePackage: String }` to `{ fromInstagram: Boolean }` — sender app identity is no longer recorded; only whether the notification came from Instagram is stored. `sourcePackage` on the event entity is set to `"android"`. This removes the sensitive app-enumeration risk for ethics review while preserving the interrupt signal needed for analysis.

---

## [0.6.0] — 2026-05-20

### Fixed
- `SessionManagerTest` was silently uncompilable: imported the deleted `SelfReportTrigger` interface (removed in v0.3.0) and passed constructor args in the wrong order (`SessionManager(db, privacy, trigger, scope)` instead of `(db, privacy, scope, onSessionFinalized)`); all 7 session lifecycle tests were never running
- `finalizeSession` called `getOpenSession()` (first row with `endedAtUtc IS NULL`) instead of querying by `sessionId`; stale open rows could cause the wrong session to be closed
- `sessionStartInstant` was never cleared after finalization — stale `Instant` persisted until the next session
- `SessionManager.activeSessionId`, `sessionStartInstant`, and `endJob` were plain `var` accessed from both the main thread (accessibility callbacks) and IO coroutines; added `@Volatile` on all three so cross-thread writes are visible
- Dev "Trigger ESM now" button wrote `SELF_REPORT_SHOWN` events with `sessionId = null` (instantiated a fresh `EsmTrigger` without a `SessionManager`), so the exporter's `getEventsForSession()` never found them → `periodIds` empty → all epochs had `esmPeriodId = null` and both responses null
- `APP_SWITCH` events and session countdown fired when the Samsung keyboard (and any IME) appeared over Instagram — treated as a genuine app navigation when it is not
- `NOTIFICATION_RECEIVED` from `com.irin.flowlog` itself appeared in exports — the service's own foreground notification was being recorded

### Added
- `SessionDao.getSessionById(sessionId: String)` — used by `finalizeSession` to close the exact right row
- `ServiceStats.activeSessionId: MutableStateFlow<String?>(null)` — set in `FlowLogAccessibilityService.handleWindowChange()` on session foreground, cleared in the `onSessionFinalized` callback; bridges the dev-button's detached `EsmTrigger` to the live session
- `ScreenContextTest` — 13 JVM unit tests for `inferContextFromViewId`; `inferContextFromViewId` extracted as `internal` top-level function so tests don't need Android
- `TAG_TAP = "FlowLog_Tap"` — dev-mode Logcat tag that fires on every `TYPE_VIEW_CLICKED` with full node dump: desc, viewId, class, selected, clickable, own children, parent + siblings, grandparent

### Changed
- `EsmTrigger.fireNotifications()` resolves `sessionId` as `sessionManager?.activeSessionId ?: ServiceStats.activeSessionId.value` — the live service path is unchanged; the dev button now also picks up the real session ID
- `inferScreenContext` view-ID rules: added `"save_button" in viewId -> "reels"` (covers `com.instagram.android:id/save_button`, the Reels save button)
- `scanForPostInteractions` detects feed repost/unrepost via `scanToggleButton` on `com.instagram.android:id/reposts_ufi_icon` (`isSelected` flip, bidirectional)
- `scanForPostInteractions` detects Reels save/unsave via `scanToggleButton` on `com.instagram.android:id/save_button` (`isSelected` flip, confirmed via `FlowLog_Tap`)
- `handleTap` detects Reels repost via content description match (`"repost"` / `"republier"`, case-insensitive); fires `repost_tapped` — direction not detectable, locale-limited to English and French (see D15) — confirmed via `FlowLog_Tap` parent dump; fires `reposted`/`unreposted` on `isSelected` flip
- `handleTap` detects the feed share button (`android.view.ViewGroup`, no viewId, no contentDescription) via `findFeedShareButton()` structural bounds check (±40px tolerance); fires `POST_INTERACTION action=share_tapped`; also emits `TestBus.postInteractionSignal` so the existing "Tap feed share button" dev test now passes
- `screenContext` inference uses `viewIdResourceName` as primary signal, class/text as fallback. View-ID rules: `clips_viewer` / `like_button` → `"reels"`; `row_feed` → `"feed"`; `comment` / `sticky_header` → `"comments"`; `direct_` / `share` → `"share"`; `story` → `"story"`; `profile` → `"profile"`. The hardcoded `REELS_PAGER_ID` special-case in `handleScroll` is removed — covered by the general rule
- `APP_SWITCH` fires only when the incoming window is not an IME, system UI, or accessibility overlay — detected via `windows.any { it.type == TYPE_INPUT_METHOD || TYPE_SYSTEM || TYPE_ACCESSIBILITY_OVERLAY }`; covers keyboards, nav/status bar adjustments, and accessibility overlays
- `APP_SWITCH` payload no longer includes `targetPackage` — records that the user left Instagram, not where they went
- `NOTIFICATION_RECEIVED` is filtered at dispatch: events where `pkg == packageName` are dropped

---

## [0.5.0] — 2026-05-20

### Added
- `share_sheet_opened` POST_INTERACTION action — detected via zero-delta scroll on `direct_private_share_recipients_recycler_view`; `shareSheetOpen` flag prevents duplicate fires per open; resets when the share sheet closes (detected by absence of `direct_share_sheet_grid_view_pog`)
- Two-question parallel ESM: Q1 (liking) "How was the last few minutes of scrolling?" → Pretty good / Eh / It sucked; Q2 (affective salience) "Did anything catch your attention or feel strong, positively or negatively?" → Yesss / Not really — both notifications fire simultaneously with the same `esmPeriodId`
- `EsmQuestion` enum (`LIKING_ESM_VAL`, `AFFECTIVE_SALIENCE_ESM_VAL`) with per-question `notifId`, `undoNotifId`, `questionText`, and `actions` list — adding a third question only requires a new enum entry
- `questionType` field on all three ESM events (`SELF_REPORT_SHOWN`, `SELF_REPORT_SUBMITTED`, `SELF_REPORT_DISMISSED`) so per-question responses can be distinguished in analysis
- Undo is now per-question — each question has its own undo notification and independent pending-commit job keyed by question name
- ESM session-gating — `scheduleTimer()` checks `sessionManager?.activeSessionId` before firing; notifications never appear outside an active Instagram session
- `SessionManager.onSessionFinalized` callback — ESM timer resets only after the 30 s grace period expires (or screen off), not on every momentary app switch
- Dev-mode test runner in `dev/` package (`DevConfig.enabled` only):
  - `TestAction` sealed class with `DoubleTapCenter`, `TapFeedSaveButton`, `TapFeedShareButton`; `TestAction.all` list drives the dropdown automatically
  - `TestBus` singleton (`triggerAction`, `postInteractionSignal`, `testResult` flows) decouples MainActivity from the service
  - Dropdown + countdown + PASS/FAIL result UI in MainActivity; dropdown locked during non-Idle states
  - `collectTestActions()` loop in `FlowLogAccessibilityService` runs test actions and reports results via `TestBus`
  - `android:canPerformGestures="true"` added to `accessibility_service_config.xml`

### Changed
- `EsmResponseReceiver` `pendingCommits` map is now keyed by question name (string) instead of a single global job, so answering one question does not cancel the other's undo window

---

## [0.4.0] — 2026-05-19

### Added
- `MEDIA_DWELL` detection for Reels — fires when `clips_viewer_view_pager` `fromIndex` changes (i.e. the user completes a swipe to a new reel); wiggle/hesitation that snaps back does not fire; last reel flushed on session end, app switch, or screen off
- `comment_section_opened` POST_INTERACTION — detects `bottom_sheet_container` appearing without the share-sheet child (`direct_share_sheet_grid_view_pog`); fires once per open, resets when the sheet closes
- `share_recipient_selected` and `share_sent` POST_INTERACTION actions — detected via tap on `direct_share_sheet_grid_view_pog` and `direct_send_button_multi_select` respectively

### Changed
- Reels scroll events now correctly report `screenContext = "reels"` (was `"unknown"` — `ViewPager` class name does not contain "reel" so `inferScreenContext` fell through to the default)
- `lastScreenContext` tracked across scroll and tap events; used by comment detection and reel dwell to attach context without a live event

---

## [0.3.0] — 2026-05-19

### Added
- ESM (Experience Sampling Method) ping notifications — fires every 5 minutes with three inline response buttons ("Pretty good", "Eh", "It sucked"); no form opens
- `esmPeriodId` UUID links `SELF_REPORT_SHOWN` → `SELF_REPORT_SUBMITTED` and the interaction events that fall between them, enabling per-period slicing in analysis
- Undo window — tapping a response shows "Logged: X — Undo?" for 10 s; tapping Undo re-posts the original question with the same `esmPeriodId` so the eventual submission still links to the original shown event
- `DevConfig` — single boolean (`DEV_OVERRIDE`) to force dev mode on/off; defaults to `BuildConfig.DEBUG`; debug builds show a "Trigger ESM now" button in the main screen
- Notification permission prompt on first launch (Android 13+)

### Changed
- ESM notification channel bumped to `IMPORTANCE_HIGH` so pings appear as heads-up banners (reinstall required)
- `minSdk` raised from 26 → 28 (Android 8.1) — `getScrollDeltaX/Y` require API 28; ~3% device coverage drop
- `stopExportReceiver` and `screenReceiver` registration switched to `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` — fixes latent crash on Android 12+

### Removed
- `PostSessionTrigger` and `SelfReportTrigger` interface — replaced entirely by ESM; session end no longer triggers a self-report prompt

---

## [0.2.0] — 2026-04-24

### Added
- `POST_LIKED` event type — fires when a post/reel transitions from unliked → liked
- Like detection via `isSelected` state-flip on view IDs `row_feed_button_like` (feed) and `like_button` (Reels), per Gemini spec
- State tracker (`likeStateTracker`) in `FlowLogAccessibilityService` — in-memory only, prevents duplicate `POST_LIKED` events; author string used as dedup key but never written to DB
- 200ms debounce on `TYPE_WINDOW_CONTENT_CHANGED` (`likeCheckJob`) — avoids firing on every frame during video playback
- `mainScope` (Dispatchers.Main) for `rootInActiveWindow` scans
- `recycleNode()` helper — consolidates the API < 33 recycle boilerplate
- `screenContext` payload field on `POST_LIKED` ("feed" or "reels")
- `contentChangeTypes` field added to `toRawMap()` raw event payload
- `flagRetrieveInteractiveWindows` and `flagIncludeNotImportantViews` added to both XML config and programmatic serviceInfo flags

### Removed
- `textRaw` and `textFingerprint` fields — removed from payloads, `textFields()` function deleted, `sha256Prefix()` helper deleted, `FINGERPRINT_EXCLUDED_CONTEXTS` constant deleted, unused `MessageDigest` import removed
- `text_fingerprint_debug.md` deleted

---

## [0.1.0] — initial scaffold

- Room DB with `events` and `sessions` tables
- `AccessibilityService` with scroll, tap, pause, session start/end, screen on/off, notification, app switch detection
- Post-session self-report form (flow scale × 5, valence, time distortion, free text)
- JSON exporter
- Privacy layer (single write chokepoint)
- Foreground service with persistent notification and Stop & Export action
