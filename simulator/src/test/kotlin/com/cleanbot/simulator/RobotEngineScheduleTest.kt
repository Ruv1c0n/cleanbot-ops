package com.cleanbot.simulator

import com.cleanbot.simulator.model.CleaningPlan
import com.cleanbot.simulator.model.Cell
import com.cleanbot.simulator.model.GRID_HEIGHT
import com.cleanbot.simulator.model.GRID_WIDTH
import com.cleanbot.simulator.model.Zone
import com.cleanbot.simulator.robot.Command
import com.cleanbot.simulator.robot.RobotEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Автозапуск по расписанию: scheduledStart стартует только из IDLE, задаёт
 * mode уборки из записи расписания и уважает запрет пустого плана.
 */
class RobotEngineScheduleTest {

    private fun testPlan() = CleaningPlan(
        GRID_WIDTH, GRID_HEIGHT,
        listOf(Zone(1, "A", listOf(Cell(1, 1), Cell(2, 1))))
    )

    @Test
    fun `scheduledStart from IDLE accepts and records mode`() = runBlocking {
        val engine = RobotEngine("t", "T", faultChance = 0.0).apply { setPlan(testPlan()) }
        val modes = mutableListOf<String>()
        engine.onSessionFinished = { _, _, _, mode, _ -> modes += mode }

        val response = engine.scheduledStart("wet_mopping", "req1")
        assertTrue(response.accepted)
        assertEquals("CLEANING", engine.snapshot().state)

        // Сессия, прерванная abort-ом, должна выйти с mode из расписания
        engine.command(Command.ABORT, "req2")
        assertEquals(listOf("wet_mopping"), modes)
    }

    @Test
    fun `scheduledStart in non-IDLE state is rejected`() = runBlocking {
        val engine = RobotEngine("t", "T", faultChance = 0.0).apply { setPlan(testPlan()) }
        engine.scheduledStart("dry_sweep", "req1")

        val second = engine.scheduledStart("wet_mopping", "req2")
        assertFalse(second.accepted)
        assertEquals("ILLEGAL_STATE", second.reason)
        assertEquals("CLEANING", engine.snapshot().state)
    }

    @Test
    fun `scheduledStart with empty plan is rejected`() = runBlocking {
        val engine = RobotEngine("t", "T", faultChance = 0.0) // план не задан

        val response = engine.scheduledStart("dry_sweep", "req1")
        assertFalse(response.accepted)
        assertEquals("План не задан", response.reason)
        assertEquals("IDLE", engine.snapshot().state)
    }

    @Test
    fun `rejected scheduledStart does not change mode`() = runBlocking {
        val engine = RobotEngine("t", "T", faultChance = 0.0) // план не задан → START отклонится
        val modes = mutableListOf<String>()
        engine.onSessionFinished = { _, _, _, mode, _ -> modes += mode }

        val rejected = engine.scheduledStart("wet_mopping", "req1")
        assertFalse(rejected.accepted)
        assertEquals("IDLE", engine.snapshot().state)

        // mode не должен был смениться при отклонённом автозапуске: последующая
        // обычная уборка выходит со старым режимом (dry_sweep по умолчанию).
        engine.setPlan(testPlan())
        engine.command(Command.START, "req2")
        engine.command(Command.ABORT, "req3")
        assertEquals(listOf("dry_sweep"), modes)
    }
}
