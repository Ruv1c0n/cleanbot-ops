package com.cleanbot.simulator.repository

import com.cleanbot.simulator.model.*
import com.cleanbot.simulator.robot.Command
import com.cleanbot.simulator.robot.RobotEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.random.Random

class FleetRepository(private val scope: CoroutineScope) {

    val robots: List<RobotEngine> = listOf(
        RobotEngine(id = "cleanbot-01", name = "CleanBot 01"),
        RobotEngine(id = "cleanbot-02", name = "CleanBot 02"),
        RobotEngine(id = "cleanbot-03", name = "CleanBot 03")
    )

    private val schedules = ConcurrentHashMap<String, MutableList<Schedule>>()
    private val sessions = ConcurrentHashMap<String, MutableList<Session>>()
    private val logs = ConcurrentHashMap<String, ConcurrentLinkedDeque<LogEntry>>()
    private val settings = ConcurrentHashMap<String, RobotSettings>()

    /** Транслирует срез телеметрии по всем роботам разом, раз в секунду. */
    val telemetryFlow = MutableSharedFlow<List<Telemetry>>(replay = 1)

    init {
        robots.forEach { robot ->
            schedules[robot.id] = defaultSchedule(robot.id)
            sessions[robot.id] = generateHistory(robot.id)
            logs[robot.id] = ConcurrentLinkedDeque()
            settings[robot.id] = RobotSettings(robot.id, robot.name, "ws://10.0.2.2:8080")

            robot.onLog = { level, message -> appendLog(robot.id, level, message) }
            robot.onSessionFinished = { startedAt, endedAt, area, mode, aborted ->
                addSession(robot.id, startedAt, endedAt, area, mode, aborted)
            }
        }
    }

    fun findRobot(id: String): RobotEngine? = robots.find { it.id == id }

    suspend fun allSummaries(): List<RobotSummary> = robots.map { it.summary() }

    suspend fun sendCommand(robotId: String, rawCommand: String, requestId: String): CommandResponse? {
        val robot = findRobot(robotId) ?: return null
        val command = Command.parse(rawCommand)
            ?: return CommandResponse(accepted = false, requestId = requestId, reason = "UNKNOWN_COMMAND")
        return robot.command(command, requestId)
    }

    fun getSchedule(robotId: String): List<Schedule>? = schedules[robotId]

    fun replaceSchedule(robotId: String, entries: List<Schedule>): Boolean {
        if (!schedules.containsKey(robotId)) return false
        schedules[robotId] = entries.toMutableList()
        return true
    }

    fun getSessions(robotId: String, from: Instant?, to: Instant?): List<Session>? {
        val all = sessions[robotId] ?: return null
        return all.filter { s ->
            val started = Instant.parse(s.startedAt)
            (from == null || !started.isBefore(from)) && (to == null || !started.isAfter(to))
        }.sortedByDescending { it.startedAt }
    }

    private fun addSession(robotId: String, startedAt: Instant, endedAt: Instant, area: Double, mode: String, aborted: Boolean) {
        val list = sessions.getOrPut(robotId) { mutableListOf() }
        synchronized(list) {
            list.add(
                Session(
                    id = UUID.randomUUID().toString(),
                    robotId = robotId,
                    startedAt = startedAt.toString(),
                    endedAt = endedAt.toString(),
                    durationMinutes = ChronoUnit.MINUTES.between(startedAt, endedAt).toInt().coerceAtLeast(1),
                    areaCleaned = Math.round(area * 10) / 10.0,
                    mode = mode,
                    aborted = aborted
                )
            )
        }
    }

    fun getLogs(robotId: String): List<LogEntry>? =
        logs[robotId]?.toList()?.sortedByDescending { it.timestamp }

    private fun appendLog(robotId: String, level: String, message: String) {
        val deque = logs.getOrPut(robotId) { ConcurrentLinkedDeque() }
        deque.addFirst(LogEntry(Instant.now().toString(), robotId, level, message))
        while (deque.size > 200) deque.removeLast()
    }

    fun getSettings(robotId: String): RobotSettings? = settings[robotId]

    fun updateSettings(robotId: String, newSettings: RobotSettings): Boolean {
        if (!settings.containsKey(robotId)) return false
        settings[robotId] = newSettings
        return true
    }

    /** Запускает фоновые циклы: тик каждого робота раз в секунду + рассылку телеметрии. */
    fun start() {
        robots.forEach { robot ->
            scope.launch {
                while (isActive) {
                    robot.tick()
                    delay(1000)
                }
            }
        }
        scope.launch {
            while (isActive) {
                telemetryFlow.emit(robots.map { it.snapshot() })
                delay(1000)
            }
        }
    }

    private fun defaultSchedule(robotId: String): MutableList<Schedule> = mutableListOf(
        Schedule(UUID.randomUUID().toString(), robotId, 1, "06:00", "dry_sweep"),
        Schedule(UUID.randomUUID().toString(), robotId, 3, "06:00", "wet_mopping"),
        Schedule(UUID.randomUUID().toString(), robotId, 5, "22:00", "dry_sweep")
    )

    /** ~40 прошлых сессий за последний месяц — для экрана статистики. */
    private fun generateHistory(robotId: String): MutableList<Session> {
        val now = Instant.now()
        val list = mutableListOf<Session>()
        repeat(40) { i ->
            val start = now.minus((i * 17).toLong(), ChronoUnit.HOURS)
            val durationMin = Random.nextInt(25, 70)
            val end = start.plus(durationMin.toLong(), ChronoUnit.MINUTES)
            list.add(
                Session(
                    id = UUID.randomUUID().toString(),
                    robotId = robotId,
                    startedAt = start.toString(),
                    endedAt = end.toString(),
                    durationMinutes = durationMin,
                    areaCleaned = Math.round(Random.nextDouble(80.0, 450.0) * 10) / 10.0,
                    mode = if (Random.nextBoolean()) "dry_sweep" else "wet_mopping",
                    aborted = Random.nextInt(100) < 8
                )
            )
        }
        return list
    }
}
