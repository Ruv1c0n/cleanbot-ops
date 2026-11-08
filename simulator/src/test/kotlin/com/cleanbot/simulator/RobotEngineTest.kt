package com.cleanbot.simulator

import com.cleanbot.simulator.robot.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class RobotEngineTest {

    /** Небольшой непустой план: без него START теперь отклоняется. */
    private fun RobotEngine.withTestPlan(): RobotEngine = apply {
        setPlan(
            com.cleanbot.simulator.model.CleaningPlan(
                com.cleanbot.simulator.model.GRID_WIDTH,
                com.cleanbot.simulator.model.GRID_HEIGHT,
                listOf(com.cleanbot.simulator.model.Zone(1, "A", listOf(com.cleanbot.simulator.model.Cell(1, 1), com.cleanbot.simulator.model.Cell(2, 1))))
            )
        )
    }

    @Test
    fun `full cleaning ends without abort`() = runBlocking {
        val engine = RobotEngine("t", "T", faultChance = 0.0).withTestPlan()
        val aborted = mutableListOf<Boolean>()
        engine.onSessionFinished = { _, _, _, _, a -> aborted += a }

        assertTrue(engine.command(Command.START, "r1").accepted)
        repeat(60) { engine.tick() }

        assertEquals(listOf(false), aborted)
        // План из 2 клеток убирается за пару тиков: за 60 тиков робот успевает вернуться
        // и полностью зарядиться, поэтому IDLE — тоже корректный финал без прерывания.
        assertTrue(engine.snapshot().state in setOf("RETURNING", "CHARGING", "IDLE"))
    }

    @Test
    fun `illegal command is rejected`() = runBlocking {
        val engine = RobotEngine("t", "T", faultChance = 0.0).withTestPlan()
        engine.command(Command.START, "r1")
        val second = engine.command(Command.START, "r2")
        assertFalse(second.accepted)
        assertEquals("ILLEGAL_STATE", second.reason)
    }

    @Test
    fun `reset leads from error to idle`() {
        val next = RobotFsm.applyCommand(RobotState.ERROR, Command.RESET)
        assertEquals(TransitionResult.Accepted(RobotState.IDLE), next)
    }
}