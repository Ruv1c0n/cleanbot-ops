package com.cleanbot.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface CleanBotDao {

    @Upsert
    suspend fun upsertTelemetry(entries: List<TelemetryEntity>)

    @Upsert
    suspend fun upsertSessions(sessions: List<SessionEntity>)

    @Query(
        "SELECT * FROM telemetry WHERE robotId = :robotId AND timestamp BETWEEN :fromMillis AND :toMillis " +
            "ORDER BY timestamp ASC"
    )
    suspend fun telemetry(robotId: String, fromMillis: Long, toMillis: Long): List<TelemetryEntity>

    @Query("SELECT * FROM sessions WHERE robotId = :robotId ORDER BY startedAt DESC")
    suspend fun sessions(robotId: String): List<SessionEntity>

    @Query("DELETE FROM telemetry WHERE timestamp < :cutoffMillis")
    suspend fun deleteTelemetryBefore(cutoffMillis: Long): Int

    @Query("DELETE FROM sessions WHERE startedAt < :cutoffIso")
    suspend fun deleteSessionsBefore(cutoffIso: String): Int
}
