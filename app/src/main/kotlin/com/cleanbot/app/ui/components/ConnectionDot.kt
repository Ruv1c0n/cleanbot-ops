package com.cleanbot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cleanbot.app.data.repository.ConnectionState

/**
 * Точка-индикатор связи с симулятором (цвет по [ConnectionState]).
 * Вынесена из FleetListScreen: тот же индикатор нужен на экране настроек.
 */
@Composable
fun ConnectionDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, shape = CircleShape)
    )
}
