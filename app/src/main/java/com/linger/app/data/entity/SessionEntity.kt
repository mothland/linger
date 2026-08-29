package com.linger.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// One row per Instagram session. endedAtUtc and durationMs are null while the session is still open.
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val startedAtUtc: String,
    val endedAtUtc: String?,
    val durationMs: Long?,
)
