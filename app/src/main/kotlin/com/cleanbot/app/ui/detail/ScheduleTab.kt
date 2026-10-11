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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanbot.app.data.model.Schedule
import java.util.UUID
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField

private val dayNames = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

@Composable
fun ScheduleTab(viewModel: RobotDetailViewModel, robotId: String) {
    val schedule by viewModel.schedule.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<List<Schedule>>(emptyList()) }

    LaunchedEffect(Unit) { viewModel.loadSchedule() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Расписание уборки", style = MaterialTheme.typography.titleMedium)
            TextButton(
                enabled = !editing || draft.all { timeRegex.matches(it.time) },
                onClick = {
                    if (editing) {
                        viewModel.saveSchedule(draft.sortedWith(compareBy({ it.dayOfWeek }, { it.time })))
                        editing = false
                    } else {
                        draft = schedule
                        editing = true
                    }
                }
            ) { Text(if (editing) "Сохранить" else "Изменить") }
        }

        Spacer(Modifier.height(8.dp))

        val items = if (editing) draft else schedule
        if (items.isEmpty()) {
            Text("Записей нет", style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { entry ->
                if (editing) {
                    EditableScheduleRow(
                        entry = entry,
                        onChange = { upd -> draft = draft.map { if (it.id == upd.id) upd else it } },
                        onDelete = { draft = draft.filterNot { it.id == entry.id } }
                    )
                } else {
                    ScheduleRow(entry = entry, editable = false, onDelete = {})
                }
            }
            if (editing) {
                item {
                    TextButton(onClick = {
                        draft = draft + Schedule(
                            id = UUID.randomUUID().toString(),
                            robotId = robotId,
                            dayOfWeek = 1,
                            time = "06:00",
                            mode = "dry_sweep"
                        )
                    }) { Text("+ Добавить запись") }
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(entry: Schedule, editable: Boolean, onDelete: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                val day = dayNames.getOrElse(entry.dayOfWeek - 1) { entry.dayOfWeek.toString() }
                Text("$day · ${entry.time}", style = MaterialTheme.typography.bodyLarge)
                Text(entry.mode, style = MaterialTheme.typography.bodySmall)
            }
            if (editable) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                }
            }
        }
    }
}

private val timeRegex = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

@Composable
private fun EditableScheduleRow(entry: Schedule, onChange: (Schedule) -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box {
                OutlinedButton(onClick = { menu = true }) {
                    Text(dayNames.getOrElse(entry.dayOfWeek - 1) { "?" })
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    dayNames.forEachIndexed { i, d ->
                        DropdownMenuItem(text = { Text(d) }, onClick = {
                            onChange(entry.copy(dayOfWeek = i + 1)); menu = false
                        })
                    }
                }
            }
            OutlinedTextField(
                value = entry.time,
                onValueChange = { onChange(entry.copy(time = it.take(5))) },
                isError = !timeRegex.matches(entry.time),
                singleLine = true,
                modifier = Modifier.width(88.dp)
            )
            OutlinedButton(onClick = {
                onChange(entry.copy(mode = if (entry.mode == "dry_sweep") "wet_mopping" else "dry_sweep"))
            }) { Text(if (entry.mode == "dry_sweep") "Сухая" else "Влажная") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Удалить") }
        }
    }
}
