package com.cleanbot.app.ui.settings

import androidx.lifecycle.ViewModel
import com.cleanbot.app.data.remote.ServerConfig
import com.cleanbot.app.data.repository.ConnectionState
import com.cleanbot.app.data.repository.RobotRepository
import com.cleanbot.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Экран настроек приложения: адрес сервера редактируется всегда, в т.ч. офлайн
 * (в отличие от инженерной вкладки робота, чьё сохранение зависело от GET /settings).
 */
class SettingsViewModel(
    /** Персист адреса (требует Context) — инжектится колбэком для тестируемости. */
    private val onPersist: (String) -> Unit = AppContainer::saveServerAddress,
    private val repository: RobotRepository = AppContainer.robotRepository
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = repository.connectionState

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Нормализация адреса: срезает необязательный префикс схемы (ws://, http:// —
     * инженерная вкладка принимала ввод вида "ws://host"), проверяет host и порт.
     * null — адрес неверный.
     */
    fun normalizeHostPort(raw: String): String? {
        val cleaned = raw.trim()
            .removePrefix("ws://")
            .removePrefix("wss://")
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
        if (cleaned.isBlank()) return null
        val colon = cleaned.lastIndexOf(':')
        if (colon <= 0 || colon == cleaned.length - 1) return null
        val host = cleaned.substring(0, colon)
        val port = cleaned.substring(colon + 1).toIntOrNull() ?: return null
        if (host.isBlank()) return null
        if (port !in 1..65535) return null
        return "$host:$port"
    }

    /**
     * Сохранение адреса и переподключение. Пишем и персистим всегда (когда адрес
     * изменился ИЛИ связи нет — второй случай покрывает правку с последующим
     * сохранением офлайн). reconnect() — только при НЕизменном адресе: смену адреса
     * цикл телеметрии перезапускает сам через combine(hostPort, reconnectTick) в
     * collectLatest, а двойной рестарт тиком отменил бы первый на середине хендшейка.
     */
    fun save(raw: String) {
        val hostPort = normalizeHostPort(raw) ?: run {
            _message.value = "Неверный адрес"
            return
        }
        val changed = hostPort != ServerConfig.hostPort.value
        if (!changed && connectionState.value == ConnectionState.CONNECTED) {
            _message.value = "Адрес не изменился"
            return
        }
        ServerConfig.hostPort.value = hostPort
        onPersist(hostPort)
        if (changed) {
            // Смена адреса сама перезапускает цикл через combine(hostPort, reconnectTick)
            // в collectLatest — двойной рестарт тиком отменил бы первый на середине
            // хендшейка, поэтому reconnect() здесь не вызываем.
        } else {
            // Адрес не изменился: одинаковое значение hostPort в StateFlow не
            // эммитится само — перезапускаем цикл явным тиком.
            repository.reconnect()
        }
        _message.value = "Сохранено, переподключение…"
    }

    /** «Переподключиться» без смены адреса: перезапуск цикла телеметрии. */
    fun reconnect() {
        repository.reconnect()
        _message.value = "Переподключение…"
    }

    fun clearMessage() {
        _message.value = null
    }
}
