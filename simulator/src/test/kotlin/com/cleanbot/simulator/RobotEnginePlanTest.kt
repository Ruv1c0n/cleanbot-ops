package com.cleanbot.simulator

import com.cleanbot.simulator.model.Cell
import com.cleanbot.simulator.model.CleaningPlan
import com.cleanbot.simulator.model.GRID_HEIGHT
import com.cleanbot.simulator.model.GRID_WIDTH
import com.cleanbot.simulator.model.Position
import com.cleanbot.simulator.model.Zone
import com.cleanbot.simulator.robot.Command
import com.cleanbot.simulator.robot.RobotEngine
import com.cleanbot.simulator.robot.RobotState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Движение по плану в RobotEngine: при START маршрут «змейкой» покрывает все клетки
 * зон, progress = доля пройденных клеток, позиция остаётся в границах сетки 20×14.
 */
class RobotEnginePlanTest {

    private fun engineWithPlan(plan: CleaningPlan) = RobotEngine("t", "T", faultChance = 0.0).apply { setPlan(plan) }

    /** Тикает, пока робот CLEANING; возвращает позиции до каждого тика. */
    private suspend fun RobotEngine.tickUntilDone(maxTicks: Int = 400): List<Position> {
        val visited = mutableListOf<Position>()
        repeat(maxTicks) {
            visited.add(snapshot().position)
            tick()
            if (snapshot().state != RobotState.CLEANING.name) return visited
        }
        return visited
    }

    @Test
    fun `route over non-empty plan visits every zone cell`() = runBlocking {
        val cells = listOf(
            Cell(2, 1), Cell(3, 1), Cell(4, 1),
            Cell(4, 2), Cell(3, 2), Cell(2, 2),
            Cell(2, 3), Cell(3, 3)
        )
        val plan = CleaningPlan(GRID_WIDTH, GRID_HEIGHT, listOf(Zone(1, "Комната", cells)))
        val engine = engineWithPlan(plan)

        assertTrue(engine.command(Command.START, "r1").accepted)
        val visited = engine.tickUntilDone()

        val finalState = engine.snapshot().state
        assertTrue(
            finalState in setOf(RobotState.RETURNING.name, RobotState.CHARGING.name),
            "после маршрута робот уходит на станцию, было $finalState"
        )
        assertEquals(1.0, engine.snapshot().progress, "все клетки маршрута должны быть пройдены")
        for (cell in cells) {
            assertTrue(Position(cell.x, cell.y) in visited, "клетка (${cell.x}, ${cell.y}) должна быть посещена")
        }
    }

    @Test
    fun `route stays inside fixed grid`() = runBlocking {
        val plan = CleaningPlan(
            GRID_WIDTH, GRID_HEIGHT,
            listOf(Zone(1, "Комната", listOf(Cell(19, 13), Cell(18, 13), Cell(17, 13))))
        )
        val engine = engineWithPlan(plan)

        engine.command(Command.START, "r1")
        val visited = engine.tickUntilDone()

        assertTrue(visited.all { it.x in 0 until GRID_WIDTH && it.y in 0 until GRID_HEIGHT })
    }

    @Test
    fun `multi-zone plan is cleaned zone by zone`() = runBlocking {
        val zoneA = Zone(1, "A", listOf(Cell(1, 1), Cell(2, 1)))
        val zoneB = Zone(2, "B", listOf(Cell(10, 5), Cell(11, 5)))
        val plan = CleaningPlan(GRID_WIDTH, GRID_HEIGHT, listOf(zoneA, zoneB))
        val engine = engineWithPlan(plan)

        engine.command(Command.START, "r1")
        val visited = engine.tickUntilDone()

        val firstA = visited.indexOf(Position(1, 1))
        val lastA = visited.lastIndexOf(Position(2, 1))
        val firstB = visited.indexOf(Position(10, 5))
        assertTrue(firstA in 0 until lastA, "зона A начинается раньше, чем заканчивается")
        assertTrue(lastA < firstB, "зона A должна закончиться раньше начала зоны B")
        assertEquals(1.0, engine.snapshot().progress)
    }

    @Test
    fun `empty plan rejects START and stays idle`() = runBlocking {
        val plan = CleaningPlan(GRID_WIDTH, GRID_HEIGHT, emptyList())
        val engine = engineWithPlan(plan)

        val response = engine.command(Command.START, "r1")

        assertTrue(!response.accepted, "пустой план не должен допускать запуск уборки")
        assertEquals("План не задан", response.reason)
        assertEquals(RobotState.IDLE.name, engine.snapshot().state, "робот должен остаться в IDLE")
    }

    @Test
    fun `plan without cells rejects START`() = runBlocking {
        val plan = CleaningPlan(GRID_WIDTH, GRID_HEIGHT, listOf(Zone(1, "Пустая зона", emptyList())))
        val engine = engineWithPlan(plan)

        val response = engine.command(Command.START, "r1")

        assertTrue(!response.accepted, "план без клеток не должен допускать запуск уборки")
    }

    @Test
    fun `progress grows monotonically along the route`() = runBlocking {
        val plan = CleaningPlan(
            GRID_WIDTH, GRID_HEIGHT,
            listOf(Zone(1, "A", listOf(Cell(1, 1), Cell(2, 1), Cell(3, 1))))
        )
        val engine = engineWithPlan(plan)

        engine.command(Command.START, "r1")
        val progressSeen = mutableListOf<Double>()
        repeat(400) {
            engine.tick()
            progressSeen.add(engine.snapshot().progress)
            if (engine.snapshot().state != RobotState.CLEANING.name) return@repeat
        }
        assertEquals(progressSeen, progressSeen.sorted(), "progress не должен убывать")
        assertEquals(1.0, progressSeen.last())
    }
}
