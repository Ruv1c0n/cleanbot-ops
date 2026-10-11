package com.cleanbot.simulator.robot

sealed class TransitionResult {
    data class Accepted(val newState: RobotState) : TransitionResult()
    data class Rejected(val reason: String) : TransitionResult()
}

/**
 * Таблица переходов ровно по README:
 * IDLE --start--> CLEANING
 * CLEANING --pause--> PAUSED
 * PAUSED --resume--> CLEANING
 * CLEANING/PAUSED --abort/return_home--> RETURNING
 * RETURNING --docked--> CHARGING
 * CHARGING --battery_full--> IDLE
 * любое кроме ERROR --fault--> ERROR
 * ERROR --reset--> IDLE
 *
 * Команда, недопустимая в текущем состоянии, отклоняется с кодом ILLEGAL_STATE.
 */
object RobotFsm {

    private val commandTransitions: Map<Pair<RobotState, Command>, RobotState> = mapOf(
        (RobotState.IDLE to Command.START) to RobotState.CLEANING,
        (RobotState.CLEANING to Command.PAUSE) to RobotState.PAUSED,
        (RobotState.PAUSED to Command.RESUME) to RobotState.CLEANING,
        (RobotState.CLEANING to Command.ABORT) to RobotState.RETURNING,
        (RobotState.PAUSED to Command.ABORT) to RobotState.RETURNING,
        (RobotState.CLEANING to Command.RETURN_HOME) to RobotState.RETURNING,
        (RobotState.PAUSED to Command.RETURN_HOME) to RobotState.RETURNING,
        (RobotState.IDLE to Command.RETURN_HOME) to RobotState.RETURNING,
        (RobotState.ERROR to Command.RESET) to RobotState.IDLE
    )

    fun applyCommand(current: RobotState, command: Command): TransitionResult {
        val next = commandTransitions[current to command]
        return if (next != null) {
            TransitionResult.Accepted(next)
        } else {
            TransitionResult.Rejected("ILLEGAL_STATE")
        }
    }

    // Внутренние события симулятора — не приходят по REST, вызываются циклом тика.
    fun docked(current: RobotState): RobotState =
        if (current == RobotState.RETURNING) RobotState.CHARGING else current

    fun batteryFull(current: RobotState): RobotState =
        if (current == RobotState.CHARGING) RobotState.IDLE else current

    fun fault(current: RobotState): RobotState =
        if (current != RobotState.ERROR) RobotState.ERROR else current
}
