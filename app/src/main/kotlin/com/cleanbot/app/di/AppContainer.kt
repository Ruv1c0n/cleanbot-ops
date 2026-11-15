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
    private const val KEY_ONBOARDING_DONE = "onboarding_done"

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
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_HOST_PORT, null)
        if (!saved.isNullOrBlank()) ServerConfig.hostPort.value = saved
        // Онбординг показывается только один раз — до первого завершения.
        onboardingDone = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
    }

    /** Сохраняет адрес сервера между запусками; вызывается при успешном сохранении настроек. */
    fun saveServerAddress(hostPort: String) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HOST_PORT, hostPort).apply()
    }

    /** Признак завершённого онбординга; читается в [init] до первой навигации. */
    var onboardingDone: Boolean = false
        private set

    /** Фиксирует завершение онбординга: больше не показывается ни при каких перезапусках. */
    fun markOnboardingDone() {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
        onboardingDone = true
    }

    val robotRepository: RobotRepository by lazy {
        RobotRepository(db = database, errorNotifier = RobotErrorNotifier(appContext))
    }

    /** In-memory настройки приложения (осознанно без SharedPreferences, живут до перезапуска процесса). */
    var showPlanPreviewEveryTime: Boolean = true
}
