# Data Collection Audit — flowlog v1.0.0

**Study:** Behavioral telemetry and flow state in social media use  
**Researcher:** Irin, MSc thesis, ESILV Paris  
**Instrument:** flowlog Android app (`com.irin.flowlog`)  
**Last updated:** 2026-05-21

This document describes exactly what the app records, how it is stored, how it leaves the device, and what technical controls enforce the privacy boundaries. It is intended for ethics board review, participant information sheets, and researcher reference.

---

## 1. Purpose

The app passively records behavioral events while the participant uses Instagram, and periodically prompts them for a brief self-report. No content is captured. The goal is to correlate interaction patterns (scrolling behavior, session length, pauses, likes, saves, shares, time spent on content) with self-reported psychological state (flow, valence, perceived time distortion).

The app **observes only**. It never modifies, interrupts, blocks, or influences the user's experience beyond the ESM prompts.

---

## 2. Data collected

### 2.1 Session data

A session is a contiguous period of Instagram use. A new session starts when Instagram enters the foreground; it ends when the user leaves Instagram for more than 30 seconds or the screen locks.

Each session record contains:

| Field | Description |
|---|---|
| `session_id` | Random UUID — no link to identity |
| `started_at_utc` | Session start time (ISO 8601) |
| `ended_at_utc` | Session end time (ISO 8601) |
| `duration_ms` | Total session length in milliseconds |

### 2.2 Behavioral events

Every event row contains:

| Field | Description |
|---|---|
| `event_id` | Random UUID |
| `session_id` | Which session this belongs to (null for out-of-session system events and for `SELF_REPORT_SUBMITTED`/`SELF_REPORT_DISMISSED`) |
| `post_view_id` | UUID grouping events that occurred while the same post or reel was on screen. Feed: changes when the RecyclerView child closest to screen center changes (by pixel distance). Reels: changes on each completed pager swipe. Carousel slides share the same ID as the parent post. Null for session, screen, notification, and ESM events. |
| `timestamp_utc` | Wall-clock time (ISO 8601, millisecond precision) |
| `timestamp_mono` | Monotonic nanosecond timestamp for interval calculations |
| `event_type` | One of the types listed below |
| `source_package` | App package that originated the event |
| `payload` | JSON object with type-specific fields (see below) |

#### Event types and their payloads

**Interaction events** (only fire while Instagram is active):

| Event type | What it captures | Payload fields |
|---|---|---|
| `SCROLL` | User scrolled | `direction` (up/down), `delta` (pixels, if available), `screenContext` (feed/reels/comments/story/share/dm/profile/unknown) |
| `TAP` | User tapped | `elementType` (button/image/video/text/unknown), `screenContext` |
| `PAUSE` | No Instagram interaction for >1.5 s; fires on the next Instagram event. Inherits the current `post_view_id` — it does not generate a new one. | `durationMs` — gap since last Instagram event (may include off-app time if user briefly tabbed out within the session window — cross-reference `APP_SWITCH`); `screenContext` — context during the pause |
| `POST_INTERACTION` | Post action or UI event | `action` (see below), `screenContext` |
| `APP_SWITCH` | Instagram backgrounded mid-session | *(no payload — records that the user left, not where they went)* |

**`POST_INTERACTION` action values:**

| `action` | What it means |
|---|---|
| `liked` / `unliked` | Like button toggled on/off. Detected via `isSelected` flip on `row_feed_button_like` (feed) or `like_button` (reels) — whichever Instagram renders is accepted |
| `saved` / `unsaved` | Save/bookmark button toggled on/off. Detected via `isSelected` flip on `row_feed_button_save` (feed) or `save_button` (reels) — whichever Instagram renders is accepted |
| `reposted` / `unreposted` | Repost toggled — feed only, detected via `isSelected` flip on `reposts_ufi_icon` |
| `repost_tapped` | Reels repost button tapped — direction not detectable; locale-limited to English and French |
| `share_tapped` | Feed share button tapped (structural detection — no view ID on this button) |
| `share_sheet_opened` | Share sheet became visible |
| `share_recipient_selected` | User tapped a recipient in the share sheet |
| `share_sent` | User tapped the send button in the share sheet |
| `comment_section_opened` | Comment sheet became visible |

**Session lifecycle events:**

| Event type | What it captures | Payload fields |
|---|---|---|
| `SESSION_START` | Instagram entered foreground | *(none)* |
| `SESSION_END` | Session closed (30 s gap or screen lock) | *(none)* |

**System events** (`session_id` is null; only written when adjacent to an active session — dropped otherwise):

| Event type | What it captures | Payload fields |
|---|---|---|
| `SCREEN_ON` | Screen turned on (only if a session is active at that moment — in practice never written, since sessions always end on SCREEN_OFF) | *(none)* |
| `SCREEN_OFF` | Screen turned off while a session was active — terminates the session immediately | *(none)* |

**In-session notification events** (only recorded during an active Instagram session; `session_id` is set):

| Event type | What it captures | Payload fields |
|---|---|---|
| `NOTIFICATION_RECEIVED` | Any app posted a notification during an active Instagram session; out-of-session notifications are dropped | `fromInstagram` (boolean) — `true` if from Instagram, `false` for any other app; no sender identity recorded |

### 2.3 What the app observes outside Instagram but does not record

The accessibility service runs continuously (as a foreground service) and necessarily receives some events from the broader system in order to function. The following are **observed but never written to the database**:

| Signal | Why it is received | What happens with it |
|---|---|---|
| `TYPE_WINDOW_STATE_CHANGED` from all apps | No package filter is set in the XML config (required so that notifications from all apps are received — see §4.3 and DECISIONS.md D3) | Checked only to detect Instagram entering or leaving the foreground. Nothing is recorded for non-Instagram packages. |
| Notification events from all apps | Same as above — `TYPE_NOTIFICATION_STATE_CHANGED` cannot be filtered by package at the XML level | Only a boolean (`fromInstagram`) is written, and only during active sessions; out-of-session notifications are dropped entirely; notification title, body, and all other content fields are never accessed |

The ESM timer and service heartbeat coroutines run continuously but produce no output (no DB writes, no notifications) when Instagram is not in an active session.

**ESM (Experience Sampling Method) events:**

ESM fires only during active Instagram sessions. Each ping posts two notifications simultaneously (one per question). `SELF_REPORT_SHOWN` carries `session_id` from the active session. `SELF_REPORT_SUBMITTED` and `SELF_REPORT_DISMISSED` have `session_id = null` (handled by a broadcast receiver that has no session reference).

| Event type | What it captures | Payload fields |
|---|---|---|
| `SELF_REPORT_SHOWN` | ESM ping notification was posted | `esmPeriodId` (UUID), `questionType` (`liking_esm_val` or `affective_salience_esm_val`) |
| `SELF_REPORT_SUBMITTED` | Participant answered the ping | `esmPeriodId`, `questionType`, `response` (question-dependent — see below), `completedAtUtc` |
| `SELF_REPORT_DISMISSED` | Participant swiped the notification away | `esmPeriodId`, `questionType`, `completedAtUtc` |

**Response values by question:**
- `liking_esm_val`: `pretty_good`, `eh`, `it_sucked`
- `affective_salience_esm_val`: `yes`, `not_really`

`esmPeriodId` links the two SHOWN events from the same ping to their respective SUBMITTED/DISMISSED responses and allows slicing of behavioral events that occurred during the sampling window.

### 2.4 Device identifier

The export file includes a `device_id_hash` — a SHA-256 hash of the Android ID salted with a random per-install UUID. This allows the researcher to distinguish exports from different devices without storing any re-identifiable identifier. The salt (install UUID) is stored in SharedPreferences on-device and is never exported.

---

## 3. Data not collected

The following is explicitly excluded by the `PrivacyLayer` redaction system, which is the single write chokepoint for all database writes. Any field on the blacklist is silently dropped before it can reach the database, regardless of which module generated it.

**Never recorded under any circumstances:**

- Any text content — post captions, comments, DMs, search queries, usernames
- Content description strings from accessibility nodes (may echo post/profile text)
- Previous text field values (may appear in search or DM flows)
- Notification titles, bodies, or sender names — only the sending app's package name is recorded
- Screenshots, screen recordings, or pixel data of any kind
- Audio
- Precise or approximate location
- Contact list, call log, or SMS
- Account identifiers, usernames, or profile information of any kind
- The identity of which specific post, reel, or story the user viewed

**Structural exclusions:**

- No data is transmitted over a network. The app has no internet permission in the manifest and no HTTP client dependency.

---

## 4. Technical privacy controls

### 4.1 PrivacyLayer

All database writes in the entire app go through a single class (`PrivacyLayer.writeEvent()`). No module has direct DAO access. The layer applies two filters before writing:

1. **Blacklist** — keys in `PAYLOAD_BLACKLIST` are dropped from all event payloads regardless of event type. Current blacklist: `text`, `contentDescription`, `beforeText`.
2. **Allowlist** — `SELF_REPORT_SUBMITTED` payloads are filtered through an explicit allowlist (`SELF_REPORT_ALLOWLIST`). Only listed keys survive. This prevents any unexpected field from reaching the DB in self-report data.

### 4.2 POST_INTERACTION detection privacy

Toggle actions (like, save) are detected via the `isSelected` state on accessibility node IDs, not content. The dedup key is `"$screenContext:$action:${node.hashCode()}"` — the node's in-memory hash, which is unique per view instance and never contains user-identifiable information. No usernames, captions, or content are accessed. The payload contains only `action` and `screenContext`.

Comment section detection checks for the presence/absence of specific view IDs in the window root — no content description or text fields from those nodes are read or stored.

### 4.3 Notification logging

Notification events are captured via the Android accessibility framework's `TYPE_NOTIFICATION_STATE_CHANGED` event. Only a boolean (`fromInstagram`) is recorded — whether the notification came from `com.instagram.android` or not. The sender's package name, the notification's title, body, and all other content fields are never accessed or stored.

### 4.4 No network

`android.permission.INTERNET` is absent from the manifest. The app has no network client libraries and performs no outbound connections. This is verified by code review — there is no `OkHttp`, `Retrofit`, `Volley`, or equivalent dependency in the build file.

### 4.5 On-device storage only

All data is written to a local SQLite database using Android Room (`com.irin.flowlog.data`). The database is stored in the app's private data directory, inaccessible to other apps without root.

---

## 5. Data flow

```
Instagram / Android system
        │
        ▼
FlowLogAccessibilityService   ← raw AccessibilityEvents
        │
        ▼
EventDispatcher               ← translates to structured calls
        │
        ▼
PrivacyLayer                  ← redacts blacklisted fields
        │
        ▼
Room (SQLite, on-device)      ← final storage
        │
   (user action only)
        ▼
JsonExporter                  ← assembles export file
        │
        ▼
User shares/transfers file    ← USB, share sheet, etc.
```

ESM events bypass `EventDispatcher` and go directly from `EsmTrigger` / `EsmResponseReceiver` → `PrivacyLayer` → Room.

---

## 6. Data export

Export is triggered manually by the participant tapping "Share JSON" or "View JSON" in the app. No automatic or background export occurs.

The export is a single UTF-8 JSON file written to the app's external Documents directory (`Android/data/com.irin.flowlog/files/Documents/`). The filename includes a timestamp (`flowlog_export_<ms>.json`).

### Export file structure

```json
{
  "exportVersion": "0.2",
  "deviceIdHash": "sha256-<hex>",
  "exportedAtUtc": "2026-05-20T10:00:00.000Z",
  "appVersion": "1.0.0",
  "sessions": [
    {
      "sessionId": "<uuid>",
      "startedAtUtc": "...",
      "endedAtUtc": "...",
      "durationMs": 123456,
      "epochs": [
        {
          "esmPeriodId": "<uuid or null>",
          "likingResponse": "pretty_good",
          "affectiveSalienceResponse": "yes",
          "events": [ /* behavioral events that occurred during this epoch */ ]
        }
      ]
    }
  ],
  "unattachedEvents": [ /* screen events, notifications, ESM dismissed events */ ]
}
```

Each session is divided into **ESM epochs** — slices of behavioral events bracketed by completed ESM pings. An epoch closes when both questions for a given `esmPeriodId` are submitted; the boundary is the later of the two `completedAtUtc` timestamps. Any events after the last completed epoch (or the whole session if no ping was fully answered) become an unlabelled tail epoch with null `esmPeriodId` and null response fields. `SELF_REPORT_SUBMITTED` events are embedded as epoch metadata and excluded from `unattachedEvents`.

---

## 7. Data retention and deletion

- All data remains on the participant's device until they export it.
- The app provides a one-tap "Delete all data" function in the main screen that wipes both the `events` and `sessions` tables from the database.
- Uninstalling the app removes the private database. The external Documents export file persists until manually deleted.
- The researcher receives only files the participant explicitly shares. There is no server, no account, and no passive data collection pathway.

---

## 8. Permissions used

| Permission | Purpose |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | Required to receive accessibility events from Instagram |
| `POST_NOTIFICATIONS` | Required on Android 13+ to show ESM prompt notifications |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Required to keep the logging service running while Instagram is in use |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prompts user to exempt the app from battery restriction, preventing the OS from killing it during long sessions |
| `WRITE_EXTERNAL_STORAGE` | Required on Android 8 only for writing the export file; not needed on Android 9+ |

**Absent by design:** `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `READ_CONTACTS`, `READ_CALL_LOG`, `CAMERA`, `RECORD_AUDIO`.

---

## 9. Suggested participant information sheet (template)

> **Study title:** [Your thesis title]
>
> **What the app records while you use Instagram:**
> - How often and in which direction you scroll
> - When you tap the screen (not what you tap on)
> - How long you pause between interactions
> - When you like, unlike, save, or unsave a post or reel
> - When you open the comment section on a post (not the comments themselves)
> - When you share a post (recipient count only — not who you shared to)
> - How long you spend on each Reel or feed post (derived from event grouping — no content is identified)
> - How long your sessions last and when they start and end
> - When you receive a notification and whether it came from Instagram (no sender identity, no content)
> - When your screen turns on and off
>
> **Every 5 minutes during active Instagram use, two notifications will appear:**
> 1. "How was the last few minutes of scrolling?" (Pretty good / Eh / It sucked)
> 2. "Did anything catch your attention or feel strong, positively or negatively?" (Yesss / Not really)
>
> You can ignore either or swipe them away. Tapping a button gives you a 10-second window to undo before the response is saved.
>
> **What the app never records:**
> - What any post, video, or story contains
> - Any text you read or type
> - Who sent you notifications or what they said
> - Your location
> - Anything from apps other than Instagram (except which apps sent notifications)
>
> **Your data stays on your phone** until you choose to share the export file with the researcher. You can delete all recorded data at any time from the app's main screen. Uninstalling the app also removes the database.
>
> **Participation is voluntary.** You can stop at any time by disabling the FlowLog accessibility service in your phone's Settings → Accessibility.

---

## 10. Known limitations and open questions for ethics review

1. **Screen context inference** — the app infers whether the user is in Feed, Reels, Stories, etc. primarily from `viewIdResourceName` (Instagram-specific view IDs), falling back to class name and event text. Best-effort; may misclassify if Instagram renames internal IDs across updates. No content is captured in the process.
2. **Notification interrupts** — only a boolean is recorded per notification (`fromInstagram: true/false`). No sender identity is stored. This is sufficient to measure interrupt frequency and whether the interrupt came from Instagram itself, without revealing any other app the participant uses.
3. **ESM non-response** — a `SELF_REPORT_SHOWN` with no corresponding `SELF_REPORT_SUBMITTED` is itself a data point. Participants should be aware that ignoring a prompt is recorded.
4. **Accessibility service visibility** — Android displays FlowLog in the system Accessibility settings. Other people with access to the participant's phone can see it is installed.
5. **Reels repost locale limitation** — `repost_tapped` is detected via content description matching and only confirmed for English ("Repost") and French ("Republier"). Other locales will silently miss this event. Feed repost (`reposted`/`unreposted`) uses `isSelected` and is unaffected.
6. **Feed post dwell** — use `post_view_id` to group events on the same post, then compute dwell as `MAX(timestamp_mono) − MIN(timestamp_mono)` across that group. `PAUSE` events with `screenContext = "feed"` contribute to this span. The ID is assigned based on which post View is physically centered on screen (nearest center-Y), so partial wiggles and brief hesitations do not generate spurious new IDs.
7. **Stories produce no detectable accessibility events** — empirically confirmed: full logcat produced zero events of any kind (no tap, scroll, window-state, or content-changed) during story navigation. Story time therefore appears in the data as a `PAUSE` event with `screenContext=feed` and an elevated `durationMs`, indistinguishable from a long feed pause at the instrument level. The root cause was not investigated. Further work could attempt to identify a detection mechanism; for now this is a known gap. For analysis: anomalously long PAUSEs (e.g. >30 s) within an active session may indicate story viewing time and can be flagged or excluded.
