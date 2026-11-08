package com.cleanbot.simulator.robot

import com.cleanbot.simulator.model.Cell
import com.cleanbot.simulator.model.CleaningPlan

/**
 * Чистая функция планируемого маршрута: клетки зон плана «змейкой», зона за зоной.
 * В зоне клетки сортируются по строкам (y), направление обхода строк чередуется.
 * Пустой/отсутствующий план → пустой маршрут.
 *
 * Используется и RobotEngine при START (фактическое движение), и GET /plan
 * (предпросмотр) — показанный маршрут всегда совпадает с фактическим.
 */
fun planRoute(plan: CleaningPlan?): List<Cell> {
    if (plan == null) return emptyList()
    return plan.zones.flatMap { zone ->
        zone.cells
            .sortedWith(compareBy({ it.y }, { it.x }))
            .groupBy { it.y }
            .toSortedMap()
            .entries.flatMapIndexed { rowIndex, (_, rowCells) ->
                if (rowIndex % 2 == 0) rowCells else rowCells.reversed()
            }
    }
}
