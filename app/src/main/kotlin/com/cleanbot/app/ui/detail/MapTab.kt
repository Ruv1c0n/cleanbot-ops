package com.cleanbot.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanbot.app.data.model.CleaningPlan
import com.cleanbot.app.ui.theme.ZoneColors

@Composable
fun MapTab(viewModel: RobotDetailViewModel, onEditPlan: () -> Unit) {
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val robot by viewModel.robot.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadPlan() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("План уборки", style = MaterialTheme.typography.titleMedium)

        val p = plan
        when {
            p == null -> CircularProgressIndicator()
            else -> {
                PlanMapView(
                    plan = p,
                    position = robot?.position,
                    modifier = Modifier.fillMaxWidth()
                )
                ZoneLegend(plan = p)
                if (p.zones.isEmpty()) {
                    Text("План пуст", style = MaterialTheme.typography.bodyMedium)
                }
                robot?.let { r ->
                    Text(
                        "Позиция: (${r.position.x}, ${r.position.y}) · Прогресс: ${(r.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        Button(onClick = onEditPlan) { Text("Редактировать") }
    }
}

@Composable
// internal по пакету: переиспользуется в предпросмотре плана (PlanPreviewSheet)
fun ZoneLegend(plan: CleaningPlan) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        plan.zones.forEachIndexed { index, zone ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(ZoneColors[index % ZoneColors.size])
                )
                Text(
                    zone.name,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}
