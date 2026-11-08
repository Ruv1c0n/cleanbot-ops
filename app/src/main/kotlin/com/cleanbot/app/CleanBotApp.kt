package com.cleanbot.app

import android.app.Application
import android.util.Log
import com.cleanbot.app.di.AppContainer
import com.cleanbot.app.notification.NotificationChannels
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

class CleanBotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
        NotificationChannels.ensure(this)
        // Политика очистки кэша: телеметрия старше 7 дней удаляется при старте приложения.
        val cutoff = Instant.now().minus(TELEMETRY_RETENTION_DAYS, ChronoUnit.DAYS).toEpochMilli()
        AppContainer.applicationScope.launch {
            runCatching { AppContainer.database.dao().deleteTelemetryBefore(cutoff) }
                .onFailure { Log.w(TAG, "Не удалось очистить старую телеметрию", it) }
        }
    }

    private companion object {
        const val TAG = "CleanBotApp"
        const val TELEMETRY_RETENTION_DAYS = 7L
    }
}
