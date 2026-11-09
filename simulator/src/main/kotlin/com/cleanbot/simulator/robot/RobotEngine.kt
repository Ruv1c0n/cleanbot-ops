package com.cleanbot.simulator.robot

import com.cleanbot.simulator.model.CleaningPlan
import com.cleanbot.simulator.model.CommandResponse
import com.cleanbot.simulator.model.Position
import com.cleanbot.simulator.model.RobotSummary
import com.cleanbot.simulator.model.Telemetry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.math.round
import kotlin.random.Random

/**
 * Один робот-двойник. Всё изменяемое состояние спрятано за mutex,
 * т.к. к нему обращаются одновременно REST-хендлеры и фоновый цикл тика.
 */
class RobotEngine(
    val id: String,
    @Volatile var name: String,
    private val dockPosition: Position = Position(0, 0),
    private val faultChance: Double = 0.01
) {
    private val mutex = Mutex()

    private var state: RobotState = RobotState.IDLE
    private var battery: Int = 100
    private var progress: Double = 0.0
    private var position: Position = dockPosition
    private var error: RobotError? = null
    private var mode: String = "dry_sweep"

    private var sessionStartedAt: Instant? = null
    private var sessionAreaAccum: Double = 0.0

    /** Маршрут текущей уборки: клетки зон плана «змейкой», зона за зоной. Непуст при START (пустой план отклоняется). */
    private var route: List<Position> = emptyList()
    private var routeIndex: Int = 0

    var onSessionFinished: (suspend (startedAt: Instant, endedAt: Instant, area: Double, mode: String, aborted: Boolean) -> Unit)? = null
    var onLog: (suspend (level: String, message: String) -> Unit)? = null

    /** Текущий план уборки; назначается репозиторием, применяется при следующем START. */
    @Volatile
    private var currentPlan: CleaningPlan? = null

    /** Назначает план уборки; применяется при следующем START. */
    fun setPlan(plan: CleaningPlan) {
        currentPlan = plan
    }

    suspend fun command(cmd: Command, requestId: String): CommandResponse = mutex.withLock {
        commandLocked(cmd, requestId)
    }

    /** Логика команды при уже захваченном мьютексе (общая для command и scheduledStart). */
    private suspend fun commandLocked(cmd: Command, requestId: String): CommandResponse {
        // Пустой план не даёт запустить уборку: предпросмотр в app блокирует Старт,
        // здесь дублируем отклонением, чтобы неаккуратный клиент не начал блуждание.
        val planNow = currentPlan
        if (cmd == Command.START && (planNow == null || planNow.zones.all { it.cells.isEmpty() })) {
            onLog?.invoke("WARN", "Команда START отклонена: план не задан")
            return CommandResponse(accepted = false, requestId = requestId, reason = "План не задан")
        }
        when (val result = RobotFsm.applyCommand(state, cmd)) {
            is TransitionResult.Rejected -> {
                onLog?.invoke("WARN", "Команда $cmd отклонена в состоянии $state")
                return CommandResponse(accepted = false, requestId = requestId, reason = result.reason)
            }
            is TransitionResult.Accepted -> {
                val previous = state
                applySideEffects(cmd, previous)
                state = result.newState
                onLog?.invoke("INFO", "Команда $cmd принята: $previous → ${result.newState}")
                return CommandResponse(accepted = true, requestId = requestId)
            }
        }
    }

    /**
     * Автозапуск уборки по расписанию: задаёт mode из записи и стартует,
     * только если робот в IDLE (иначе — отклонение, как у обычной команды).
     */
    suspend fun scheduledStart(mode: String, requestId: String): CommandResponse = mutex.withLock {
        if (state != RobotState.IDLE) {
            return@withLock CommandResponse(accepted = false, requestId = requestId, reason = "ILLEGAL_STATE")
        }
        this.mode = mode
        commandLocked(Command.START, requestId)
    }

    private suspend fun applySideEffects(cmd: Command, previous: RobotState) {
        when (cmd) {
            Command.START -> {
                // START с пустым планом отклонён в command(), поэтому маршрут здесь всегда непуст.
                val plan = currentPlan
                sessionStartedAt = Instant.now()
                sessionAreaAccum = 0.0
                progress = 0.0
                error = null
                route = planRoute(plan).map { Position(it.x, it.y) }
                routeIndex = 0
                onLog?.invoke("INFO", "Уборка по плану: зон ${plan?.zones?.size ?: 0}, клеток в маршруте ${route.size}")
            }
            Command.ABORT -> finishSession(aborted = true)
            Command.RETURN_HOME -> {
                if (previous == RobotState.CLEANING || previous == RobotState.PAUSED) {
                    finishSession(aborted = true)
                }
            }
            Command.RESET -> error = null
            else -> Unit
        }
    }

    private suspend fun finishSession(aborted: Boolean) {
        sessionStartedAt?.let { started ->
            onSessionFinished?.invoke(started, Instant.now(), sessionAreaAccum, mode, aborted)
        }
        sessionStartedAt = null
    }

    /** Один "тик" симуляции, вызывается раз в секунду фоновым циклом. */
    suspend fun tick() = mutex.withLock {
        when (state) {
            RobotState.CLEANING -> tickCleaning()
            RobotState.RETURNING -> tickReturning()
            RobotState.CHARGING -> tickCharging()
            RobotState.IDLE -> tickIdleTopUp()
            RobotState.PAUSED, RobotState.ERROR -> Unit
        }
    }

    private suspend fun tickCleaning() {
        battery = (battery - 1).coerceAtLeast(0)
        sessionAreaAccum += 5.0
        position = stepAlongRoute()

        when {
            battery <= 15 -> {
                onLog?.invoke("WARN", "Низкий заряд, робот возвращается на станцию")
                finishSession(aborted = true)
                state = RobotState.RETURNING
            }
            progress >= 1.0 -> {
                onLog?.invoke("INFO", "Уборка завершена, робот возвращается на станцию")
                finishSession(aborted = false)
                state = RobotState.RETURNING
            }
            Random.nextDouble() < faultChance -> {
                error = RobotError.entries.filter { it != RobotError.LOW_BATTERY }.random()
                onLog?.invoke("ERROR", "Авария: $error")
                finishSession(aborted = true)
                state = RobotFsm.fault(state)
            }
        }
    }

    /** Один шаг к очередной клетке маршрута; по достижении — переход к следующей, progress растёт. */
    private fun stepAlongRoute(): Position {
        if (routeIndex >= route.size) return position
        val target = route[routeIndex]
        if (position != target) {
            return stepToward(position, target)
        }
        // Клетка достигнута: progress = доля пройденных клеток маршрута.
        routeIndex++
        progress = routeIndex.toDouble() / route.size
        return position
    }

    private suspend fun tickReturning() {
        position = stepToward(position, dockPosition)
        if (position == dockPosition) {
            state = RobotFsm.docked(state)
            onLog?.invoke("INFO", "Робот пристыкован, началась зарядка")
        }
    }

    private suspend fun tickCharging() {
        battery = (battery + 3).coerceAtMost(100)
        if (battery >= 100) {
            state = RobotFsm.batteryFull(state)
            onLog?.invoke("INFO", "Зарядка завершена")
        }
    }

    private fun tickIdleTopUp() {
        if (battery < 100 && position == dockPosition) {
            battery = (battery + 1).coerceAtMost(100)
        }
    }

    private fun stepToward(from: Position, to: Position): Position {
        val dx = (to.x - from.x).coerceIn(-1, 1)
        val dy = (to.y - from.y).coerceIn(-1, 1)
        return Position(from.x + dx, from.y + dy)
    }

    suspend fun snapshot(): Telemetry = mutex.withLock {
        Telemetry(
            robotId = id,
            timestamp = Instant.now().toString(),
            state = state.name,
            battery = battery,
            progress = round(progress * 100) / 100.0,
            position = position,
            error = error?.name
        )
    }

    suspend fun summary(): RobotSummary = mutex.withLock {
        RobotSummary(id = id, name = name, state = state.name, battery = battery, error = error?.name)
    }
}
