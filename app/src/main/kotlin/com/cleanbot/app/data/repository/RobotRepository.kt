package com.cleanbot.app.data.repository

import android.util.Log
import com.cleanbot.app.data.local.CleanBotDatabase
import com.cleanbot.app.data.local.SessionEntity
import com.cleanbot.app.data.local.TelemetryEntity
import com.cleanbot.app.data.model.CleaningPlan
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import com.cleanbot.app.data.remote.ServerConfig
import com.cleanbot.app.notification.RobotErrorNotifier
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

/** Сессии + признак того, что данные взяты из офлайн-кэша Room. */
data class SessionsResult(val sessions: List<Session>, val fromCache: Boolean)

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
    private val socket: TelemetrySocket = TelemetrySocket(),
    /** Офлайн-кэш Room; null — кэш отключён (по умолчанию для совместимости). */
    private val db: CleanBotDatabase? = null,
    /** Публикатор локальных уведомлений об ошибках; null — уведомления отключены. */
    private val errorNotifier: RobotErrorNotifier? = null
) {
    private val _robots = MutableStateFlow<Map<String, RobotUiState>>(emptyMap())
    val robots: StateFlow<Map<String, RobotUiState>> = _robots.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTING)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Отдельный dispatcher для записи кэша: телеметрия не ждёт БД. */
    private val dbScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            ServerConfig.hostPort.collectLatest {
                while (isActive) {
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
        // Детектор ошибок edge-trigger: уведомление на переход в ERROR / смену кода / recovery.
        batch.forEach { t ->
            val name = _robots.value[t.robotId]?.name ?: t.robotId
            errorNotifier?.onTelemetry(t.robotId, name, t.state, t.error)
        }
        cacheTelemetry(batch)
    }

    /** Запись батча телеметрии в Room на dbScope; ошибка БД — лог, не краш (кэш не роняет приложение). */
    private fun cacheTelemetry(batch: List<Telemetry>) {
        val dao = db?.dao() ?: return
        val entities = batch.mapNotNull { t ->
            val ts = runCatching { Instant.parse(t.timestamp).toEpochMilli() }.getOrNull()
                ?: return@mapNotNull null
            TelemetryEntity(
                robotId = t.robotId,
                timestamp = ts,
                state = t.state,
                battery = t.battery,
                progress = t.progress,
                posX = t.position.x,
                posY = t.position.y,
                error = t.error
            )
        }
        if (entities.isEmpty()) return
        dbScope.launch {
            runCatching { dao.upsertTelemetry(entities) }
                .onFailure { Log.w(TAG, "Не удалось записать телеметрию в кэш", it) }
        }
    }

    suspend fun sendCommand(robotId: String, command: String): CommandResponse =
        api.sendCommand(robotId, command, UUID.randomUUID().toString())

    suspend fun schedule(robotId: String): List<Schedule> = api.getSchedule(robotId)
    suspend fun updateSchedule(robotId: String, entries: List<Schedule>) = api.updateSchedule(robotId, entries)
    suspend fun plan(robotId: String): CleaningPlan = api.getPlan(robotId)
    /** Сохранение плана: null при успехе, иначе текст ошибки валидации с сервера. */
    suspend fun updatePlan(robotId: String, plan: CleaningPlan): String? = api.updatePlan(robotId, plan)
    /**
     * Сессии за период: сначала сеть, при DISCONNECTED/исключении — офлайн-фолбэк из Room.
     * Успешный ответ сети обновляет кэш (upsert по startedAt — без дублей).
     */
    suspend fun sessions(robotId: String, from: String? = null, to: String? = null): SessionsResult {
        val dao = db?.dao()
        if (_connectionState.value == ConnectionState.DISCONNECTED && dao != null) {
            return SessionsResult(cachedSessions(robotId, from, to), fromCache = true)
        }
        val network = runCatching { api.getSessions(robotId, from, to) }
        return network.fold(
            onSuccess = { list ->
                dao?.let {
                    dbScope.launch {
                        runCatching { it.upsertSessions(list.map { s -> s.toEntity() }) }
                            .onFailure { e -> Log.w(TAG, "Не удалось обновить кэш сессий", e) }
                    }
                }
                SessionsResult(list, fromCache = false)
            },
            onFailure = { e ->
                Log.w(TAG, "Сессии из сети недоступны, фолбэк на кэш", e)
                dao?.let { SessionsResult(cachedSessions(robotId, from, to), fromCache = true) }
                    ?: SessionsResult(emptyList(), fromCache = true)
            }
        )
    }

    /** Телеметрия из кэша за период (epoch millis). */
    suspend fun cachedTelemetry(robotId: String, fromMillis: Long, toMillis: Long): List<TelemetryEntity> =
        db?.dao()?.telemetry(robotId, fromMillis, toMillis) ?: emptyList()

    /** Сессии только из кэша (без обращения к сети). */
    suspend fun cachedSessions(robotId: String, from: String? = null, to: String? = null): List<Session> {
        val dao = db?.dao() ?: return emptyList()
        return runCatching {
            dao.sessions(robotId).map { it.toModel() }.filter { it.matchesPeriod(from, to) }
        }.getOrElse { e ->
            Log.w(TAG, "Не удалось прочитать кэш сессий", e)
            emptyList()
        }
    }

    private fun Session.matchesPeriod(from: String?, to: String?): Boolean {
        val started = runCatching { Instant.parse(startedAt) }.getOrNull() ?: return true
        val fromInstant = from?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val toInstant = to?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (fromInstant != null && started.isBefore(fromInstant)) return false
        if (toInstant != null && started.isAfter(toInstant)) return false
        return true
    }

    private fun Session.toEntity() = SessionEntity(
        startedAt = startedAt,
        robotId = robotId,
        endedAt = endedAt,
        area = areaCleaned,
        mode = mode,
        aborted = aborted
    )

    private fun SessionEntity.toModel(): Session {
        val started = runCatching { Instant.parse(startedAt) }.getOrNull()
        val ended = runCatching { Instant.parse(endedAt) }.getOrNull()
        val duration = if (started != null && ended != null) {
            ChronoUnit.MINUTES.between(started, ended).coerceAtLeast(0).toInt()
        } else 0
        return Session(
            id = startedAt,
            robotId = robotId,
            startedAt = startedAt,
            endedAt = endedAt,
            durationMinutes = duration,
            areaCleaned = area,
            mode = mode,
            aborted = aborted
        )
    }
    suspend fun logs(robotId: String): List<LogEntry> = api.getLogs(robotId)
    suspend fun settings(robotId: String): RobotSettings = api.getSettings(robotId)
    suspend fun updateSettings(settings: RobotSettings) = api.updateSettings(settings)

    private companion object {
        const val TAG = "RobotRepository"
    }
}
