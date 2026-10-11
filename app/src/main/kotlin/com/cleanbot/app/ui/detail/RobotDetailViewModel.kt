package com.cleanbot.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class RobotDetailViewModel(
    private val robotId: String,
    private val repository: RobotRepository = AppContainer.robotRepository
) : ViewModel() {

    val robot: StateFlow<RobotUiState?> = repository.robots
        .map { it[robotId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _schedule = MutableStateFlow<List<Schedule>>(emptyList())
    val schedule: StateFlow<List<Schedule>> = _schedule.asStateFlow()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _settings = MutableStateFlow<RobotSettings?>(null)
    val settings: StateFlow<RobotSettings?> = _settings.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun sendCommand(command: String) {
        viewModelScope.launch {
            val response = repository.sendCommand(robotId, command)
            if (!response.accepted) {
                _lastError.value = "Команда отклонена: ${response.reason}"
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
            runCatching { repository.sessions(robotId) }.onSuccess { _sessions.value = it }
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

    fun saveSettings(newSettings: RobotSettings) {
        viewModelScope.launch {
            runCatching { repository.updateSettings(newSettings) }.onSuccess { _settings.value = newSettings }
        }
    }

    fun clearError() {
        _lastError.value = null
    }
}
