package com.cleanbot.app.data.repository

import com.cleanbot.app.data.model.RobotSummary
import com.cleanbot.app.data.model.Session
import com.cleanbot.app.data.remote.RobotApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Юнит-тесты RobotRepository (без сети и Room: db = null, api — фейк):
 * слияние fleet из refreshFleet и офлайн-фолбэк sessions().
 */
class RobotRepositoryTest {

    /** Фейк API: отвечает заготовленным списком или бросает исключение. */
    private class FakeApi(
        private val summaries: List<RobotSummary> = emptyList(),
        private val sessions: List<Session>? = null,
        private val failRobots: Boolean = false,
        private val failSessions: Boolean = false
    ) : RobotApi() {
        override suspend fun listRobots(): List<RobotSummary> {
            if (failRobots) throw IOException("нет сети")
            return summaries
        }

        override suspend fun getSessions(
            id: String, from: String?, to: String?
        ): List<Session> {
            if (failSessions) throw IOException("нет сети")
            return sessions.orEmpty()
        }
    }

    private fun summary(id: String, state: String, battery: Int, error: String? = null) =
        RobotSummary(id = id, name = "Robot $id", state = state, battery = battery, error = error)

    @Test
    fun `refreshFleet merges summaries into state`() = runTest {
        val repo = RobotRepository(
            api = FakeApi(summaries = listOf(
                summary("r1", "CLEANING", 77),
                summary("r2", "IDLE", 50)
            )),
            db = null
        )
        repo.refreshFleet()

        val r1 = repo.robots.value["r1"]
        assertEquals("CLEANING", r1?.state)
        assertEquals(77, r1?.battery)
        assertEquals("Robot r1", r1?.name)
        assertEquals("IDLE", repo.robots.value["r2"]?.state)
    }

    @Test
    fun `sessions network success returns fromCache false`() = runTest {
        val sessions = listOf(
            Session("id1", "r1", "2026-11-01T10:00:00Z", "2026-11-01T10:30:00Z", 30, 12.0, "dry_sweep", false)
        )
        val repo = RobotRepository(api = FakeApi(sessions = sessions), db = null)

        val result = repo.sessions("r1")

        assertTrue(!result.fromCache)
        assertEquals(1, result.sessions.size)
    }

    @Test
    fun `sessions network failure falls back to empty cache without db`() = runTest {
        val repo = RobotRepository(api = FakeApi(failSessions = true), db = null)

        val result = repo.sessions("r1")

        // Room-кэша нет: пусто, но fromCache=true — UI покажет офлайн-баннер, не упадёт
        assertTrue(result.fromCache)
        assertTrue(result.sessions.isEmpty())
    }
}
