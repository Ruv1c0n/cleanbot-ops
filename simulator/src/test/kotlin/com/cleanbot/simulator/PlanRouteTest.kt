package com.cleanbot.simulator

import com.cleanbot.simulator.model.Cell
import com.cleanbot.simulator.model.CleaningPlan
import com.cleanbot.simulator.model.Zone
import com.cleanbot.simulator.robot.planRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Чистая функция planRoute: «змейка» по зонам плана — тот же порядок клеток,
 * что и фактическое движение робота (RobotEngine при START).
 */
class PlanRouteTest {

    @Test
    fun `null and empty plans give empty route`() {
        assertTrue(planRoute(null).isEmpty())
        assertTrue(planRoute(CleaningPlan(20, 14, emptyList())).isEmpty())
    }

    @Test
    fun `single zone is swept in snake order`() {
        val plan = CleaningPlan(
            20, 14,
            listOf(Zone(1, "Комната", listOf(Cell(2, 1), Cell(3, 1), Cell(4, 1), Cell(2, 2), Cell(3, 2))))
        )

        val route = planRoute(plan)

        // строка y=1 слева направо, строка y=2 справа налево (змейка)
        assertEquals(
            listOf(Cell(2, 1), Cell(3, 1), Cell(4, 1), Cell(3, 2), Cell(2, 2)),
            route
        )
    }

    @Test
    fun `multiple zones are swept zone by zone`() {
        val plan = CleaningPlan(
            20, 14,
            listOf(
                Zone(2, "B", listOf(Cell(10, 5), Cell(11, 5))),
                Zone(1, "A", listOf(Cell(1, 1), Cell(2, 1)))
            )
        )

        val route = planRoute(plan)

        assertEquals(
            listOf(Cell(10, 5), Cell(11, 5), Cell(1, 1), Cell(2, 1)),
            route,
            "маршрут идёт по зонам в порядке их следования в плане"
        )
    }

    @Test
    fun `route covers every zone cell exactly once`() {
        val plan = CleaningPlan(
            20, 14,
            listOf(
                Zone(1, "A", listOf(Cell(1, 1), Cell(2, 1), Cell(1, 2))),
                Zone(2, "B", listOf(Cell(5, 5), Cell(6, 5), Cell(5, 6), Cell(6, 6)))
            )
        )

        val route = planRoute(plan)

        assertEquals(plan.zones.sumOf { it.cells.size }, route.size, "маршрут покрывает все клетки")
        assertEquals(route, route.distinct(), "клетки без повторов")
    }
}
