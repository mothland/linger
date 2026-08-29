package com.irin.flowlog.data.dao

import androidx.room.*
import com.irin.flowlog.data.entity.EventEntity
import kotlinx.coroutines.flow.Flow

// DAO = Data Access Object. Room generates the SQL implementation of these methods at compile time.
// All methods are suspend functions — they must be called from a coroutine, never from the main thread.
@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity)

    @Query("SELECT * FROM events WHERE sessionId = :sessionId ORDER BY timestampMono ASC")
    suspend fun getEventsForSession(sessionId: String): List<EventEntity>

    // Unattached events are things like screen_on/off and notifications that happened outside any session.
    @Query("SELECT * FROM events WHERE sessionId IS NULL ORDER BY timestampMono ASC")
    suspend fun getUnattachedEvents(): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun getTotalEventCount(): Int

    // Flow version — Room emits a new value whenever the events table changes.
    @Query("SELECT COUNT(*) FROM events")
    fun observeTotalEventCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM events WHERE sessionId IN (SELECT sessionId FROM sessions WHERE endedAtUtc IS NULL)")
    fun observeCurrentSessionEventCount(): Flow<Int>

    // Fetches SELF_REPORT_SUBMITTED events whose esmPeriodId matches any entry in the list.
    // Used by JsonExporter to pull responses for periods that fired during a given session.
    // json_extract is available from SQLite 3.9 / Android API 26+.
    @Query("""
        SELECT * FROM events
        WHERE eventType = 'SELF_REPORT_SUBMITTED'
        AND json_extract(payload, '${'$'}.esmPeriodId') IN (:periodIds)
        ORDER BY timestampMono ASC
    """)
    suspend fun getSubmittedEventsForPeriods(periodIds: List<String>): List<EventEntity>

    @Query("DELETE FROM events")
    suspend fun deleteAll()
}
