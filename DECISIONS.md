# DECISIONS.md

Decisions made during scaffolding that are not fully resolved by the spec. Review and override any of these before deployment.

---

## D1 — JSON library: Gson over kotlinx-serialization

**Chose:** `com.google.code.gson:gson`
**Why:** kotlinx-serialization requires a compiler plugin, which adds build configuration steps that are easy to misconfigure for a first-time Android developer. Gson is simpler to add and just as readable in output. Both produce equivalent JSON.
**Revisit if:** the project grows significantly and you want compile-time safety on serialization shapes.

---

## D2 — Privacy layer exposes `redact()` as `internal`

**Chose:** `PrivacyLayer.redact()` is `internal` rather than `private` so unit tests can call it directly without spinning up a Room database.
**Why:** The spec requires unit tests on the privacy redaction layer. Exposing only the redaction logic (a pure function) keeps tests fast and deterministic. DB writes are tested in the integration test instead.

---

## D3 — No `packageNames` filter in accessibility_service_config.xml

**Chose:** The XML config does not restrict to `com.instagram.android`.
**Why:** Notification events (`TYPE_NOTIFICATION_STATE_CHANGED`) must arrive from all packages. If we filtered by Instagram at the XML level, we'd miss notifications from other apps. All Instagram-specific filtering happens in code (`FlowLogAccessibilityService`), which is auditable.

---

## D4 — Session end threshold is a top-level constant, not a user-facing setting

**Chose:** `SESSION_END_THRESHOLD_MS = 30_000L` in `SessionManager.kt`.
**Why:** The spec says "configurable, default 30s". For MVP, a constant is sufficient and avoids adding a settings screen. To make it user-facing, expose it in MainActivity's settings section and persist in SharedPreferences.

---

## D5 — `post_view_id` via node hash (feed) and `fromIndex` (Reels) (v0.9.0–0.9.1)

**Chose:** Two strategies depending on surface:
- **Feed**: On every feed scroll event, walk the feed RecyclerView's children (~4-6 nodes) via `event.source`, find the child whose center Y is closest to screen center using `getBoundsInScreen()`, and assign a new UUID only when that child's `hashCode()` changes. Same View object = same post = same hash, regardless of scroll wiggles. Carousel swipes happen in a nested ViewPager2 — the outer feed child is unchanged → same hash → same `post_view_id` for all carousel slides on one post.
- **Reels**: Assign a new UUID on each `fromIndex` change of `clips_viewer_view_pager` (completed swipe). Wiggles that snap back do not change `fromIndex`.
- **PAUSE**: inherits the current `post_view_id` — it does not generate a new one.
- An initial UUID is seeded on Instagram foreground entry so events before the first scroll are never null.

**Why the node-hash approach for feed:** Raw `fromIndex` / `toIndex` on the feed RecyclerView oscillates ±1 during user wiggles — a test with one post produced 16 different IDs in 2 seconds of wiggling. `(fromIndex + toIndex) / 2` still bounces. The node's `hashCode()` is stable because Android's RecyclerView only recycles views that scroll fully off-screen; the view centered on screen keeps its identity through wiggles. This approach requires no content access and no view ID assumptions beyond the RecyclerView container.

**Why PAUSE-based (old v0.9.0 design) was wrong:** If the user likes, saves, or shares a post without pausing first, those interaction events would carry a null or stale `post_view_id`. The node-hash approach assigns the ID on scroll events, which always precede interactions in normal usage, so interactions are correctly attributed.

**Dwell computation:** `MAX(timestamp_mono) − MIN(timestamp_mono)` grouped by `post_view_id`.

---

## D6 — `device_id_hash` salting strategy

**Chose:** `SHA-256(androidId + ":" + installUuid)` where `installUuid` is generated on first launch and stored in SharedPreferences.
**Why:** Android ID alone is stable per app/user pair on Android 8+ but could theoretically be correlated across data exports from the same device. The salt means even if the Android ID leaks, it can't be linked to this export without also knowing the install UUID. The install UUID is never exported.

---

## D7 — Mockito used in SessionManager unit tests

**Chose:** Added `mockito-kotlin` for SessionManagerTest.
**Why:** SessionManager takes AppDatabase, which needs Android to instantiate. Rather than pulling in Robolectric for the session tests (which adds significant test startup time), Mockito stubs let us test the state machine logic purely on the JVM.
**Status:** `testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")` is already in `app/build.gradle.kts`.

---

## D8 — Self-report form Likert item wording *(DELETED)*

**Status:** `SelfReportFormActivity.kt` was deleted. The post-session form was replaced by the inline ESM notification system in v0.3.0 and was never triggered after that. ESM question wording lives in `EsmQuestion` in `EsmTrigger.kt`.

---

## D9 — Foreground service runs inside the AccessibilityService (v0.3.0)

**Chose:** `FlowLogAccessibilityService` calls `startForeground()` on itself (API Q+ with `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, earlier APIs without type). A persistent notification shows last-event time and session count; the "Stop & Export" action triggers export via a local broadcast.
**Why:** AccessibilityServices can be killed by the OS on some manufacturers under memory pressure. Running as a foreground service prevents this and gives the user a visible indicator that recording is active, which is also an ethics requirement.

---

## D11 — `APP_SWITCH` IME filter: window type check, not package blocklist (v0.6.0)

**Chose:** Skip `APP_SWITCH` events (and session countdown) when `windows.any { it.type == TYPE_INPUT_METHOD || TYPE_SYSTEM || TYPE_ACCESSIBILITY_OVERLAY }` is true at the time of the `TYPE_WINDOW_STATE_CHANGED` event.
**Why:** A blocklist of package names would require maintenance. Checking window type is authoritative — Android marks IME windows with `TYPE_INPUT_METHOD`, system UI (nav bar, status bar) with `TYPE_SYSTEM`, and accessibility overlays with `TYPE_ACCESSIBILITY_OVERLAY`. All three fire spurious `TYPE_WINDOW_STATE_CHANGED` events (observed: comment section opening caused `com.android.systemui` `TYPE_SYSTEM` events to trigger false APP_SWITCHes). `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` is already set so `windows` is available.
**Limitation:** If a genuine navigation event coincides with an open keyboard or active system overlay, the switch would be skipped. In practice negligible for Instagram usage.

---

## D12 — `APP_SWITCH` drops `targetPackage` from payload (v0.6.0)

**Chose:** `APP_SWITCH` events record only that the user left Instagram; the destination package is not logged.
**Why:** For the research questions (session shape, usage intensity, ESM epoch structure) knowing *that* the user switched out is sufficient — which app they went to is not needed and creates unnecessary linkage risk if the export is shared. The session end/start pattern already captures the session gap.
**Revisit if:** A research question requires knowing what other apps the user visits during a session.

---

## D13 — `screenContext` uses `viewIdResourceName` as primary signal (v0.6.0)

**Chose:** `inferScreenContext()` checks the event node's `viewIdResourceName` first; falls back to `className` and event `text` only if no ID match is found.
**Why:** Instagram's views are generic Android classes (`RecyclerView`, `ViewGroup`) so the class/text heuristic returned `"unknown"` for ~90% of events. View IDs such as `row_feed_button_like`, `clips_viewer_view_pager`, `sticky_header_list` are stable, Instagram-specific, and unambiguous.
**Risk:** Instagram can rename IDs across app updates. If a context starts returning `"unknown"` again, check Logcat (tag `FlowLog`) to find the new view ID and update the mapping.

---

## D14 — `ServiceStats.activeSessionId` as in-process bridge for dev ESM trigger (v0.6.0)

**Chose:** Added `activeSessionId: MutableStateFlow<String?>` to `ServiceStats` (the existing in-process singleton). Set it in `FlowLogAccessibilityService.handleWindowChange()` on foreground and cleared via the `onSessionFinalized` callback. `EsmTrigger.fireNotifications()` falls back to this value when `sessionManager` is null (dev-button path).
**Why:** The dev "Trigger ESM now" button creates a detached `EsmTrigger` with no `SessionManager`, so SHOWN events were written with `sessionId = null` and became invisible to `getEventsForSession()`. Binding the active service instance or using a bound service would work but is heavyweight for a dev-only path. `ServiceStats` already exists for exactly this kind of in-process state sharing.

---

## D15 — Reels repost direction is not detectable (v0.6.0)

**Chose:** Fire a single `repost_tapped` action (undirected) for Reels repost interactions, rather than `reposted`/`unreposted`.
**Why:** The Reels repost button is an `android.widget.ImageView` with no `viewIdResourceName`. `isSelected` does not flip on the tapped node, its parent, or grandparent — both repost and unrepost produce identical accessibility events. The only stable signal is the content description (`"Repost"` en / `"Republier"` fr), which is locale-dependent and direction-agnostic.
**Limitation:** Only confirmed for English and French Instagram. Other locales will silently miss the event. Feed repost (`reposts_ufi_icon`) is unaffected — it uses `isSelected` and is bidirectional.
**Revisit if:** Instagram adds a viewId or `isSelected` support to the Reels repost button, or if additional locales need support (extend the `desc` match list in `handleTap`).

---

## D16 — Button detection uses OR, not XOR, for like/save (v0.9.1)

**Chose:** `scanForPostInteractions()` calls `scanToggleButton` for both `row_feed_button_like` and `like_button` (and both `row_feed_button_save`/`save_button`) independently, rather than branching on context. Whichever ID Instagram actually renders in the current view fires the event.
**Why:** The original design passed a hardcoded `screenContext` to `scanToggleButton` and made the two IDs mutually exclusive. This breaks for inline reels embedded in the feed scroll — Instagram may render either button ID, and the session context (from `lastScreenContext`) is already `"feed"` because the scroll happened in the feed RecyclerView. Making it XOR meant one ID would always win and the other silently miss. Making it OR means both IDs are always checked; because the dedup key includes `node.hashCode()`, a simultaneous appearance of the same button under both IDs would require two distinct View objects, which doesn't happen in practice.
**Context source:** `scanToggleButton` no longer takes a `screenContext` parameter — it always uses `lastScreenContext` (scroll-derived), which is the correct ground truth for where the interaction occurred.

---

## D17 — Carousel `screenContext` override to `"carousel"` (v0.9.1)

**Chose:** In `handleScroll`, if the scroll direction is `"left"` or `"right"` and the inferred context is `"unknown"`, override to `"carousel"`.
**Why:** Carousel swipes fire `TYPE_VIEW_SCROLLED` on the inner ViewPager2, which has no Instagram-specific view ID — `inferContextFromViewId` returns null, falling through to `"unknown"`. Horizontal scrolls with unknown context are always carousel swipes within a post; no other UI in Instagram produces horizontal `"unknown"` scrolls. The override is applied *after* checking for the Reels pager (which is handled separately) so it cannot affect Reels events.
**Benefit:** `"carousel"` events are now distinguishable from `"unknown"` events in analysis; the outer feed RecyclerView scroll context remains `"feed"` because carousel swipes do not update `lastScreenContext` for the feed.

---

## D19 — Story detection was not achieved; root cause not established (v1.0.1)

**Situation:** Full logcat produced zero accessibility events of any kind during story navigation — no `TYPE_VIEW_CLICKED`, `TYPE_VIEW_SCROLLED`, `TYPE_WINDOW_CONTENT_CHANGED`, or `TYPE_WINDOW_STATE_CHANGED`. The root cause was not investigated.
**Consequence:** Story time appears as a `PAUSE` event with `screenContext=feed` and elevated `durationMs`, indistinguishable from a long ordinary pause at the instrument level.
**Analysis-side handling:** Anomalously long PAUSEs (>30 s) within an active session are candidates for story-viewing time and can be flagged or excluded. Documented in `DATA_AUDIT.md` §10.7 and `EVENTS.md`.
**Further work:** The reason no events fire is unknown. A future investigation could examine which accessibility event types and window types Instagram registers for the story viewer, and whether any detection path exists.

---

## D18 — DM context rules must precede the `"direct_"` share rule (v1.0.1)

**Chose:** In `inferContextFromViewId`, `"direct_tab" in viewId -> "dm"` and `"inbox" in viewId -> "dm"` appear before `"direct_" in viewId -> "share"`.
**Why:** The DM navigation tab (`direct_tab`) and inbox list (`inbox_refreshable_thread_list_recyclerview`) both start with or contain substrings that would otherwise match the share rule. Without the ordering, tapping into DMs labels the event as `"share"` and all subsequent DM scrolls and PAUSEs get the wrong context. The share-sheet view IDs (`direct_private_share_recipients_recycler_view`, `direct_share_sheet_grid_view_pog`, `direct_send_button_multi_select`) do not contain `direct_tab` or `inbox`, so they fall through correctly to `"share"`.
**Rule for adding more DM IDs:** confirm the view ID from `FlowLog_Tap` before adding it. Generic substrings (e.g. `"thread"`) risk matching share-sheet recipient rows which Instagram renders as DM thread previews.

---

## D20 — Toggle-button dedup key uses `currentPostViewId`, not `node.hashCode()` (v1.0.2)

**Chose:** Two-layer dedup in `scanToggleButton`:
1. Node state is tracked per `"$onActivate:${node.hashCode()}"` — per node, so buttons from different posts (e.g. an inline reel's `save_button` and the centered post's `row_feed_button_save`) don't overwrite each other's state.
2. A 100ms time window per `"$currentPostViewId:$action"` suppresses duplicate fires from sibling button IDs when both are present for the same post in the same scan.

**Why the two-layer approach is necessary:** A pure `postViewId` key (tried first in this version) broke because `findAccessibilityNodeInfosByViewId` searches the entire window — it finds `save_button` from an inline reel embedded in the feed *and* `row_feed_button_save` from the centered post simultaneously. These belong to different logical posts but share the same `currentPostViewId`. Using only `postViewId` as the tracker key caused state from the inline reel's button to bleed into the centered post's tracker: each time the user unsaved the centered post, the inline reel's `save_button` (still saved) saw tracker=false and fired a phantom "saved". Reverting to per-node state tracking eliminates this bleeding; the time window then suppresses genuine same-post sibling-ID duplicates (the original D16 problem) which happen within milliseconds.

**100ms window:** Safe upper bound — no human can tap the same toggle faster than ~200ms. Liked/unliked and saved/unsaved use independent window keys (not shared), so a rapid like then unlike (if > 100ms apart) fires both correctly.

**Null postViewId gap:** A fire in the `null` postViewId window uses key `"null:action"` — a second fire from a different post (also null) would be suppressed within 100ms. Acceptable: null-postViewId events are unattributable regardless.

---

## D10 — Schema export directory

**Chose:** Room schema exported to `app/schemas/`.
**Why:** `exportSchema = true` in `@Database` produces a JSON file at build time showing the exact table structure. Commit this file — it lets you track schema changes across app versions and is required to write proper `Migration` objects if you ever change the DB schema.
