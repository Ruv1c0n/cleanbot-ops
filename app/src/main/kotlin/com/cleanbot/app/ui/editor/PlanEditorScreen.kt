package com.cleanbot.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cleanbot.app.data.model.Cell
import com.cleanbot.app.ui.theme.MapBackground
import com.cleanbot.app.ui.theme.MapGridLine
import com.cleanbot.app.ui.theme.ZoneColors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Экран-редактор плана (issue #10): сетка 20×14, тап красит/снимает клетку активной зоны,
 * чипы зон сверху, сохранение через PUT /plan. Несохранённые изменения при выходе — подтверждение.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanEditorScreen(robotId: String, onBack: () -> Unit) {
    val viewModel: PlanEditorViewModel = viewModel(
        factory = viewModelFactory {
            initializer { PlanEditorViewModel(robotId) }
        }
    )

    val sketch by viewModel.sketch.collectAsStateWithLifecycle()
    val activeIndex by viewModel.activeZoneIndex.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    var renameIndex by rememberSaveable { mutableStateOf(-1) }
    var deleteIndex by rememberSaveable { mutableStateOf(-1) }
    // диалог «создать первую зону» показывается один раз за вход, «Отмена» его гасит
    var firstZoneOfferDismissed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(saved) { if (saved) onBack() }

    val dirty = viewModel.hasUnsavedChanges
    BackHandler(enabled = true) {
        if (dirty) confirmExit = true else onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Редактор плана") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (dirty) confirmExit = true else onBack()
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        val p = sketch
        when {
            p == null -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Робот: $robotId", style = MaterialTheme.typography.bodySmall)

                ZoneChipsRow(
                    zoneNames = p.zones.map { it.name },
                    activeIndex = activeIndex,
                    onSelect = viewModel::setActiveZone,
                    onNewZone = { viewModel.createZone() }
                )

                EditorGrid(
                    zoneCells = p.zones.map { it.cells },
                    zoneNames = p.zones.map { it.name },
                    gridW = p.gridW,
                    gridH = p.gridH,
                    activeIndex = activeIndex,
                    onTapCell = viewModel::toggleCell
                )

                activeIndex.takeIf { it in p.zones.indices }?.let { index ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { renameIndex = index }) { Text("Переименовать") }
                        OutlinedButton(onClick = { deleteIndex = index }) { Text("Удалить зону") }
                    }
                }

                Spacer(Modifier.weight(1f))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::save,
                        enabled = dirty && !saving,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (saving) "Сохранение…" else "Сохранить") }
                    OutlinedButton(
                        onClick = { if (dirty) viewModel.reset() else onBack() },
                        enabled = !saving,
                        modifier = Modifier.weight(1f)
                    ) { Text("Отмена") }
                }
            }
        }
    }

    // План пуст — предлагаем создать первую зону сразу после согласия
    val offerFirstZone = sketch?.zones?.isEmpty() == true && !firstZoneOfferDismissed
    if (offerFirstZone) {
        AlertDialog(
            onDismissRequest = { firstZoneOfferDismissed = true },
            title = { Text("План пуст") },
            text = { Text("Создать первую зону и начать рисовать?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createZone()
                    firstZoneOfferDismissed = true
                }) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { firstZoneOfferDismissed = true }) { Text("Позже") }
            }
        )
    }

    if (renameIndex >= 0) {
        RenameZoneDialog(
            currentName = sketch?.zones?.getOrNull(renameIndex)?.name.orEmpty(),
            onDismiss = { renameIndex = -1 },
            onConfirm = { name ->
                viewModel.renameZone(renameIndex, name)
                renameIndex = -1
            }
        )
    }

    if (deleteIndex >= 0) {
        AlertDialog(
            onDismissRequest = { deleteIndex = -1 },
            title = { Text("Удалить зону?") },
            text = { Text("Зона «${sketch?.zones?.getOrNull(deleteIndex)?.name.orEmpty()}» и все её клетки будут убраны из эскиза.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteZone(deleteIndex)
                    deleteIndex = -1
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteIndex = -1 }) { Text("Отмена") } }
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Несохранённые изменения") },
            text = { Text("Выйти без сохранения? Изменения эскиза будут потеряны.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    onBack()
                }) { Text("Выйти") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmExit = false
                    viewModel.save()
                }) { Text("Сохранить и выйти") }
            }
        )
    }
}

@Composable
private fun RenameZoneDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать зону") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Название зоны") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("ОК") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun ZoneChipsRow(
    zoneNames: List<String>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onNewZone: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        zoneNames.forEachIndexed { index, name ->
            val active = index == activeIndex
            val borderColor = ZoneColors[index % ZoneColors.size]
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active) borderColor.copy(alpha = 0.25f) else Color.Transparent)
                    .border(
                        width = if (active) 2.dp else 1.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(borderColor)
                )
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
        Text(
            "+ Новая зона",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable { onNewZone() }
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun EditorGrid(
    zoneCells: List<List<Cell>>,
    zoneNames: List<String>,
    gridW: Int,
    gridH: Int,
    activeIndex: Int,
    onTapCell: (Int, Int) -> Unit
) {
    var boxPx by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(gridW.toFloat() / gridH.toFloat())
            .onSizeChanged { boxPx = it }
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(zoneCells, activeIndex) {
                    detectTapGestures { offset ->
                        // пересчёт тапа в клетку: та же геометрия, что и в отрисовке ниже
                        val cell = min(size.width / gridW.toFloat(), size.height / gridH.toFloat())
                        val gridPxW = cell * gridW
                        val gridPxH = cell * gridH
                        val originX = (size.width - gridPxW) / 2f
                        val originY = (size.height - gridPxH) / 2f
                        val cx = ((offset.x - originX) / cell).toInt()
                        val cy = ((offset.y - originY) / cell).toInt()
                        if (cx in 0 until gridW && cy in 0 until gridH) onTapCell(cx, cy)
                    }
                }
        ) {
            val cell = min(size.width / gridW, size.height / gridH)
            val gridPxW = cell * gridW
            val gridPxH = cell * gridH
            val originX = (size.width - gridPxW) / 2f
            val originY = (size.height - gridPxH) / 2f

            drawRect(color = MapBackground, topLeft = Offset(originX, originY), size = Size(gridPxW, gridPxH))

            // чужие зоны — приглушённо, активная — в полный цвет
            zoneCells.forEachIndexed { index, cells ->
                val color = ZoneColors[index % ZoneColors.size]
                val alpha = if (index == activeIndex) 1f else 0.35f
                cells.forEach { c ->
                    drawRect(
                        color = color.copy(alpha = alpha),
                        topLeft = Offset(originX + c.x * cell + 1, originY + c.y * cell + 1),
                        size = Size(cell - 2, cell - 2)
                    )
                }
            }

            for (i in 0..gridW) {
                drawLine(MapGridLine, Offset(originX + i * cell, originY), Offset(originX + i * cell, originY + gridPxH))
            }
            for (j in 0..gridH) {
                drawLine(MapGridLine, Offset(originX, originY + j * cell), Offset(originX + gridPxW, originY + j * cell))
            }
        }

        // подписи зон — в левую верхнюю клетку зоны (как на PlanMapView)
        if (boxPx != IntSize.Zero) {
            val cell = min(boxPx.width / gridW, boxPx.height / gridH).toFloat()
            val originX = (boxPx.width - cell * gridW) / 2f
            val originY = (boxPx.height - cell * gridH) / 2f
            zoneCells.forEachIndexed { index, cells ->
                val anchor = cells.minByOrNull { it.y * 1000 + it.x } ?: return@forEachIndexed
                Text(
                    text = zoneNames.getOrElse(index) { "" },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF263238),
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (originX + anchor.x * cell).roundToInt(),
                                (originY + anchor.y * cell).roundToInt()
                            )
                        }
                        .padding(2.dp)
                )
            }
        }
    }
}
