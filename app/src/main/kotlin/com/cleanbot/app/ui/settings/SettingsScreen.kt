package com.cleanbot.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cleanbot.app.data.remote.ServerConfig
import com.cleanbot.app.data.repository.ConnectionState
import com.cleanbot.app.di.AppContainer
import com.cleanbot.app.ui.components.ConnectionDot

/**
 * Настройки приложения: адрес сервера (доступен офлайн), индикатор связи,
 * ручное переподключение. Route "settings" в CleanBotNavHost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(onPersist = AppContainer::saveServerAddress) }
        }
    )

    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    var address by rememberSaveable { mutableStateOf(ServerConfig.hostPort.value) }
    val normalized = viewModel.normalizeHostPort(address)
    val invalid = address.isNotBlank() && normalized == null
    val changed = normalized != null && normalized != ServerConfig.hostPort.value

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Адрес сервера", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Адрес сервера (host:port)") },
                isError = invalid,
                supportingText = {
                    Text(
                        if (invalid) "Неверный адрес"
                        else "Эмулятор: 10.0.2.2:8081 · Телефон: <IP компьютера>:8081",
                        color = if (invalid) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ConnectionDot(connection)
                Text(
                    when (connection) {
                        ConnectionState.CONNECTED -> "Подключено"
                        ConnectionState.CONNECTING -> "Подключение…"
                        ConnectionState.DISCONNECTED -> "Нет связи"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(
                // Адрес валиден и (изменился ИЛИ связи нет) — иначе сохранение было бы ложью.
                enabled = !invalid && address.isNotBlank() &&
                    (changed || connection != ConnectionState.CONNECTED),
                onClick = { viewModel.save(address) }
            ) { Text("Сохранить и переподключиться") }
            OutlinedButton(onClick = { viewModel.reconnect() }) {
                Text("Переподключиться")
            }

            Spacer(Modifier.height(16.dp))
            Text("О приложении", style = MaterialTheme.typography.titleMedium)
            Text(
                "Консоль оператора уборочных роботов cleanbot-ops. Парк роботов, " +
                    "телеметрия, планы уборки и журналы событий. Адрес сервера хранится " +
                    "локально и переживает перезапуск приложения.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
