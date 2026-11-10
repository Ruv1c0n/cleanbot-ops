package com.cleanbot.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanbot.app.data.model.CleaningPlan
import com.cleanbot.app.data.model.LogEntry
import com.cleanbot.app.data.model.RobotSettings
import com.cleanbot.app.data.model.Schedule
import com.cleanbot.app.data.model.Session
import com.cleanbot.app.data.repository.RobotRepository
import com.cleanbot.app.data.repository.RobotUiState
import com.cleanbot.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import com.cleanbot.app.data.remote.ServerConfig

/** Период статистики; days == null — вся история. */
enum class StatsPeriod(val label: String, val days: Int?) {
    DAYS_7("7 дней", 7),
    DAYS_30("30 дней", 30),
    ALL("Всё", null)
}

class RobotDetailViewModel(
    private val robotId: String,
    private val repository: RobotRepository = AppContainer.robotRepository
) : ViewModel() {

    val robot: StateFlow<RobotUiState?> = repository.robots
        .map { it[robotId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _schedule = MutableStateFlow<List<Schedule>>(emptyList())
    val schedule: StateFlow<List<Schedule>> = _schedule.asStateFlow()

    private val _plan = MutableStateFlow<CleaningPlan?>(null)
    val plan: StateFlow<CleaningPlan?> = _plan.asStateFlow()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _sessionsFromCache = MutableStateFlow(false)
    val sessionsFromCache: StateFlow<Boolean> = _sessionsFromCache.asStateFlow()

    private val _statsPeriod = MutableStateFlow(StatsPeriod.DAYS_7)
    val statsPeriod: StateFlow<StatsPeriod> = _statsPeriod.asStateFlow()

    private val _statsSessions = MutableStateFlow<List<Session>>(emptyList())
    val statsSessions: StateFlow<List<Session>> = _statsSessions.asStateFlow()

    private val _statsFromCache = MutableStateFlow(false)
    val statsFromCache: StateFlow<Boolean> = _statsFromCache.asStateFlow()

    private val _statsLoading = MutableStateFlow(false)
    val statsLoading: StateFlow<Boolean> = _statsLoading.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _settings = MutableStateFlow<RobotSettings?>(null)
    val settings: StateFlow<RobotSettings?> = _settings.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun sendCommand(command: String) {
        viewModelScope.launch {
            runCatching { repository.sendCommand(robotId, command) }
                .onSuccess { response ->
                    if (!response.accepted) {
                        _lastError.value = "Команда отклонена: ${response.reason}"
                    }
                }
                .onFailure {
                    _lastError.value = "Команда не отправлена: ${it.message ?: "нет связи с сервером"}"
                }
        }
    }

    fun loadSchedule() {
        viewModelScope.launch {
            runCatching { repository.schedule(robotId) }.onSuccess { _schedule.value = it }
        }
    }

    fun saveSchedule(entries: List<Schedule>) {
        viewModelScope.launch {
            runCatching { repository.updateSchedule(robotId, entries) }.onSuccess { _schedule.value = entries }
        }
    }

    fun loadSessions() {
        viewModelScope.launch {
            runCatching { repository.sessions(robotId) }.onSuccess { result ->
                _sessions.value = result.sessions
                _sessionsFromCache.value = result.fromCache
            }
        }
    }

    fun loadPlan() {
        viewModelScope.launch {
            runCatching { repository.plan(robotId) }.onSuccess { _plan.value = it }
        }
    }

    fun setStatsPeriod(period: StatsPeriod) {
        _statsPeriod.value = period
        loadStats()
    }

    /**
     * Статистика за период: Room-кэш + догрузка из сети.
     * Сначала мгновенно показываем кэш, затем сеть; недостающее в сетевом ответе
     * дополняем из кэша (по startedAt), ошибки сети оставляют кэш с пометкой «офлайн».
     */
    fun loadStats() {
        viewModelScope.launch {
            _statsLoading.value = true
            val from = _statsPeriod.value.days
                ?.let { Instant.now().minus(it.toLong(), ChronoUnit.DAYS).toString() }
            val cached = runCatching { repository.cachedSessions(robotId, from, null) }
                .getOrDefault(emptyList())
            if (_statsSessions.value.isEmpty()) {
                _statsSessions.value = cached
                _statsFromCache.value = cached.isNotEmpty()
            }
            runCatching { repository.sessions(robotId, from, null) }.onSuccess { result ->
                if (result.fromCache) {
                    // Сеть недоступна: остаются офлайн-данные из кэша
                    _statsSessions.value = result.sessions
                    _statsFromCache.value = result.sessions.isNotEmpty()
                } else {
                    // Сеть успешна: догружаем из кэша то, чего нет в ответе
                    val networkStarted = result.sessions.map { it.startedAt }.toSet()
                    val topUp = cached.filter { it.startedAt !in networkStarted }
                    _statsSessions.value = result.sessions + topUp
                    _statsFromCache.value = false
                }
            }
            _statsLoading.value = false
        }
    }

    fun loadLogs() {
        viewModelScope.launch {
            runCatching { repository.logs(robotId) }.onSuccess { _logs.value = it }
        }
    }

    fun loadSettings() {
        viewModelScope.launch {
            runCatching { repository.settings(robotId) }.onSuccess { _settings.value = it }
        }
    }

    fun saveSettings(newSettings: RobotSettings, hostPort: String) {
        viewModelScope.launch {
            // PUT уходит на ещё действующий адрес; сам адрес сервера и локальные
            // настройки меняем только при успехе, иначе «сохранение» было бы ложью.
            runCatching { repository.updateSettings(newSettings) }
                .onSuccess {
                    ServerConfig.hostPort.value = hostPort
                    AppContainer.saveServerAddress(hostPort)
                    repository.refreshFleet()
                    _settings.value = newSettings
                }
                .onFailure {
                    _lastError.value = "Настройки не сохранены: ${it.message ?: "нет связи с сервером"}"
                }
        }
    }

    fun clearError() {
        _lastError.value = null
    }
}
