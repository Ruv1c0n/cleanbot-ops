package com.cleanbot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun StateBadge(state: String, modifier: Modifier = Modifier) {
    val color = when (state) {
        "CLEANING" -> Color(0xFF2E7D32)
        "PAUSED" -> Color(0xFFF9A825)
        "RETURNING" -> Color(0xFF1565C0)
        "CHARGING" -> Color(0xFF6A1B9A)
        "ERROR" -> Color(0xFFC62828)
        else -> Color(0xFF616161) // IDLE
    }
    Text(
        text = stateLabel(state),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .background(color, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
fun ErrorBadge(error: String, modifier: Modifier = Modifier) {
    Text(
        text = errorLabel(error),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .background(Color(0xFFC62828), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

fun stateLabel(state: String): String = when (state) {
    "IDLE" -> "Простой"
    "CLEANING" -> "Уборка"
    "PAUSED" -> "Пауза"
    "RETURNING" -> "Возврат"
    "CHARGING" -> "Зарядка"
    "ERROR" -> "Ошибка"
    else -> state
}

fun errorLabel(error: String): String = when (error) {
    "STUCK" -> "Застрял"
    "BRUSH_JAMMED" -> "Щётка заклинила"
    "TANK_FULL" -> "Бак полон"
    "SENSOR_FAULT" -> "Сбой датчика"
    "LOW_BATTERY" -> "Низкий заряд"
    else -> error
}
