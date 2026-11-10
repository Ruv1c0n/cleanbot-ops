package com.cleanbot.simulator.repository

import com.cleanbot.simulator.model.*
import com.cleanbot.simulator.robot.Command
import com.cleanbot.simulator.robot.RobotEngine
import com.cleanbot.simulator.robot.dueScheduleEntries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.random.Random

class FleetRepository(private val scope: CoroutineScope) {

    private companion object {
        /** Как часто проверяем расписание (цикл должен попадать в минуту срабатывания). */
        const val SCHEDULE_CHECK_INTERVAL_MS = 15_000L
    }

    val robots: List<RobotEngine> = listOf(
        RobotEngine(id = "cleanbot-01", name = "CleanBot 01"),
        RobotEngine(id = "cleanbot-02", name = "CleanBot 02"),
        RobotEngine(id = "cleanbot-03", name = "CleanBot 03")
    )

    private val schedules = ConcurrentHashMap<String, MutableList<Schedule>>()
    private val sessions = ConcurrentHashMap<String, MutableList<Session>>()
    private val logs = ConcurrentHashMap<String, ConcurrentLinkedDeque<LogEntry>>()
    private val settings = ConcurrentHashMap<String, RobotSettings>()
    private val plans = ConcurrentHashMap<String, CleaningPlan>()

    /** Ключ последнего срабатывания расписания на робота ("дата/HH:mm") — защита от дублей внутри минуты. */
    private val lastScheduleFire = ConcurrentHashMap<String, String>()

    /** Транслирует срез телеметрии по всем роботам разом, раз в секунду. */
    val telemetryFlow = MutableSharedFlow<List<Telemetry>>(replay = 1)

    init {
        robots.forEach { robot ->
            schedules[robot.id] = defaultSchedule(robot.id)
            sessions[robot.id] = generateHistory(robot.id)
            val plan = defaultPlan(robot.id)
            plans[robot.id] = plan
            robot.setPlan(plan)
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

    /** Заменяет расписание целиком. Возвращает null при успехе или текст ошибки валидации. */
    fun replaceSchedule(robotId: String, entries: List<Schedule>): String? {
        if (!schedules.containsKey(robotId)) return "Неизвестный робот: $robotId"
        val error = validateScheduleEntries(robotId, entries)
        if (error != null) return error
        schedules[robotId] = entries.toMutableList()
        return null
    }

    fun getPlan(robotId: String): CleaningPlan? = plans[robotId]

    /** Заменяет план целиком. Возвращает null при успехе или текст ошибки валидации. */
    fun replacePlan(robotId: String, plan: CleaningPlan): String? {
        if (!plans.containsKey(robotId)) return "Неизвестный робот: $robotId"
        val error = validatePlan(plan)
        if (error != null) return error
        plans[robotId] = plan
        findRobot(robotId)?.setPlan(plan)
        appendLog(robotId, "INFO", "План уборки заменён: зон ${plan.zones.size}, клеток ${plan.zones.sumOf { it.cells.size }}")
        return null
    }

    fun getSessions(robotId: String, from: Instant?, to: Instant?): List<Session>? {
        val list = sessions[robotId] ?: return null
        // Снимок под тем же локом, что и addSession: корутина тика может добавить
        // сессию прямо во время итерации здесь — иначе ConcurrentModificationException.
        val snapshot = synchronized(list) { list.toList() }
        return snapshot.filter { s ->
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
        val robot = findRobot(robotId) ?: return false
        settings[robotId] = newSettings
        robot.name = newSettings.displayName
        return true
    }

    /** Запускает фоновые циклы: тик каждого робота раз в секунду, рассылка телеметрии и проверка расписания. */
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
        scope.launch {
            while (isActive) {
                checkSchedules()
                delay(SCHEDULE_CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Автозапуск по расписанию: в минуту срабатывания (день недели + "HH:mm", локальная
     * таймзона сервера) каждой записи срабатывания даётся попытка scheduledStart.
     * lastScheduleFire не даёт сработать повторно в ту же минуту (цикл тикает чаще).
     * Ключ хранит дату, а не только день недели: еженедельная запись с ключом
     * "день недели/время" сработала бы один раз за жизнь процесса.
     */
    fun checkSchedules(now: LocalDateTime = LocalDateTime.now()) {
        val day = now.dayOfWeek.value
        val time = now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
        val key = "${now.toLocalDate()}/$time"
        robots.forEach { robot ->
            val due = dueScheduleEntries(schedules[robot.id].orEmpty(), day, time)
            if (due.isEmpty() || lastScheduleFire[robot.id] == key) return@forEach
            lastScheduleFire[robot.id] = key
            // Несколько записей в одну минуту: каждая получает свою попытку старта,
            // вторая не теряется молча (не-IDLE/пустой план → WARN «пропущено»).
            due.forEach { entry ->
                scope.launch {
                    val response = robot.scheduledStart(entry.mode, UUID.randomUUID().toString())
                    if (response.accepted) {
                        appendLog(robot.id, "INFO", "Запуск по расписанию ($day, $time): mode=${entry.mode}")
                    } else {
                        appendLog(robot.id, "WARN", "Расписание ($day, $time) пропущено: ${response.reason}")
                    }
                }
            }
        }
    }

    private fun defaultSchedule(robotId: String): MutableList<Schedule> = mutableListOf(
        Schedule(UUID.randomUUID().toString(), robotId, 1, "06:00", "dry_sweep"),
        Schedule(UUID.randomUUID().toString(), robotId, 3, "06:00", "wet_mopping"),
        Schedule(UUID.randomUUID().toString(), robotId, 5, "22:00", "dry_sweep")
    )

    /** Дефолтный план: 3 зоны; для разных роботов немного сдвинут по x, чтобы планы отличались. */
    private fun defaultPlan(robotId: String): CleaningPlan {
        val shiftX = robots.indexOfFirst { it.id == robotId }.coerceAtLeast(0) * 2
        fun rect(x1: Int, x2: Int, y1: Int, y2: Int): List<Cell> =
            (y1..y2).flatMap { y -> (x1..x2).map { x -> Cell(x + shiftX, y) } }
        return CleaningPlan(
            gridW = GRID_WIDTH,
            gridH = GRID_HEIGHT,
            zones = listOf(
                Zone(1, "Гостиная", rect(1, 6, 1, 3)),
                Zone(2, "Спальня", rect(9, 12, 4, 7)),
                Zone(3, "Кухня", rect(2, 4, 9, 11))
            )
        )
    }

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

/**
 * Валидация плана: сетка строго 20×14, клетки в границах, без дублей, зона ≥ 1 клетки.
 * Возвращает текст ошибки или null, если план корректен.
 */
fun validatePlan(plan: CleaningPlan): String? {
    if (plan.gridW != GRID_WIDTH || plan.gridH != GRID_HEIGHT) {
        return "Сетка плана должна быть $GRID_WIDTH×$GRID_HEIGHT, получено ${plan.gridW}×${plan.gridH}"
    }
    val seen = mutableSetOf<Cell>()
    plan.zones.forEachIndexed { index, zone ->
        if (zone.cells.isEmpty()) return "Зона «${zone.name}» (#$index) должна содержать хотя бы одну клетку"
        for (cell in zone.cells) {
            if (cell.x !in 0 until GRID_WIDTH || cell.y !in 0 until GRID_HEIGHT) {
                return "Клетка (${cell.x}, ${cell.y}) вне сетки $GRID_WIDTH×$GRID_HEIGHT"
            }
            if (!seen.add(cell)) return "Дублирующаяся клетка (${cell.x}, ${cell.y})"
        }
    }
    return null
}

/** Режимы уборки, допустимые в расписании (тот же набор, что в app и дефолтных данных). */
private val VALID_SCHEDULE_MODES = setOf("dry_sweep", "wet_mopping")

/** Время записи расписания: "HH:mm", часы 00–23, минуты 00–59. */
private val SCHEDULE_TIME_REGEX = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

/**
 * Валидация расписания: день недели 1..7 (ISO, пн=1), время "HH:mm", режим из допустимого
 * набора, robotId каждой записи совпадает с id робота в пути (иначе запись запускала бы
 * чужого робота). Возвращает текст ошибки или null, если расписание корректно.
 */
fun validateScheduleEntries(robotId: String, entries: List<Schedule>): String? {
    entries.forEachIndexed { index, entry ->
        val label = "Запись #${index + 1}"
        if (entry.dayOfWeek !in 1..7) {
            return "$label: dayOfWeek должен быть в диапазоне 1..7 (пн=1), получено ${entry.dayOfWeek}"
        }
        if (!SCHEDULE_TIME_REGEX.matches(entry.time)) {
            return "$label: время должно быть в формате HH:mm (00:00–23:59), получено \"${entry.time}\""
        }
        if (entry.mode !in VALID_SCHEDULE_MODES) {
            return "$label: режим должен быть одним из ${VALID_SCHEDULE_MODES.joinToString()}, получено \"${entry.mode}\""
        }
        if (entry.robotId != robotId) {
            return "$label: robotId в записи (${entry.robotId}) не совпадает с id в пути ($robotId)"
        }
    }
    return null
}
