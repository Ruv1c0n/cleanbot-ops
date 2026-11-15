package com.cleanbot.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun EngineeringTab(viewModel: RobotDetailViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadSettings()
        viewModel.loadLogs()
    }

    var name by remember(settings) { mutableStateOf(settings?.displayName ?: "") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Настройки", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Имя робота") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Адрес сервера задаётся в настройках приложения.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = name.isNotBlank(),
            onClick = {
                settings?.let {
                    viewModel.saveSettings(it.copy(displayName = name))
                }
            }
        ) { Text("Сохранить") }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Журнал событий", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { viewModel.loadLogs() }) { Text("Обновить") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(logs, key = { it.timestamp + it.message }) { log ->
                val color = when (log.level) {
                    "ERROR" -> MaterialTheme.colorScheme.error
                    "WARN" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    "${log.timestamp.take(19).replace("T", " ")} [${log.level}] ${log.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = color
                )
            }
        }
    }
}
