package com.cleanbot.app.di

import android.content.Context
import androidx.room.Room
import com.cleanbot.app.data.local.CleanBotDatabase
import com.cleanbot.app.data.remote.ServerConfig
import com.cleanbot.app.data.repository.RobotRepository
import com.cleanbot.app.notification.RobotErrorNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Минимальный ручной DI: один общий репозиторий и база кэша на всё приложение. */
object AppContainer {

    lateinit var database: CleanBotDatabase
        private set

    /** Application-контекст для уведомлений (не activity). */
    lateinit var appContext: Context
        private set

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var initialized = false

    private const val PREFS = "cleanbot"
    private const val KEY_HOST_PORT = "host_port"

    /** Вызывается из [com.cleanbot.app.CleanBotApp.onCreate] до первого доступа к UI. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        database = Room.databaseBuilder(
            context.applicationContext,
            CleanBotDatabase::class.java,
            CleanBotDatabase.NAME
        ).build()
        // Адрес сервера переживает перезапуск (сохраняется при успешном PUT настроек).
        val saved = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOST_PORT, null)
        if (!saved.isNullOrBlank()) ServerConfig.hostPort.value = saved
    }

    /** Сохраняет адрес сервера между запусками; вызывается при успешном сохранении настроек. */
    fun saveServerAddress(hostPort: String) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HOST_PORT, hostPort).apply()
    }

    val robotRepository: RobotRepository by lazy {
        RobotRepository(db = database, errorNotifier = RobotErrorNotifier(appContext))
    }

    /** In-memory настройки приложения (осознанно без SharedPreferences, живут до перезапуска процесса). */
    var showPlanPreviewEveryTime: Boolean = true
}
