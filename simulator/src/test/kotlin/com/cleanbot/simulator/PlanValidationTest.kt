package com.cleanbot.simulator

import com.cleanbot.simulator.model.Cell
import com.cleanbot.simulator.model.CleaningPlan
import com.cleanbot.simulator.model.GRID_HEIGHT
import com.cleanbot.simulator.model.GRID_WIDTH
import com.cleanbot.simulator.model.Zone
import com.cleanbot.simulator.repository.FleetRepository
import com.cleanbot.simulator.repository.validatePlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Валидация плана уборки (FleetRepository.replacePlan / validatePlan):
 * сетка строго 20×14, клетки в границах, без дублей, зона ≥ 1 клетки.
 */
class PlanValidationTest {

    private fun repo() = FleetRepository(CoroutineScope(SupervisorJob()))

    private fun zone(id: Int = 1, name: String = "Зона", cells: List<Cell>) = Zone(id, name, cells)

    private fun plan(zones: List<Zone>, gridW: Int = GRID_WIDTH, gridH: Int = GRID_HEIGHT) =
        CleaningPlan(gridW, gridH, zones)

    @Test
    fun `valid plan with several zones passes`() {
        val p = plan(listOf(
            zone(id = 1, cells = listOf(Cell(0, 0), Cell(1, 0))),
            zone(id = 2, cells = listOf(Cell(5, 6)))
        ))
        assertNull(validatePlan(p))
    }

    @Test
    fun `wrong grid size is rejected`() {
        val p = plan(listOf(zone(cells = listOf(Cell(0, 0)))), gridW = 24, gridH = 12)
        val error = validatePlan(p)
        assertNotNull(error)
        assertTrue("20×14" in error, "ожидали упоминание сетки 20×14: $error")
    }

    @Test
    fun `cell out of grid bounds is rejected`() {
        val p = plan(listOf(zone(cells = listOf(Cell(GRID_WIDTH, 0)))))
        val error = validatePlan(p)
        assertNotNull(error)
        assertTrue("вне сетки" in error, "ожидали ошибку границ: $error")
    }

    @Test
    fun `negative cell is rejected`() {
        val p = plan(listOf(zone(cells = listOf(Cell(-1, 0)))))
        assertNotNull(validatePlan(p))
    }

    @Test
    fun `duplicate cells are rejected`() {
        val p = plan(listOf(zone(cells = listOf(Cell(2, 2), Cell(2, 2)))))
        val error = validatePlan(p)
        assertNotNull(error)
        assertTrue("дублир" in error.lowercase(), "ожидали ошибку дублей: $error")
    }

    @Test
    fun `duplicate across zones is rejected`() {
        val p = plan(listOf(
            zone(id = 1, cells = listOf(Cell(3, 3))),
            zone(id = 2, cells = listOf(Cell(3, 3)))
        ))
        assertNotNull(validatePlan(p))
    }

    @Test
    fun `empty zone is rejected`() {
        val p = plan(listOf(zone(name = "Пустая", cells = emptyList())))
        val error = validatePlan(p)
        assertNotNull(error)
        assertTrue("хотя бы одну клетку" in error, "ожидали ошибку пустой зоны: $error")
    }

    @Test
    fun `every robot has a valid default plan`() {
        val repository = repo()
        for (robot in listOf("cleanbot-01", "cleanbot-02", "cleanbot-03")) {
            val default = repository.getPlan(robot)
            assertNotNull(default, "у $robot должен быть дефолтный план")
            assertNull(validatePlan(default), "дефолтный план $robot должен быть валиден")
            assertEquals(GRID_WIDTH, default.gridW)
            assertEquals(GRID_HEIGHT, default.gridH)
            assertTrue(default.zones.size in 2..3, "ожидается 2–3 зоны")
        }
    }

    @Test
    fun `replacePlan stores valid plan and returns it via getPlan`() {
        val repository = repo()
        val newPlan = plan(listOf(zone(id = 1, name = "Прихожая", cells = listOf(Cell(0, 13), Cell(19, 0)))))
        assertNull(repository.replacePlan("cleanbot-01", newPlan))
        assertEquals(newPlan, repository.getPlan("cleanbot-01"))
    }

    @Test
    fun `replacePlan rejects invalid plan without storing it`() {
        val repository = repo()
        val original = repository.getPlan("cleanbot-02")!!
        val invalid = plan(listOf(zone(cells = listOf(Cell(50, 50)))))
        assertNotNull(repository.replacePlan("cleanbot-02", invalid))
        assertEquals(original, repository.getPlan("cleanbot-02"), "невалидный план не должен сохраняться")
    }

    @Test
    fun `replacePlan for unknown robot returns error`() {
        val repository = repo()
        val p = plan(listOf(zone(cells = listOf(Cell(0, 0)))))
        assertNotNull(repository.replacePlan("no-such-robot", p))
    }
}
