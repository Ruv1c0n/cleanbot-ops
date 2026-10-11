package com.cleanbot.app.data.repository

import com.cleanbot.app.data.model.CommandResponse
import com.cleanbot.app.data.model.LogEntry
import com.cleanbot.app.data.model.Position
import com.cleanbot.app.data.model.RobotSettings
import com.cleanbot.app.data.model.Schedule
import com.cleanbot.app.data.model.Session
import com.cleanbot.app.data.model.Telemetry
import com.cleanbot.app.data.remote.RobotApi
import com.cleanbot.app.data.remote.TelemetrySocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

data class RobotUiState(
    val id: String,
    val name: String,
    val state: String = "IDLE",
    val battery: Int = 0,
    val progress: Double = 0.0,
    val position: Position = Position(0, 0),
    val error: String? = null
)

/**
 * Единый источник живых данных: сводки роботов (REST) обновляются телеметрией с WebSocket.
 * REST-запросы за расписанием/сессиями/логами/настройками — по требованию конкретного экрана.
 */
class RobotRepository(
    private val api: RobotApi = RobotApi(),
    private val socket: TelemetrySocket = TelemetrySocket()
) {
    private val _robots = MutableStateFlow<Map<String, RobotUiState>>(emptyMap())
    val robots: StateFlow<Map<String, RobotUiState>> = _robots.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTING)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    suspend fun refreshFleet() {
        runCatching { api.listRobots() }.onSuccess { summaries ->
            _robots.update { current ->
                val merged = current.toMutableMap()
                summaries.forEach { s ->
                    val existing = merged[s.id] ?: RobotUiState(id = s.id, name = s.name)
                    merged[s.id] = existing.copy(name = s.name, state = s.state, battery = s.battery, error = s.error)
                }
                merged
            }
        }
    }

    fun startTelemetryLoop(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                _connectionState.value = ConnectionState.CONNECTING
                runCatching {
                    socket.stream().collect { batch ->
                        _connectionState.value = ConnectionState.CONNECTED
                        applyTelemetry(batch)
                    }
                }
                _connectionState.value = ConnectionState.DISCONNECTED
                delay(2000)
            }
        }
    }

    private fun applyTelemetry(batch: List<Telemetry>) {
        _robots.update { current ->
            val merged = current.toMutableMap()
            batch.forEach { t ->
                val existing = merged[t.robotId] ?: RobotUiState(id = t.robotId, name = t.robotId)
                merged[t.robotId] = existing.copy(
                    state = t.state,
                    battery = t.battery,
                    progress = t.progress,
                    position = t.position,
                    error = t.error
                )
            }
            merged
        }
    }

    suspend fun sendCommand(robotId: String, command: String): CommandResponse =
        api.sendCommand(robotId, command, UUID.randomUUID().toString())

    suspend fun schedule(robotId: String): List<Schedule> = api.getSchedule(robotId)
    suspend fun updateSchedule(robotId: String, entries: List<Schedule>) = api.updateSchedule(robotId, entries)
    suspend fun sessions(robotId: String): List<Session> = api.getSessions(robotId)
    suspend fun logs(robotId: String): List<LogEntry> = api.getLogs(robotId)
    suspend fun settings(robotId: String): RobotSettings = api.getSettings(robotId)
    suspend fun updateSettings(settings: RobotSettings) = api.updateSettings(settings)
}
