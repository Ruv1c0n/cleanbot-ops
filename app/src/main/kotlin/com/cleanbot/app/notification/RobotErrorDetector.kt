package com.cleanbot.app.notification

/** Решение детектора по одному роботу после очередного батча телеметрии. */
sealed interface RobotAlert {
    /** Переход в ERROR или смена кода ошибки — повод для уведомления. */
    data class Fault(val robotId: String, val code: String?) : RobotAlert

    /** Выход из ERROR — recovery-уведомление. */
    data class Recovered(val robotId: String) : RobotAlert
}

/**
 * Edge-trigger детектор ошибок по потоку телеметрии: уведомляем только на переход
 * не-ERROR → ERROR, при смене кода ошибки и на выход из ERROR; висящий ERROR молчит.
 *
 * Дедуп защищает от спама при «моргании» состояния: повторная авария с тем же кодом
 * в пределах [dedupMillis] от предыдущего уведомления подавляется. Смена кода — всегда
 * новое уведомление. Recovery-уведомления не дедуплицируются и дедуп не сбрасывают.
 *
 * Чистый класс без Android-зависимостей — покрывается JUnit-тестами.
 */
class RobotErrorDetector(private val dedupMillis: Long = DEFAULT_DEDUP_MILLIS) {

    private data class Track(
        val state: String,
        val error: String?,
        val lastFaultCode: String?,
        val lastFaultNotifiedAt: Long
    )

    private val tracks = HashMap<String, Track>()

    @Synchronized
    fun onTelemetry(robotId: String, state: String, error: String?, nowMillis: Long): RobotAlert? {
        val prev = tracks[robotId]
        val wasError = prev?.state == ERROR_STATE
        val isError = state == ERROR_STATE
        tracks[robotId] = Track(state, error, prev?.lastFaultCode, prev?.lastFaultNotifiedAt ?: NEVER)
        return when {
            isError && !wasError -> fault(robotId, error, nowMillis, prev)
            isError && wasError && error != prev?.error -> fault(robotId, error, nowMillis, prev)
            !isError && wasError -> RobotAlert.Recovered(robotId)
            else -> null
        }
    }

    private fun fault(robotId: String, code: String?, nowMillis: Long, prev: Track?): RobotAlert? {
        // Дедуп только для того же кода: смена кода — всегда новое уведомление.
        val last = prev
        if (last != null &&
            last.lastFaultCode == code &&
            last.lastFaultNotifiedAt != NEVER &&
            nowMillis - last.lastFaultNotifiedAt < dedupMillis
        ) {
            return null
        }
        tracks[robotId] = Track(ERROR_STATE, code, code, nowMillis)
        return RobotAlert.Fault(robotId, code)
    }

    private companion object {
        const val ERROR_STATE = "ERROR"
        const val NEVER = 0L
        const val DEFAULT_DEDUP_MILLIS = 5L * 60 * 1000
    }
}
