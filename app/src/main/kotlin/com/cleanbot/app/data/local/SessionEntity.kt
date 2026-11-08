package com.cleanbot.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Кэшированная сессия уборки. PK startedAt (как на сервере) — повторный кэш ответа сети не дублирует записи.
 * durationMinutes при чтении из кэша вычисляется из startedAt/endedAt.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val startedAt: String,
    val robotId: String,
    val endedAt: String,
    val area: Double,
    val mode: String,
    val aborted: Boolean
)
