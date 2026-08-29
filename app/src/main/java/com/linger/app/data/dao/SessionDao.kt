package com.irin.flowlog.data.dao

import androidx.room.*
import com.irin.flowlog.data.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startedAtUtc ASC")
    suspend fun getAllSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE endedAtUtc IS NULL LIMIT 1")
    suspend fun getOpenSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Query("SELECT COUNT(*) FROM sessions")
    fun observeTotalSessionCount(): Flow<Int>

    // todayStart should be an ISO 8601 UTC string, e.g. "2026-04-21T00:00:00Z".
    // String comparison works here because ISO 8601 sorts lexicographically.
    @Query("SELECT COUNT(*) FROM sessions WHERE startedAtUtc >= :todayStart")
    fun observeTodaySessionCount(todayStart: String): Flow<Int>

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}
