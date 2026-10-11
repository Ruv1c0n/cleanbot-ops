package com.cleanbot.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanbot.app.ui.components.ErrorBadge
import com.cleanbot.app.ui.components.StateBadge

@Composable
fun DashboardTab(viewModel: RobotDetailViewModel) {
    val robot by viewModel.robot.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val r = robot

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (r == null) {
            CircularProgressIndicator()
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StateBadge(state = r.state)
            r.error?.let { ErrorBadge(error = it) }
        }

        Column {
            Text("Заряд: ${r.battery}%", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = (r.battery / 100f).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column {
            Text("Прогресс уборки: ${(r.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = r.progress.toFloat().coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text("Позиция: (${r.position.x}, ${r.position.y})", style = MaterialTheme.typography.bodyMedium)

        lastError?.let { message ->
            AssistChip(onClick = { viewModel.clearError() }, label = { Text(message) })
        }

        Spacer(Modifier.height(4.dp))
        Text("Команды", style = MaterialTheme.typography.titleMedium)
        CommandButtons(state = r.state, onCommand = viewModel::sendCommand)
    }
}

@Composable
private fun CommandButtons(state: String, onCommand: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onCommand("START") }, enabled = state == "IDLE") { Text("Старт") }
            Button(onClick = { onCommand("PAUSE") }, enabled = state == "CLEANING") { Text("Пауза") }
            Button(onClick = { onCommand("RESUME") }, enabled = state == "PAUSED") { Text("Продолжить") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onCommand("ABORT") },
                enabled = state == "CLEANING" || state == "PAUSED"
            ) { Text("Прервать") }
            OutlinedButton(
                onClick = { onCommand("RETURN_HOME") },
                enabled = state == "CLEANING" || state == "PAUSED" || state == "IDLE"
            ) { Text("Домой") }
            if (state == "ERROR") {
                Button(onClick = { onCommand("RESET") }) { Text("Сброс ошибки") }
            }
        }
    }
}
