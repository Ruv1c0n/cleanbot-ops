package com.cleanbot.app.ui.fleet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanbot.app.data.repository.RobotRepository
import com.cleanbot.app.di.AppContainer
import kotlinx.coroutines.launch

class FleetListViewModel(
    private val repository: RobotRepository = AppContainer.robotRepository
) : ViewModel() {

    val robots = repository.robots
    val connectionState = repository.connectionState

    init {
        repository.startTelemetryLoop(viewModelScope)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { repository.refreshFleet() }
    }
}
