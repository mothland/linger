package com.linger.app.data.entity

// Canonical set of event types. Stored as the enum name (string) in the DB and JSON export.
// See spec §3.2 for definitions of each type.
enum class EventType {
    SESSION_START,
    SESSION_END,
    SCROLL,
    TAP,
    PAUSE,
    APP_SWITCH,
    NOTIFICATION_RECEIVED,
    SCREEN_ON,
    SCREEN_OFF,
    SELF_REPORT_SHOWN,
    SELF_REPORT_SUBMITTED,
    SELF_REPORT_DISMISSED,
    POST_INTERACTION,
}
