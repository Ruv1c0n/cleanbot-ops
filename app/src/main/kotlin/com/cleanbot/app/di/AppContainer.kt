package com.cleanbot.app.di

import com.cleanbot.app.data.repository.RobotRepository

/** Минимальный ручной DI: один общий репозиторий на всё приложение. */
object AppContainer {
    val robotRepository = RobotRepository()

    /** In-memory настройки приложения (осознанно без SharedPreferences, живут до перезапуска процесса). */
    var showPlanPreviewEveryTime: Boolean = true
}
