package com.cleanbot.app.data.local

import androidx.room.Entity

/**
 * Кэшированная точка телеметрии.
 * PK составной (robotId, timestamp) — повторная запись того же момента не создаёт дублей.
 * timestamp хранится как epoch millis: ISO-строки сервера имеют переменную точность долей секунды,
 * числовой формат даёт корректные запросы «за период» и очистку по возрасту.
 */
@Entity(tableName = "telemetry", primaryKeys = ["robotId", "timestamp"])
data class TelemetryEntity(
    val robotId: String,
    val timestamp: Long,
    val state: String,
    val battery: Int,
    val progress: Double,
    val posX: Int,
    val posY: Int,
    val error: String?
)
