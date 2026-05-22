# Behavioral Telemetry Logger — Android App Spec

**Project codename:** TBD (working name: `flowlog`)
**Author:** Irin (MSc thesis, ESILV Paris)
**Target build environment:** Claude Code
**Status:** v0.1 spec, MVP scope

---

## 1. Context (read this first)

This is the data collection instrument for an MSc thesis on behavioral telemetry and social media. The research framework mirrors one previously scoped for competitive multiplayer games: raw behavioral event logs are paired with self-reported psychological state, then mined for sequential patterns that correlate with **flow** and **Reward Prediction Error (RPE)**.

**Important glossary for the builder:**
- **RPE** in this project = **Reward Prediction Error** (Schultz, 1997 — neuroscience / reinforcement learning construct: the signed difference between expected and actual reward). **It is NOT Rate of Perceived Exertion.** If you see RPE anywhere in this spec or future prompts, it means Reward Prediction Error.
- **Flow** = Csikszentmihalyi-style flow state, self-reported via short scale at session end.
- **ESM** = Experience Sampling Method — randomly timed prompts throughout the day asking the user to report their current state. Out of scope for MVP but architecture must not block adding it later.
- **Session** = a contiguous period the user spends inside the target app (Instagram for MVP). A session ends when the user leaves the app for more than N seconds (configurable, default 30s) OR when the screen locks.

**Architectural ancestor:** InfiniteScape (Meinhardt et al., 2025) — we are borrowing the AccessibilityService-based event logging approach, but **stripping out all intervention logic**. This app observes only. It never nudges, interrupts, blocks, or modifies the user's experience.

---

## 2. MVP scope

### 2.1 Target apps for logging
- **Instagram only** (package: `com.instagram.android`)
  - Both Reels AND feed/post content within the app. No distinction needed at the capture layer — we log everything that happens inside the Instagram process and tag it with the current screen context where inferable.
- **Notifications from any app**: during an active Instagram session, log a boolean indicating whether the notification came from Instagram. No content, no title, no body, no sender identity — not even the sending app's package name. Notifications received outside a session are not recorded. This is a hard privacy constraint — see §6.

### 2.2 Self-report
- **MVP: Post-session prompt only.** When a session ends (see §2.1 definition), trigger a local notification prompting the user to answer a short form. If they dismiss or ignore it, log the dismissal and move on — do not re-prompt.
- **Implemented (v0.5.0): ESM pings.** Two parallel ESM notifications fire every 5 minutes during active Instagram sessions. Inline quick-tap responses with a 10 s undo window — no form opens. The ESM timer resets only on true session end (30 s grace period or screen off).
- **Form contents (MVP):**
  - Flow state — short scale, 5 Likert items, TBD wording (placeholder items OK, I'll finalize)
  - Perceived enjoyment / valence — single 1–7 item
  - Perceived time distortion — single 1–7 item ("Did time pass faster or slower than expected?")
  - Free-text box, optional, capped at 500 chars
  - Timestamp of form completion (auto)

### 2.3 Data destination
- **MVP: local JSON export.** All events + self-report responses written to a local SQLite database. Manual export action in the app UI produces a single JSON file written to shared storage (Downloads/ or app-specific external dir) that I can pull via USB or share sheet.
- No cloud sync, no backend, no auth. Later I'll decide whether to pipe this into a cloud store via a separate data pipeline — the export format should be clean enough that I don't have to reshape it.

---

## 3. Events to capture

Use `AccessibilityService` with `TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOW_CONTENT_CHANGED`, `TYPE_VIEW_SCROLLED`, `TYPE_VIEW_CLICKED`, and `TYPE_NOTIFICATION_STATE_CHANGED`. Filter by package for Instagram events; capture notification events globally but discard content.

### 3.1 Event schema (canonical)
Every event row has:
```
event_id         UUID
session_id       UUID  (nullable for SCREEN_ON/SCREEN_OFF and for SELF_REPORT_SUBMITTED/SELF_REPORT_DISMISSED; set for all other event types)
post_view_id     UUID  (nullable — groups events on the same post/reel; see EVENTS.md for full semantics)
timestamp_utc    ISO 8601 with ms precision
timestamp_mono   monotonic nanos (for interval math resilient to clock changes)
event_type       enum (see below)
source_package   string
payload          JSON blob (type-specific)
```

### 3.2 Event types (MVP)
- `session_start` — Instagram entered foreground
- `session_end` — Instagram backgrounded > threshold, or screen locked
- `scroll` — payload: direction (up/down), delta (if available), current screen context hint (feed/reels/profile/story/dm/other — best-effort from view hierarchy, allow "unknown")
- `tap` — payload: coarse element type if inferable (button/image/video/text/unknown), screen context hint
- `pause` — payload: duration_ms since last event (fire when inactivity > 1500ms inside app); inherits current `post_view_id`
- `app_switch` — user backgrounds Instagram without ending session (e.g., quick tab-out); no payload (destination not recorded)
- `notification_received` — only fires during an active session. payload: `fromInstagram` (boolean). No sender identity or other fields.
- `screen_on` / `screen_off` — only written when adjacent to an active session (see §4 for details)
- `self_report_shown`
- `self_report_submitted` — payload: form responses
- `self_report_dismissed`

### 3.3 What NOT to capture
- No text content of any kind (no captions, comments, DMs, search queries, usernames)
- No screenshots, no screen recording, no pixel data
- No audio
- No notification titles, bodies, or sender names
- No account identifiers
- No precise location
- No contact list, no call log, no SMS

---

## 4. App structure

### 4.1 Modules
- `service/` — AccessibilityService implementation, event dispatcher
- `data/` — Room (SQLite) DB, DAOs, entities matching §3.1
- `session/` — Session lifecycle manager (start/end detection, threshold config)
- `report/` — Self-report form UI + scheduler interface (MVP: post-session trigger only; interface must support pluggable triggers)
- `export/` — JSON serializer, file writer, share intent
- `ui/` — Minimal settings + status screen: toggle logging on/off, see event count, trigger export, clear data, view privacy summary
- `privacy/` — Redaction layer (single chokepoint that strips anything not whitelisted before write)

### 4.2 Key architectural rules
- **All writes go through the privacy layer.** No module writes directly to the DB. This is non-negotiable.
- **Self-report trigger is an interface**, not a hardcoded hook. MVP implementation = `PostSessionTrigger`. Later = `ESMTrigger`. Don't couple the form to either.
- **No network permissions in the manifest.** MVP has zero network use. If a future version adds sync, it's a deliberate manifest change.
- **Foreground service with persistent notification** for the logger (Android requirement for long-running AccessibilityService visibility and user trust).
- **User-facing kill switch:** one toggle in-app fully stops logging immediately.

### 4.3 Tech stack
- **Language:** Kotlin
- **Min SDK:** 28 (Android 8.1) — raised from 26 because `getScrollDeltaX/Y` require API 28; covers ~92% of devices
- **Target SDK:** latest stable
- **DB:** Room
- **UI:** Jetpack Compose
- **Build:** Gradle Kotlin DSL
- **No third-party analytics, no crash reporting SDKs** (privacy)

---

## 5. JSON export format

Single file, UTF-8, newline at end. Structure:

```json
{
  "export_version": "0.1",
  "device_id_hash": "sha256-of-androidId-salted-with-install-uuid",
  "exported_at_utc": "2026-04-19T14:32:00.000Z",
  "app_version": "0.1.0",
  "sessions": [
    {
      "session_id": "uuid",
      "started_at_utc": "...",
      "ended_at_utc": "...",
      "duration_ms": 12345,
      "events": [ /* event objects per §3.1 */ ],
      "self_report": { /* or null if dismissed/none */ }
    }
  ],
  "unattached_events": [ /* SCREEN_ON/SCREEN_OFF events adjacent to sessions; notifications are always session-scoped and appear in the session's events array */ ]
}
```

`device_id_hash` exists so I can tell multi-device exports apart during analysis without storing anything re-identifiable.

---

## 6. Privacy & ethics constraints (hard)

- Nothing in §3.3 ever enters the DB or any log file, not even transiently.
- The privacy layer is the single write chokepoint; it whitelists fields and drops everything else.
- No network code in MVP. No HTTP client dependency added. Not even for crash reports.
- The app shows a plain-language privacy summary on first launch and in settings, listing exactly what is and isn't captured.
- All data stays on-device until the user manually exports.
- Data export is a user-initiated action only — never automatic.
- One-tap "delete all data" in settings that wipes the DB.
- The AccessibilityService description string shown in system settings must accurately describe what the service does.

This app will eventually be used in a study with informed consent and ethics review. The spec above is the technical floor; actual deployment will add consent flow, participant ID binding, and study-specific config. MVP doesn't need those but shouldn't make them painful to add.

---

## 7. Out of scope for MVP (explicitly)

- Any app other than Instagram
- Any notification content
- ESM scheduling *(implemented in v0.3.0 / v0.5.0)*
- `media_dwell` event type *(removed in v0.9.0 — replaced by `post_view_id` grouping; dwell = MAX−MIN of `timestamp_mono` per group)*
- Cloud sync / backend
- Authentication
- Multi-user / participant ID management
- Intervention or feedback features of any kind
- iOS

---

## 8. Deliverables

1. A working Android app meeting §2–§6
2. README with:
   - Setup steps (enable AccessibilityService, grant notification-listener permission)
   - How to export data
   - What each event type means (link back to this spec)
3. A `sample_export.json` with fake data demonstrating the schema
4. Unit tests on: the privacy redaction layer, the session lifecycle state machine, the JSON serializer
5. One integration test that replays a scripted accessibility event stream and asserts the resulting DB rows + JSON export

---

## 9. Notes for Claude Code

- I have zero prior Android/Kotlin experience. Comment generously. Explain non-obvious Android-isms (manifest intents, service lifecycle, permission prompts) as you go.
- Prefer boring, well-documented choices over clever ones. This is a research instrument; it needs to be auditable, not elegant.
- If you hit an ambiguity the spec doesn't resolve, make the most privacy-preserving choice and flag it in a `DECISIONS.md` file for me to review.
- Start by scaffolding the modules in §4.1 with stub implementations and tests, so I can see the shape before you fill it in.