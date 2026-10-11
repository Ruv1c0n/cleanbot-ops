package com.cleanbot.simulator

import com.cleanbot.simulator.robot.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class RobotEngineTest {

    @Test
    fun `full cleaning ends without abort`() = runBlocking {
        val engine = RobotEngine("t", "T", faultChance = 0.0)
        val aborted = mutableListOf<Boolean>()
        engine.onSessionFinished = { _, _, _, _, a -> aborted += a }

        assertTrue(engine.command(Command.START, "r1").accepted)
        repeat(60) { engine.tick() }

        assertEquals(listOf(false), aborted)
        assertTrue(engine.snapshot().state in setOf("RETURNING", "CHARGING"))
    }

    @Test
    fun `illegal command is rejected`() = runBlocking {
        val engine = RobotEngine("t", "T", faultChance = 0.0)
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