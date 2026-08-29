package com.linger.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// One row per behavioral event. Room turns this data class into a SQLite table at compile time.
// @Entity tells Room this is a table; @PrimaryKey marks the unique row identifier.
// See spec §3.1 for the canonical schema this mirrors.
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val eventId: String,       // UUID string, e.g. "550e8400-e29b-41d4-a716-446655440000"
    val sessionId: String?,                // UUID; null for SCREEN_ON/OFF only
    val timestampUtc: String,             // ISO 8601 with ms, e.g. "2026-04-19T14:32:00.123Z"
    val timestampMono: Long,              // System.nanoTime() — monotonic clock, immune to wall-clock changes
    val eventType: String,                // EventType.name(), e.g. "SCROLL"
    val sourcePackage: String,            // e.g. "com.instagram.android"
    val payload: String,                  // JSON object, shape depends on eventType. See spec §3.2.
    val postViewId: String?,              // synthetic UUID grouping events on the same post/reel; null outside a post context
)
