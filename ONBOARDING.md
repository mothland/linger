# Onboarding — Linger

**Current version: 1.0.2** (see CHANGELOG.md)  
**Purpose:** Passive behavioral telemetry logger for Instagram. Records interaction events + post-session self-reports to study flow state and Reward Prediction Error. Observes only — never nudges or modifies the user experience.

> **Name note:** The app is called **Linger**, both on-device (home screen, Accessibility settings) and throughout the codebase — package ID (`com.linger.app`), class names, logcat tags, and these docs.

---

## How to work on this project

- Update the docs accordingly for each PR.
- All writes to the DB go through `PrivacyLayer`:  never bypass it.
- Prefer boring, auditable choices. Keep explanations in comments where behaviour is non-obvious.
- Privacy constraints are hard. See the section below before adding any new fields.

---

## Project layout

```
app/src/main/java/com/linger/app/
├── data/
│   ├── entity/         EventEntity.kt, SessionEntity.kt, EventType.kt
│   ├── dao/            EventDao.kt, SessionDao.kt
│   └── AppDatabase.kt
├── service/
│   ├── LingerAccessibilityService.kt   ← main event capture + like/save/share/dwell detection
│   └── EventDispatcher.kt              ← translates raw events → PrivacyLayer calls
├── session/
│   └── SessionManager.kt               ← session start/end state machine
├── privacy/
│   └── PrivacyLayer.kt                 ← single write chokepoint, field whitelist
├── report/
│   ├── EsmTrigger.kt                   ← fires two ESM ping notifications every 5 min during active sessions
│   └── EsmResponseReceiver.kt          ← handles inline quick-tap responses + undo from ESM notifications
├── export/
│   └── JsonExporter.kt                 ← produces the export JSON file
├── ui/
│   └── MainActivity.kt                 ← status screen, export button, kill switch
└── dev/
    ├── DevConfig.kt                    ← single boolean to force dev mode on/off
    ├── TestAction.kt                   ← sealed class of available test actions
    └── TestBus.kt                      ← SharedFlow channels between MainActivity and service
```

---

## Current Event types (EventType.kt)

| Type | When | Key payload |
|---|---|---|
| `SESSION_START` | Instagram enters foreground | — |
| `SESSION_END` | Instagram backgrounded > 30s, or screen lock | — |
| `SCROLL` | User scrolls in Instagram | `direction`, `delta`, `screenContext` |
| `TAP` | User taps in Instagram | `elementType`, `screenContext` |
| `PAUSE` | No Instagram event for > 1500ms — inherits current `post_view_id`; dwell = time span of events sharing that ID | `durationMs`, `screenContext` |
| `POST_INTERACTION` | Like/unlike, save/unsave, repost/unrepost, share actions, comment section opened | `action`, `screenContext` |
| `APP_SWITCH` | Instagram backgrounded mid-session — clears `post_view_id` | — |
| `NOTIFICATION_RECEIVED` | Any notification during an active session (own app excluded; dropped outside sessions) — no sender identity stored | `fromInstagram` (bool) |
| `SCREEN_ON` / `SCREEN_OFF` | Screen state — only written when adjacent to an active session; `SCREEN_ON` effectively never fires in practice | — |
| `SELF_REPORT_SHOWN` / `SELF_REPORT_SUBMITTED` / `SELF_REPORT_DISMISSED` | ESM ping lifecycle — two SHOWN per ping (one per question); `questionType` distinguishes them | `esmPeriodId`, `questionType`, `response` (submitted only) |

---

## POST_INTERACTION detection

All fire a `POST_INTERACTION` event with `action` and `screenContext` fields.

**Toggle actions** — detected via `isSelected` state-flip on button nodes, debounced 200ms (`likeCheckJob`).  
Dedup: two-layer. Node state tracked per `"$action:${node.hashCode()}"` so buttons from different posts in the same window don't bleed into each other. A 100ms time-window per `"$postViewId:$action"` suppresses duplicate fires from sibling button IDs (e.g. `row_feed_button_like` and `like_button`) within the same scan. See D20.  
Both feed and reels button IDs are scanned for every action — whichever Instagram renders fires the event. Context always comes from `lastScreenContext` (scroll-derived), not from which button ID was found.

| Action | View IDs accepted | Notes |
|---|---|---|
| `liked` / `unliked` | `row_feed_button_like`, `like_button` | `isSelected` flip on whichever is present |
| `saved` / `unsaved` | `row_feed_button_save`, `save_button` | `isSelected` flip on whichever is present |
| `reposted` / `unreposted` | `reposts_ufi_icon` | `isSelected` flip; feed only |
| `repost_tapped` | *(no viewId)* | content description match (`"repost"` / `"republier"`); reels only; direction not detectable; English + French only |
| `share_tapped` | *(no viewId)* | structural bounds check via `findFeedShareButton()` — rightmost no-ID `ViewGroup` in the action bar row |

**Share sheet + comment section** — detected via scroll events and taps on known view IDs.

| Action | Detection |
|---|---|
| `share_sheet_opened` | Zero-delta scroll on `direct_private_share_recipients_recycler_view`; fires once per open |
| `share_recipient_selected` | Tap on `direct_share_sheet_grid_view_pog` |
| `share_sent` | Tap on `direct_send_button_multi_select` |
| `comment_section_opened` | `bottom_sheet_container` present without `direct_share_sheet_grid_view_pog` child |

All IDs are prefixed `com.instagram.android:id/`. If detection stops working, use `adb logcat -s Linger_Tap` — every `TYPE_VIEW_CLICKED` event logs the tapped node's viewId, class, contentDescription, isSelected, children, parent, siblings, and grandparent.

---

## Privacy rules (hard constraints)

Never write to DB or logs:
- Any text content (captions, comments, DMs, search queries)
- Usernames or account identifiers of any kind
- Notification titles, bodies, or sender names beyond the app package
- Screenshots, pixel data, audio
- Precise location
- Contact list, call log, SMS

The `PrivacyLayer` is the enforcer. If you add a new event type, add its allowed fields to the whitelist there. Do not write directly to the DAO.

---

## Key constants / config knobs

| Location | Constant | Value | Purpose |
|---|---|---|---|
| `SessionManager.kt` | `SESSION_END_THRESHOLD_MS` | 30 000 ms | Gap before session ends |
| `LingerAccessibilityService.kt` | `PAUSE_THRESHOLD_MS` | 1 500 ms | Inactivity before PAUSE fires |
| `LingerAccessibilityService.kt` | `HEARTBEAT_INTERVAL_MS` | 30 000 ms | SharedPrefs heartbeat |
| `EsmTrigger.kt` | `ESM_INTERVAL_MS` | 5 × 60 × 1 000 ms | Time between ESM pings (only advances during active sessions) |

---

## Logcat tags

| Tag | What it logs | When |
|---|---|---|
| `Linger` | All structured events, session lifecycle, dwell | always (while service is running) |
| `Linger_Tap` | Full node dump on every `TYPE_VIEW_CLICKED`: viewId, class, desc, isSelected, children, parent, siblings, grandparent | dev mode only |
| `Linger_Scroll` | viewId, fromIndex, direction, context, delta on every scroll | dev mode only |
| `Linger_Test` | Dev test runner START/PASS/FAIL | dev mode only |

---

## Things to know / gotchas

- `TYPE_WINDOW_CONTENT_CHANGED` fires on every video frame in Reels — any handler must debounce aggressively. SeekBar events (className `android.widget.SeekBar`) are filtered before the debounce to prevent the scrubber from resetting it constantly.
- `post_view_id` seems to track which post is currently centered on screen. Feed: `trackFeedItem()` uses `event.source` (the feed RecyclerView node) to walk its children (~4-6), finds the child whose center Y is closest to screen center, and generates a new UUID when that child's `hashCode()` changes. Same View object = same post regardless of wiggles. Carousel swipes happen on a nested ViewPager2, so the outer feed child is unchanged → same hash → same ID. Reels: new UUID on `clips_viewer_view_pager` `fromIndex` change. Initial UUID seeded on foreground entry so immediate likes/saves before the first scroll are never null. `PAUSE` inherits the current ID — it does not generate a new one.
- `screenContext` is inferred from `viewIdResourceName` first (Instagram-specific IDs are stable and unambiguous), falling back to class name and event text. The mapping lives in `inferContextFromViewId()` (top-level `internal` function, testable without Android). Horizontal scrolls with `"unknown"` context are overridden to `"carousel"`. If a context starts returning `"unknown"`, check `Linger_Scroll` or `Linger_Tap` to find the new view ID and add it to the mapping. **Order matters**: `"direct_tab"` and `"inbox"` map to `"dm"` and must appear before the general `"direct_"` → `"share"` rule, since those view IDs also start with `direct_`. Only add further DM rules when confirmed from `Linger_Tap` output — generic substrings like `"thread"` risk matching share-sheet recipient rows.
- `APP_SWITCH` is suppressed when any window of type `TYPE_INPUT_METHOD`, `TYPE_SYSTEM`, or `TYPE_ACCESSIBILITY_OVERLAY` is visible — keyboards, nav bar adjustments, and accessibility overlays are not genuine app switches.
- Feed post dwell: query all events sharing the same `post_view_id` with `screenContext = "feed"`. Dwell = `MAX(timestamp_mono) - MIN(timestamp_mono)`. The ID is set by which post View is centered on screen — `PAUSE` events contribute to the dwell span but do not start a new ID.
- Instagram obfuscates its UI; view resource IDs may change across app versions. `Linger_Tap` is the primary debugging tool — filter it in Logcat and tap the button to see the current node hierarchy.
- `AccessibilityNodeInfo.recycle()` is deprecated on API ≥ 33 but required on API < 33. Use `recycleNode()` in `LingerAccessibilityService` for all node recycling.
- `rootInActiveWindow` must be called on the main thread — use `mainScope` (Dispatchers.Main) inside `LingerAccessibilityService`.
- All DB reads/writes in `EventDispatcher` and `PrivacyLayer` use `scope` (Dispatchers.IO).
- No network permissions in the manifest. Do not add any.
- See DECISIONS.md for architectural choices that aren't obvious from the code.
