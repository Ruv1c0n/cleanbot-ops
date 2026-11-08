package com.cleanbot.app.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanbot.app.data.model.Session
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun StatsTab(viewModel: RobotDetailViewModel) {
    val sessions by viewModel.statsSessions.collectAsStateWithLifecycle()
    val period by viewModel.statsPeriod.collectAsStateWithLifecycle()
    val loading by viewModel.statsLoading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadStats() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsPeriod.entries.forEach { p ->
                FilterChip(
                    selected = period == p,
                    onClick = { viewModel.setStatsPeriod(p) },
                    label = { Text(p.label) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (loading) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            return@Column
        }

        if (sessions.isEmpty()) {
            Text("Нет данных за период", style = MaterialTheme.typography.bodyLarge)
            return@Column
        }

        Aggregates(sessions)
        Spacer(Modifier.height(16.dp))
        Text("Площадь по дням", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        AreaBarChart(sessions)
    }
}

@Composable
private fun Aggregates(sessions: List<Session>) {
    val totalArea = sessions.sumOf { it.areaCleaned }
    val totalMinutes = sessions.sumOf { it.durationMinutes }
    val abortedPct = sessions.count { it.aborted } * 100 / sessions.size
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val timeStr = if (hours > 0) "$hours ч $minutes мин" else "$minutes мин"
    Text(
        "Сессий: ${sessions.size} · Площадь: ${"%.1f".format(totalArea)} м² · " +
            "Время: $timeStr · Прервано: $abortedPct%",
        style = MaterialTheme.typography.bodyMedium
    )
}

/** Столбчатая диаграмма «суммарная площадь по дням» на чистом Canvas, без внешних зависимостей. */
@Composable
private fun AreaBarChart(sessions: List<Session>) {
    val perDay = sessions.groupBy { LocalDate.parse(it.startedAt.take(10)) }
        .toSortedMap()
        .map { (day, list) -> day to list.sumOf { it.areaCleaned } }
    val maxArea = perDay.maxOf { it.second }.coerceAtLeast(0.1)
    val barColor = MaterialTheme.colorScheme.primary

    Column {
        Text("макс: ${"%.1f".format(maxArea)} м²", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val n = perDay.size
            val gap = 4.dp.toPx()
            val barWidth = if (n > 1) (size.width - gap * (n - 1)) / n else size.width
            val drawableHeight = size.height - 8.dp.toPx()
            perDay.forEachIndexed { i, (_, area) ->
                val h = (area / maxArea * drawableHeight).toFloat().coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(i * (barWidth + gap), size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val fmt = DateTimeFormatter.ofPattern("dd.MM")
            Text(perDay.first().first.format(fmt), style = MaterialTheme.typography.labelSmall)
            Text(perDay.last().first.format(fmt), style = MaterialTheme.typography.labelSmall)
        }
    }
}
