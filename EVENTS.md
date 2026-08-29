# Event Reference

Every row in the database and JSON export shares the same base schema:

| Field | Type | Notes |
|---|---|---|
| `event_id` | UUID | Unique per event |
| `session_id` | UUID or null | Null for `SCREEN_ON`/`SCREEN_OFF` and for `SELF_REPORT_SUBMITTED`/`SELF_REPORT_DISMISSED`; set for all other event types |
| `post_view_id` | UUID or null | Groups events on the same post/reel. Feed: new UUID when the RecyclerView child node centered on screen changes — detected by walking the feed RecyclerView's children (~4-6 nodes) and finding the one closest to screen center by pixel distance; same View object = same post regardless of wiggles or partial scrolls. Carousel swipes fire on an inner ViewPager2 and don't change the centered feed child, so carousel slides share the same ID. Reels: new UUID on each `fromIndex` change of the clips pager (completed swipe). An initial UUID is seeded when Instagram enters the foreground. Null for session, screen, and ESM events. |
| `timestamp_utc` | ISO 8601 | Wall-clock time with ms precision |
| `timestamp_mono` | nanoseconds | Monotonic clock — use this for interval math, resilient to clock changes |
| `event_type` | string | One of the types below |
| `source_package` | string | App that originated the event |
| `payload` | JSON object | Type-specific fields, documented below |

---

## Session events

### `SESSION_START`
Instagram entered the foreground. Marks the beginning of a session.

**Payload:** *(empty)*

---

### `SESSION_END`
Instagram was backgrounded for more than 30 s, or the screen locked.

**Payload:** *(empty)*

---

## Interaction events
All interaction events have `session_id` set (they only fire while Instagram is active).

### `SCROLL`
User scrolled inside Instagram.

| Payload field | Type | Notes |
|---|---|---|
| `direction` | `"up"` \| `"down"` | |
| `delta` | int or null | Pixel delta if available |
| `screenContext` | string | Best-effort: `feed`, `reels`, `comments`, `story`, `share`, `dm`, `profile`, `unknown` — inferred from `viewIdResourceName` first, class/text fallback |

---

### `TAP`
User tapped inside Instagram.

| Payload field | Type | Notes |
|---|---|---|
| `elementType` | string | Best-effort: `button`, `image`, `video`, `text`, `unknown` |
| `screenContext` | string | Same values as SCROLL |

---

### `PAUSE`
No Instagram interaction event for more than 1 500 ms. Fires once per inactivity gap, on the next Instagram scroll or tap event. Only fires while Instagram is active (never for other apps or outside a session).

This is a dwell signal — it inherits the current `post_view_id` (it does not generate a new one). The ID is set by which post is centered on screen, not by pausing. **Dwell time for a post = `MAX(timestamp_mono) - MIN(timestamp_mono)` across events sharing the same `post_view_id`.**

> **Note:** `durationMs` can span a brief app-switch. If the user tabs out and returns within the 30 s session window, the first Instagram event on return fires a PAUSE whose `durationMs` includes the off-app time. Cross-reference with `APP_SWITCH` events to identify and exclude these cases in analysis.

| Payload field | Type | Notes |
|---|---|---|
| `durationMs` | long | Time since last Instagram interaction event in ms |
| `screenContext` | string | Same values as SCROLL — context the user was in *during* the pause (captured from the preceding event) |

---

### `POST_INTERACTION`
Fires when the user toggles a post action button, shares a post, or opens the comment section. Toggle actions are detected via `isSelected` state-flip, debounced 200 ms. Dedup key is `context:action:nodeHash` — unique per node instance so consecutive posts by the same author are tracked independently.

| Payload field | Type | Notes |
|---|---|---|
| `action` | string | See table below |
| `screenContext` | string | `feed`, `reels`, `story`, `dm`, `share`, `profile`, `unknown` |

**`action` values:**

| Value | How detected |
|---|---|
| `liked` / `unliked` | `isSelected` flip on `com.instagram.android:id/row_feed_button_like` (feed) or `com.instagram.android:id/like_button` (reels) |
| `saved` / `unsaved` | `isSelected` flip on `com.instagram.android:id/row_feed_button_save` (feed) or `com.instagram.android:id/save_button` (reels) |
| `reposted` / `unreposted` | `isSelected` flip on `com.instagram.android:id/reposts_ufi_icon` — feed only |
| `repost_tapped` | Reels repost button tap, detected via content description match (`"repost"` en, `"republier"` fr). **Direction not detectable** — `isSelected` does not flip on any node in the Reels action bar hierarchy. **Locale-limited**: only confirmed working in English and French; other locales will silently miss this event. |
| `share_tapped` | Tap on the feed share button (no-viewId `ViewGroup` confirmed as share button via structural `findFeedShareButton` bounds check) |
| `share_sheet_opened` | Zero-delta scroll on `direct_private_share_recipients_recycler_view`; fires once per open, resets when the sheet closes |
| `share_recipient_selected` | Tap on share sheet recipient grid |
| `share_sent` | Tap on multi-select send button |
| `comment_section_opened` | `bottom_sheet_container` appears without share-sheet child; fires once per open |

> **Known gaps:**
> - Reels repost direction not detectable — fires `repost_tapped` only; locale-limited to English/French (see D15). Other locales silently miss this event.
> - Feed post dwell: query all events sharing the same `post_view_id` with `screenContext = "feed"`. Dwell = `MAX(timestamp_mono) - MIN(timestamp_mono)`. `PAUSE` events contribute to the span but do not generate a new ID.
> - **Stories produce no detectable accessibility events.** Empirically confirmed: full logcat was empty during story navigation — no tap, scroll, content-changed, or window-state event of any kind. Story time appears as a `PAUSE` with `screenContext=feed` and elevated `durationMs`, indistinguishable from a long feed pause at the instrument level. The root cause was not investigated; further work may find a detection path.

---

### `APP_SWITCH`
User backgrounded Instagram mid-session without triggering a session end (e.g. quick tab-out). Session is still considered active — the 30 s countdown starts. IME windows (keyboards), system UI windows (nav/status bar adjustments), and accessibility overlays are excluded — none of these count as an app switch or start the countdown. `post_view_id` is cleared on app switch.

**Payload:** *(empty)*

---


## System events
`session_id` is null for these events. They are only written when adjacent to an active session — if no session is active, the event is dropped.

### `SCREEN_ON` / `SCREEN_OFF`
Screen turned on or off. `SCREEN_OFF` is recorded only when it terminates an active session (it ends the session immediately, then writes the event). `SCREEN_ON` is recorded only if a session is active at the time the screen turns on — since sessions always end on `SCREEN_OFF`, this is effectively never written in practice.

**Payload:** *(empty)*

---

### `NOTIFICATION_RECEIVED`
A notification was posted by any app during an active Instagram session. Notifications received outside a session are dropped entirely. No content, title, body, or sender identity is recorded. Notifications from the recording app itself (`com.linger.app`) are excluded.

`session_id` is set to the active session (non-null).

| Payload field | Type | Notes |
|---|---|---|
| `fromInstagram` | boolean | `true` if the notification came from `com.instagram.android`, `false` for any other app |

---

## ESM events
Experience Sampling Method pings. ESM fires only during active Instagram sessions (the timer only advances while the user is in-app). Each ping posts **two notifications simultaneously** — one per question — so two `SELF_REPORT_SHOWN` events fire per ping. All ESM events share an `esmPeriodId` so they can be linked together.

`SELF_REPORT_SHOWN` carries `session_id` (the active session at fire time). `SELF_REPORT_SUBMITTED` and `SELF_REPORT_DISMISSED` have `session_id = null` — they are handled by a broadcast receiver that has no session reference.

To reconstruct a slice: find the `SELF_REPORT_SHOWN` and `SELF_REPORT_SUBMITTED` rows with the same `esmPeriodId` and `questionType`, then query all events with `timestamp_utc` between them.

### `SELF_REPORT_SHOWN`
ESM notification was posted. Fires regardless of whether the user responds. Two per ping — one for each question.

| Payload field | Type | Notes |
|---|---|---|
| `esmPeriodId` | UUID | Links this period's SHOWN → SUBMITTED/DISMISSED |
| `questionType` | string | `liking_esm_val` or `affective_salience_esm_val` |

---

### `SELF_REPORT_SUBMITTED`
User tapped a response button and the 10 s undo window elapsed without an undo.

| Payload field | Type | Notes |
|---|---|---|
| `esmPeriodId` | UUID | |
| `questionType` | string | `liking_esm_val` or `affective_salience_esm_val` |
| `response` | string | `liking_esm_val`: `pretty_good`, `eh`, or `it_sucked` — `affective_salience_esm_val`: `yes` or `not_really` |
| `completedAtUtc` | ISO 8601 | When the response was confirmed (after undo window) |

---

### `SELF_REPORT_DISMISSED`
User swiped the notification away without responding.

| Payload field | Type | Notes |
|---|---|---|
| `esmPeriodId` | UUID | |
| `questionType` | string | `liking_esm_val` or `affective_salience_esm_val` |
| `completedAtUtc` | ISO 8601 | When the swipe occurred |

---

## What is never recorded

- Any text content (captions, comments, DMs, search queries)
- Usernames or account identifiers of any kind
- Notification titles, bodies, sender names, or sender app identities (only whether the notification came from Instagram or not)
- Screenshots, pixel data, audio
- Precise location
- Contact list, call log, SMS
