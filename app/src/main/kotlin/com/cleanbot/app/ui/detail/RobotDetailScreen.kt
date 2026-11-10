package com.cleanbot.app.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

private val tabs = listOf("Дашборд", "Карта", "Расписание", "Сессии", "Статистика", "Инженерное")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobotDetailScreen(robotId: String, onBack: () -> Unit, onEditPlan: () -> Unit = {}) {
    val viewModel: RobotDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer { RobotDetailViewModel(robotId) }
        }
    )

    val robot by viewModel.robot.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        // safeDrawing включает display cutout — см. FleetListScreen.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(robot?.name ?: robotId) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ScrollableTabRow: длинные лейблы не переносятся посреди слова
            // («Дашб|орд» в TabRow — аудит 2026-11-11).
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 16.dp) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            when (selectedTab) {
                0 -> DashboardTab(viewModel)
                1 -> MapTab(viewModel, onEditPlan)
                2 -> ScheduleTab(viewModel, robotId)
                3 -> SessionsTab(viewModel)
                4 -> StatsTab(viewModel)
                5 -> EngineeringTab(viewModel)
            }
        }
    }
}
