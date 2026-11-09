package com.cleanbot.app.ui.editor

import com.cleanbot.app.MainDispatcherRule
import com.cleanbot.app.data.model.Cell
import com.cleanbot.app.data.model.CleaningPlan
import com.cleanbot.app.data.model.Zone
import com.cleanbot.app.data.remote.RobotApi
import com.cleanbot.app.data.repository.RobotRepository
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Юнит-тесты редактора планов (из ревью: главный непротестированный стейт-машин
 * app-слоя). Сеть не нужна: load() получает план с фейк-API, дальше все
 * проверяемые функции — чистые операции над локальным эскизом.
 */
class PlanEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Фейк API: отдаёт дефолтный план, других методов эти тесты не трогают. */
    private open class PlanOnlyApi(private val plan: CleaningPlan) : RobotApi() {
        override suspend fun getPlan(id: String): CleaningPlan = plan
    }

    private fun defaultPlan() = CleaningPlan(
        gridW = 20, gridH = 14,
        zones = listOf(Zone(1, "Гостиная", listOf(Cell(0, 0))))
    )

    private fun vm(plan: CleaningPlan = defaultPlan()): PlanEditorViewModel {
        val editor = PlanEditorViewModel(
            "test-robot",
            repository = RobotRepository(api = PlanOnlyApi(plan), db = null)
        )
        editor.load()
        return editor
    }

    @Test
    fun `load populates sketch and activates first zone`() = runTest {
        val editor = vm()
        advanceUntilIdle()

        assertEquals(listOf<Cell>(Cell(0, 0)), editor.sketch.value!!.zones.single().cells)
        assertEquals(0, editor.activeZoneIndex.value)
        assertFalse(editor.hasUnsavedChanges)
    }

    @Test
    fun `toggleCell adds and removes cell in active zone`() = runTest {
        val editor = vm()
        advanceUntilIdle()
        editor.toggleCell(1, 1)
        // В плане с сервера уже была клетка (0,0), добавилась (1,1)
        assertEquals(
            setOf(Cell(0, 0), Cell(1, 1)),
            editor.sketch.value!!.zones.single().cells.toSet()
        )

        // Повторный тап по той же клетке снимает её
        editor.toggleCell(1, 1)
        assertEquals(listOf(Cell(0, 0)), editor.sketch.value!!.zones.single().cells)
    }

    @Test
    fun `toggleCell in foreign zone is rejected with message`() = runTest {
        val editor = vm()
        advanceUntilIdle()
        editor.createZone("Вторая")
        editor.toggleCell(0, 0)   // клетка занята «Гостиной»

        assertTrue(editor.message.value!!.contains("занята"))
        assertEquals(listOf(Cell(0, 0)), editor.sketch.value!!.zones[0].cells)
        assertTrue(editor.sketch.value!!.zones[1].cells.isEmpty())
    }

    @Test
    fun `toggleCell without active zone shows hint`() = runTest {
        val editor = vm()
        advanceUntilIdle()
        editor.setActiveZone(-1)
        editor.toggleCell(1, 1)

        assertEquals("Сначала выберите или создайте зону", editor.message.value)
    }

    @Test
    fun `createZone assigns next id and becomes active`() = runTest {
        val editor = vm()
        advanceUntilIdle()
        val index = editor.createZone()

        assertEquals(index, editor.activeZoneIndex.value)
        assertEquals(2, editor.sketch.value!!.zones[index].id)
        assertEquals("Зона 2", editor.sketch.value!!.zones[index].name)
    }

    @Test
    fun `deleteZone of last zone leaves no active zone`() = runTest {
        val editor = vm()
        advanceUntilIdle()
        editor.deleteZone(0)

        assertTrue(editor.sketch.value!!.zones.isEmpty())
        assertEquals(-1, editor.activeZoneIndex.value)
    }

    @Test
    fun `hasUnsavedChanges tracks sketch mutations and reset`() = runTest {
        val editor = vm()
        advanceUntilIdle()
        assertFalse(editor.hasUnsavedChanges)

        editor.toggleCell(3, 3)
        assertTrue(editor.hasUnsavedChanges)

        editor.reset()
        assertFalse(editor.hasUnsavedChanges)
        assertNull(editor.message.value)
        assertEquals(listOf<Cell>(Cell(0, 0)), editor.sketch.value!!.zones.single().cells)
    }
}
