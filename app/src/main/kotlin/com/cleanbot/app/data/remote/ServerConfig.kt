package com.cleanbot.app.data.remote

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * По умолчанию "10.0.2.2:8080" — loopback хост-машины из Android-эмулятора (см. README).
 * На физическом устройстве нужно поменять на IP компьютера в той же сети — через
 * инженерное меню (вкладка "Инженерное" на экране робота).
 */
object ServerConfig {
    val hostPort = MutableStateFlow("10.0.2.2:8080")

    val httpBaseUrl: String get() = "http://${hostPort.value}"
    val wsUrl: String get() = "ws://${hostPort.value}/telemetry"
}
