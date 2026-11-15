package com.cleanbot.app.ui.fleet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cleanbot.app.data.repository.ConnectionState
import com.cleanbot.app.data.repository.RobotUiState
import com.cleanbot.app.ui.components.ConnectionDot
import com.cleanbot.app.ui.components.ErrorBadge
import com.cleanbot.app.ui.components.OfflineState
import com.cleanbot.app.ui.components.StateBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FleetListScreen(
    onRobotClick: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: FleetListViewModel = viewModel()
) {
    val robots by viewModel.robots.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()

    Scaffold(
        // safeDrawing включает display cutout: в landscape контент не уходит
        // под вырез камеры (аудит 2026-11-11).
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Парк роботов") },
                actions = {
                    ConnectionDot(connection)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Настройки")
                    }
                    Spacer(Modifier.width(16.dp))
                }
            )
        }
    ) { padding ->
        val list = robots.values.sortedBy { it.id }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                when (connection) {
                    // Рыночный стандарт вместо вечного спиннера: понятный
                    // оффлайн-стейт с повтором (аудит 2026-11-11).
                    ConnectionState.DISCONNECTED -> OfflineState(
                        onRetry = viewModel::refresh,
                        onOpenSettings = onOpenSettings,
                        hint = "Проверь, что симулятор запущен, и адрес верен —\n" +
                            "он задаётся в настройках приложения."
                    )
                    else -> CircularProgressIndicator()
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(list, key = { it.id }) { robot ->
                    RobotRow(robot = robot, onClick = { onRobotClick(robot.id) })
                }
            }
        }
    }
}

@Composable
private fun RobotRow(robot: RobotUiState, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(robot.name, style = MaterialTheme.typography.titleMedium)
                Text("${robot.battery}%", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StateBadge(state = robot.state)
                robot.error?.let { ErrorBadge(error = it) }
            }
        }
    }
}
