package com.cleanbot.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cleanbot.app.data.model.CleaningPlan

/** Условная площадь одной клетки плана в м² — для пересчёта клеток в площадь. */
private const val CELL_AREA_M2 = 0.25

/**
 * Bottom-sheet «Предпросмотр плана»: та же PlanMapView без реалтайм-маркера,
 * с пунктирной траекторией, списком зон и суммарной площадью. Старт отправляет
 * команду как раньше; при пустом плане кнопка неактивна.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanPreviewSheet(
    plan: CleaningPlan?,
    showEveryTime: Boolean,
    onShowEveryTimeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onStart: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Предпросмотр плана", style = MaterialTheme.typography.titleMedium)

            val empty = plan == null || plan.zones.isEmpty()
            when {
                plan == null -> Text("План не загружен", style = MaterialTheme.typography.bodyMedium)
                plan.zones.isEmpty() -> Text(
                    "План не задан",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                else -> {
                    PlanMapView(
                        plan = plan,
                        position = null,
                        modifier = Modifier.fillMaxWidth(),
                        route = plan.route
                    )
                    ZoneLegend(plan = plan)

                    val cells = plan.zones.sumOf { it.cells.size }
                    val area = cells * CELL_AREA_M2
                    Text(
                        "Зон: ${plan.zones.size} · Клеток: $cells · Площадь ≈ ${"%.2f".format(area)} м²",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showEveryTime, onCheckedChange = onShowEveryTimeChange)
                Spacer(Modifier.width(4.dp))
                Text("Показывать каждый раз", style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss) { Text("Отмена") }
                Button(onClick = onStart, enabled = !empty) { Text("Старт") }
            }
        }
    }
}
