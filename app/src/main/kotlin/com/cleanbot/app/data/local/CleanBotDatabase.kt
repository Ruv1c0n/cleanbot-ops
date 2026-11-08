package com.cleanbot.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TelemetryEntity::class, SessionEntity::class],
    version = 1,
    exportSchema = true
)
abstract class CleanBotDatabase : RoomDatabase() {
    abstract fun dao(): CleanBotDao

    companion object {
        const val NAME = "cleanbot.db"
    }
}
