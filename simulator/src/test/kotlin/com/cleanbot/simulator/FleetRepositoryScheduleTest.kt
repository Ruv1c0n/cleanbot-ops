package com.cleanbot.simulator

import com.cleanbot.simulator.model.CleaningPlan
import com.cleanbot.simulator.model.Cell
import com.cleanbot.simulator.model.GRID_HEIGHT
import com.cleanbot.simulator.model.GRID_WIDTH
import com.cleanbot.simulator.model.Schedule
import com.cleanbot.simulator.model.Zone
import com.cleanbot.simulator.repository.FleetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Автозапуск по расписанию на уровне репозитория (checkSchedules):
 * дедуп в пределах минуты и повторное срабатывание еженедельной записи
 * на следующей неделе — ключ lastScheduleFire хранит дату, а не день недели.
 */
class FleetRepositoryScheduleTest {

    // Unconfined: launch внутри checkSchedules выполняется синхронно,
    // состояние и лог после вызова детерминированы.
    // faultChance = 0.0: штатная авария (~1%/тик уборки) уводит робота в ERROR,
    // и runToIdle периодически не дожидается IDLE — так CI упал на 2026-11-10.
    private fun repo() = FleetRepository(
        CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
        faultChance = 0.0
    )

    private fun tinyPlan() = CleaningPlan(
        GRID_WIDTH, GRID_HEIGHT,
        listOf(Zone(1, "A", listOf(Cell(1, 1), Cell(2, 1))))
    )

    private fun mondayEntry() = Schedule("s1", "cleanbot-01", 1, "06:00", "dry_sweep")

    /** Пустые расписания у остальных роботов, чтобы их автозапуски не шумели в лог. */
    private fun isolate(repo: FleetRepository) {
        listOf("cleanbot-02", "cleanbot-03").forEach {
            assertEquals(null, repo.replaceSchedule(it, emptyList()))
        }
    }

    private fun fired(repo: FleetRepository, robotId: String) =
        repo.getLogs(robotId)!!.count { "Запуск по расписанию" in it.message }

    private fun skipped(repo: FleetRepository, robotId: String) =
        repo.getLogs(robotId)!!.count { "пропущено" in it.message }

    /** Догоняем робота до IDLE тиками (2-клеточный план убирается и заряжается за десятки тиков). */
    private fun runToIdle(repo: FleetRepository, robotId: String) = runBlocking {
        val robot = repo.findRobot(robotId)!!
        repeat(120) { robot.tick() }
        assertEquals("IDLE", robot.snapshot().state)
    }

    // 2026-11-09 и 2026-11-16 — понедельники (dayOfWeek == 1)
    private val week1 = LocalDateTime.of(2026, 11, 9, 6, 0)
    private val week2 = LocalDateTime.of(2026, 11, 16, 6, 0)

    @Test
    fun `weekly entry fires again next week`() {
        val repo = repo()
        isolate(repo)
        assertEquals(null, repo.replacePlan("cleanbot-01", tinyPlan()))
        assertEquals(null, repo.replaceSchedule("cleanbot-01", listOf(mondayEntry())))

        repo.checkSchedules(week1)
        assertEquals(1, fired(repo, "cleanbot-01"))

        runToIdle(repo, "cleanbot-01")

        // Регресс: раньше ключ был "день недели/время" — та же минута следующей
        // недели совпадала с сохранённым ключом, и запись пропускалась молча.
        repo.checkSchedules(week2)
        assertEquals(2, fired(repo, "cleanbot-01"))
    }

    @Test
    fun `repeat within the same minute is suppressed`() {
        val repo = repo()
        isolate(repo)
        assertEquals(null, repo.replacePlan("cleanbot-01", tinyPlan()))
        assertEquals(null, repo.replaceSchedule("cleanbot-01", listOf(mondayEntry())))

        repo.checkSchedules(week1)
        repo.checkSchedules(week1) // цикл тикает чаще минуты — дубль подавляется
        assertEquals(1, fired(repo, "cleanbot-01"))
    }

    @Test
    fun `every entry of the same minute gets its attempt`() {
        val repo = repo()
        isolate(repo)
        assertEquals(null, repo.replacePlan("cleanbot-01", tinyPlan()))
        assertEquals(null, repo.replaceSchedule("cleanbot-01", listOf(
            mondayEntry(),
            Schedule("s2", "cleanbot-01", 1, "06:00", "wet_mopping")
        )))

        repo.checkSchedules(week1)
        // первая запись стартует, вторая получает попытку (робот уже CLEANING → «пропущено»)
        assertEquals(1, fired(repo, "cleanbot-01"))
        assertEquals(1, skipped(repo, "cleanbot-01"))
    }
}
