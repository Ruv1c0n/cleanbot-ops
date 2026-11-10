package com.cleanbot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Primary = Color(0xFF00695C)
val Secondary = Color(0xFF00897B)
val Background = Color(0xFFF5F7F7)

/** Палитра раскраски зон на карте плана. */
val ZoneColors = listOf(
    Color(0xFF4DB6AC), // бирюзовый
    Color(0xFF64B5F6), // голубой
    Color(0xFFFFB74D), // оранжевый
    Color(0xFF9575CD), // сиреневый
    Color(0xFF81C784), // зелёный
    Color(0xFFF06292)  // розовый
)

/** Линии сетки карты. */
val MapGridLine = Color(0xFFB0BEC5)

/** Фон карты вне зон. */
val MapBackground = Color(0xFFECEFF1)

/** Маркер робота. */
val RobotMarker = Color(0xFF37474F)

/** Планируемая траектория (пунктир) на карте плана. */
val MapRoute = Color(0xFFD84315)

// Тёмные варианты карты: без них карта остаётся светлым пятном в тёмной
// теме (аудит 2026-11-11).
private val MapGridLineDark = Color(0xFF3A4149)
private val MapBackgroundDark = Color(0xFF1E2226)
private val RobotMarkerDark = Color(0xFFECEFF1)

@Composable
fun mapBackground(): Color = if (isSystemInDarkTheme()) MapBackgroundDark else MapBackground

@Composable
fun mapGridLine(): Color = if (isSystemInDarkTheme()) MapGridLineDark else MapGridLine

@Composable
fun robotMarker(): Color = if (isSystemInDarkTheme()) RobotMarkerDark else RobotMarker
