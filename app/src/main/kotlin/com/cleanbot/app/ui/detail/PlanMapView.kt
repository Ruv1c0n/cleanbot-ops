package com.cleanbot.app.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleanbot.app.data.model.CleaningPlan
import com.cleanbot.app.data.model.Position
import com.cleanbot.app.ui.theme.MapBackground
import com.cleanbot.app.ui.theme.MapGridLine
import com.cleanbot.app.ui.theme.RobotMarker
import com.cleanbot.app.ui.theme.ZoneColors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Переиспользуемая карта плана уборки: сетка gridW×gridH, зоны палитрой из ZoneColors,
 * подписи зон, маркер робота по position из телеметрии. Реалтайм — маркер перерисовывается
 * при каждой новой телеметрии, сетевых запросов на кадр нет.
 *
 * Телеметрия с координатой вне сетки не роняет рендер: позиция клампится в границы.
 */
@Composable
fun PlanMapView(plan: CleaningPlan, position: Position?, modifier: Modifier = Modifier) {
    var boxPx by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .onSizeChanged { boxPx = it }
            .aspectRatio(plan.gridW.toFloat() / plan.gridH.toFloat())
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = min(size.width / plan.gridW, size.height / plan.gridH)
            val gridW = cell * plan.gridW
            val gridH = cell * plan.gridH
            val originX = (size.width - gridW) / 2f
            val originY = (size.height - gridH) / 2f

            drawRect(color = MapBackground, topLeft = Offset(originX, originY), size = Size(gridW, gridH))

            plan.zones.forEachIndexed { index, zone ->
                val color = ZoneColors[index % ZoneColors.size]
                zone.cells.forEach { c ->
                    if (c.x in 0 until plan.gridW && c.y in 0 until plan.gridH) {
                        drawRect(
                            color = color,
                            topLeft = Offset(originX + c.x * cell + 1, originY + c.y * cell + 1),
                            size = Size(cell - 2, cell - 2)
                        )
                    }
                }
            }

            // сетка
            for (i in 0..plan.gridW) {
                drawLine(MapGridLine, Offset(originX + i * cell, originY), Offset(originX + i * cell, originY + gridH))
            }
            for (j in 0..plan.gridH) {
                drawLine(MapGridLine, Offset(originX, originY + j * cell), Offset(originX + gridW, originY + j * cell))
            }

            // маркер робота
            position?.let { p ->
                val cx = originX + (p.x.coerceIn(0, plan.gridW - 1) + 0.5f) * cell
                val cy = originY + (p.y.coerceIn(0, plan.gridH - 1) + 0.5f) * cell
                drawCircle(color = RobotMarker, radius = cell * 0.35f, center = Offset(cx, cy))
                drawCircle(color = Color.White, radius = cell * 0.12f, center = Offset(cx, cy))
            }
        }

        // подписи зон — в левую верхнюю клетку зоны
        if (boxPx != IntSize.Zero) {
            val cell = min(boxPx.width / plan.gridW, boxPx.height / plan.gridH).toFloat()
            val originX = (boxPx.width - cell * plan.gridW) / 2f
            val originY = (boxPx.height - cell * plan.gridH) / 2f
            plan.zones.forEachIndexed { index, zone ->
                val anchor = zone.cells.minByOrNull { it.y * 1000 + it.x } ?: return@forEachIndexed
                val color = ZoneColors[index % ZoneColors.size]
                Text(
                    text = zone.name,
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
