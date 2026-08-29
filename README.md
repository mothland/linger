# Linger

Android behavioral telemetry logger for MSc thesis research on reward satiation and social media engagement (ESILV Paris). 

Since I lack Kotlin experience, this has been vibe coded in good part, and solo testing has been done and is still ongoing. Android accessibility events for instagram tend to be a bit finnicky and hard to catch (as well as poorly documented), so this was figured out through a lot of trial and error.

**Target:** Instagram on Android 8.1+

---

## What it does

Linger runs silently in the background and **locally** records **anonymous** behavioral events (taps, scrolls, pauses, session timing, likes/saves/reposts/shares, Reel dwell) while you use Instagram. Every few minutes during an active Instagram session, it sends two short ESM ping notifications asking how the scrolling felt. All data stays on your device; you export it manually as a JSON file.

**What it records:** tap/scroll/pause/post-interaction events inside Instagram, session start/end times, Reel dwell time, screen on/off events when adjacent to a session, notification interrupt signals during sessions (no content, no sender identity).

**What it never records:** any text, captions, usernames, notification titles/bodies, images, audio, or location.

---

## Setup

### 1. Build and install

Open the project in **Android Studio** (Electric Eel or newer). The first open will download Gradle and dependencies automatically.

```
File → Open → select the thesis-prototype/ folder
```

Connect your Android phone via USB, enable USB debugging (Settings → Developer Options → USB Debugging), then:

```
Run → Run 'app'
```

### 2. Enable the Accessibility Service

Linger cannot log anything until you enable its Accessibility Service in system settings. Open Linger, tap **"Open Accessibility Settings"**, find **Linger** in the list, and toggle it on. Read the description that Android shows you — it accurately describes what the service does.

### 3. Enable Notification Access

To log which apps send you notifications (package names only), grant notification access:

Settings → Notifications → Notification access → Linger → Allow

### 4. Allow notifications from Linger

On Android 13+, grant the POST_NOTIFICATIONS permission when the app prompts you. This is needed for the ESM ping notifications.

---

## How to export data

1. Open Linger.
2. Tap **"Share JSON"** to share the file via email, Google Drive, ADB pull, etc. — or tap **"View JSON"** to open it in a viewer app.
   
---

## Event types

| Event type | When it fires | Key payload fields |
|---|---|---|
| `SESSION_START` | Instagram enters foreground | — |
| `SESSION_END` | Instagram backgrounded > 30 s, or screen locked | — |
| `SCROLL` | User scrolls inside Instagram | `direction`, `delta`, `screenContext` |
| `TAP` | User taps inside Instagram | `elementType`, `screenContext` |
| `PAUSE` | No Instagram event for > 1.5 s — inherits current `post_view_id`; dwell = time span of events sharing that ID | `durationMs`, `screenContext` |
| `POST_INTERACTION` | Like/unlike, save/unsave, repost/unrepost (feed), repost tapped (Reels — English/French only; other locales silently missed), share tapped, share sheet opened, share sent, comment section opened | `action`, `screenContext` |
| `APP_SWITCH` | Instagram moved to background mid-session — clears `post_view_id` | — |
| `NOTIFICATION_RECEIVED` | Any app posts a notification during an active session (dropped outside sessions) | `fromInstagram` (bool) |
| `SCREEN_ON` | Screen wakes while a session is active (in practice never fires — sessions always end on SCREEN_OFF) | — |
| `SCREEN_OFF` | Screen locks while a session is active — terminates the session immediately (dropped if no session) | — |
| `SELF_REPORT_SHOWN` | ESM ping notification posted (two per ping, one per question) | `esmPeriodId`, `questionType` |
| `SELF_REPORT_SUBMITTED` | User tapped a response and the 10 s undo window passed | `esmPeriodId`, `questionType`, `response`, `completedAtUtc` |
| `SELF_REPORT_DISMISSED` | User swiped the ESM notification away | `esmPeriodId`, `questionType`, `completedAtUtc` |

`screenContext` is a best-effort label: `feed`, `reels`, `comments`, `story`, `share`, `dm`, `profile`, or `unknown` — inferred from `viewIdResourceName` first, class/text fallback. See `EVENTS.md` for full payload details.

---

## Project structure

```
app/src/main/java/com/user/Linger/
├── service/      AccessibilityService + EventDispatcher
├── data/         Room DB, DAOs, entities
├── session/      Session lifecycle state machine
├── report/       ESM trigger, response receiver
├── export/       JSON serializer + file writer
├── ui/           Status/settings screen
├── privacy/      Redaction layer (single write chokepoint)
└── dev/          Dev-mode test runner (debug builds only)
```

---

## Privacy notes

- No internet permission is declared in the manifest. The app cannot make network requests. There is no cloud database: everything is stored directly on your device.
- The privacy layer (`PrivacyLayer.kt`) is the only path to the database. Every write goes through it. Every field not on the explicit whitelist is silently dropped before the write.
