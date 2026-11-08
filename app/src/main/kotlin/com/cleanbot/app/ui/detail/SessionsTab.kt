package com.cleanbot.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SessionsTab(viewModel: RobotDetailViewModel) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val fromCache by viewModel.sessionsFromCache.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadSessions() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (fromCache) {
            OfflineBanner()
            Spacer(Modifier.height(12.dp))
        }
        if (sessions.isNotEmpty()) {
            val totalArea = sessions.sumOf { it.areaCleaned }
            val avgDuration = sessions.map { it.durationMinutes }.average().toInt()
            Text(
                "Сессий: ${sessions.size} · Убрано: ${"%.1f".format(totalArea)} м² · Средняя: $avgDuration мин",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { session ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(session.startedAt.take(16).replace("T", " "), style = MaterialTheme.typography.bodyLarge)
                        val suffix = if (session.aborted) " · прервана" else ""
                        Text(
                            "${session.durationMinutes} мин · ${session.areaCleaned} м² · ${session.mode}$suffix",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/** Метка «офлайн-данные»: список показан из локального кэша Room. */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            "Офлайн-данные (из локального кэша)",
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
