package com.cleanbot.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Оффлайн-стейт вместо вечного спиннера: заголовок, подсказка и кнопки
 * «Повторить» (+ «Настройки», если передан колбэк). Вынесен из FleetListScreen,
 * чтобы детальные экраны не показывали бесконечный CircularProgressIndicator
 * при недоступном симуляторе (аудит 2026-11-11).
 */
@Composable
fun OfflineState(
    onRetry: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    title: String = "Нет связи с симулятором",
    hint: String = "Проверьте, что симулятор запущен, и адрес верен. " +
        "Адрес задаётся в настройках приложения.",
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text("Повторить") }
            onOpenSettings?.let { FilledTonalButton(onClick = it) { Text("Настройки") } }
        }
    }
}
