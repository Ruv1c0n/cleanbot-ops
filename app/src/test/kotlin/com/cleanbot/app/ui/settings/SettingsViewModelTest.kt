package com.cleanbot.app.ui.settings

import com.cleanbot.app.MainDispatcherRule
import com.cleanbot.app.data.remote.RobotApi
import com.cleanbot.app.data.remote.ServerConfig
import com.cleanbot.app.data.repository.RobotRepository
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Юнит-тесты валидации и сохранения адреса сервера в настройках приложения.
 * Сеть и Android-фреймворк не нужны: персист адреса инжектится колбэком,
 * ServerConfig — простой object, репозиторий — реальный без запущенного цикла.
 */
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var persisted: String? = null

    private fun vm(): SettingsViewModel {
        persisted = null
        return SettingsViewModel(
            onPersist = { persisted = it },
            repository = RobotRepository(api = RobotApi(), db = null)
        )
    }

    @Test
    fun `normalize accepts valid host port`() {
        val viewModel = vm()
        assertEquals("192.168.1.5:8081", viewModel.normalizeHostPort("192.168.1.5:8081"))
        assertEquals("10.0.2.2:8080", viewModel.normalizeHostPort(" 10.0.2.2:8080 "))
    }

    @Test
    fun `normalize strips scheme prefix and trailing slash`() {
        val viewModel = vm()
        assertEquals("10.0.2.2:8081", viewModel.normalizeHostPort("ws://10.0.2.2:8081"))
        assertEquals("host:8080", viewModel.normalizeHostPort("http://host:8080/"))
    }

    @Test
    fun `normalize rejects empty or missing host`() {
        val viewModel = vm()
        assertNull(viewModel.normalizeHostPort(""))
        assertNull(viewModel.normalizeHostPort("   "))
        assertNull(viewModel.normalizeHostPort(":8080"))
        assertNull(viewModel.normalizeHostPort("ws://:8080"))
    }

    @Test
    fun `normalize rejects bad port`() {
        val viewModel = vm()
        assertNull(viewModel.normalizeHostPort("host:0"))
        assertNull(viewModel.normalizeHostPort("host:70000"))
        assertNull(viewModel.normalizeHostPort("host:abc"))
        assertNull(viewModel.normalizeHostPort("host:"))
        assertNull(viewModel.normalizeHostPort("host"))
    }

    @Test
    fun `save strips scheme persists and reconnects on changed address`() = runTest {
        ServerConfig.hostPort.value = "10.0.2.2:8080"
        val viewModel = vm()

        viewModel.save("ws://10.0.2.2:8081/")
        advanceUntilIdle()

        assertEquals("10.0.2.2:8081", persisted)
        assertEquals("10.0.2.2:8081", ServerConfig.hostPort.value)
        assertEquals("Сохранено, переподключение…", viewModel.message.value)
    }

    @Test
    fun `save invalid address shows error and does not persist`() = runTest {
        val viewModel = vm()

        viewModel.save("no-port-here")
        advanceUntilIdle()

        assertNull(persisted)
        assertEquals("Неверный адрес", viewModel.message.value)
    }

    @Test
    fun `save same address while disconnected still saves and reconnects`() = runTest {
        ServerConfig.hostPort.value = "10.0.2.2:8080"
        val viewModel = vm()

        viewModel.save("10.0.2.2:8080")
        advanceUntilIdle()

        assertEquals("10.0.2.2:8080", persisted)
        assertEquals("Сохранено, переподключение…", viewModel.message.value)
    }

    @Test
    fun `reconnect shows message without persisting`() = runTest {
        ServerConfig.hostPort.value = "10.0.2.2:8080"
        val viewModel = vm()

        viewModel.reconnect()
        advanceUntilIdle()

        assertNull(persisted)
        assertEquals("Переподключение…", viewModel.message.value)
    }
}
