package com.cleanbot.app.data.remote

import com.cleanbot.app.data.model.CleaningPlan
import com.cleanbot.app.data.model.CommandRequest
import com.cleanbot.app.data.model.CommandResponse
import com.cleanbot.app.data.model.LogEntry
import com.cleanbot.app.data.model.RobotSettings
import com.cleanbot.app.data.model.RobotSummary
import com.cleanbot.app.data.model.Schedule
import com.cleanbot.app.data.model.ScheduleUpdateRequest
import com.cleanbot.app.data.model.Session
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/** open + open-методы: юнит-тесты репозитория подменяют API фейком-наследником. */
open class RobotApi {

    private val client get() = ApiClient.http
    private val base get() = ServerConfig.httpBaseUrl

    open suspend fun listRobots(): List<RobotSummary> =
        client.get("$base/robots").body()

    suspend fun getRobot(id: String): RobotSummary =
        client.get("$base/robots/$id").body()

    suspend fun sendCommand(id: String, command: String, requestId: String): CommandResponse =
        client.post("$base/robots/$id/commands") {
            contentType(ContentType.Application.Json)
            setBody(CommandRequest(command, requestId))
        }.body()

    suspend fun getSchedule(id: String): List<Schedule> =
        client.get("$base/robots/$id/schedules").body()

    suspend fun updateSchedule(id: String, entries: List<Schedule>) {
        client.put("$base/robots/$id/schedules") {
            contentType(ContentType.Application.Json)
            setBody(ScheduleUpdateRequest(entries))
        }
    }

    open suspend fun getPlan(id: String): CleaningPlan =
        client.get("$base/robots/$id/plan").body()

    /** PUT /plan: возвращает null при успехе или текст ошибки валидации из 400. */
    suspend fun updatePlan(id: String, plan: CleaningPlan): String? {
        val response = client.put("$base/robots/$id/plan") {
            contentType(ContentType.Application.Json)
            setBody(plan)
        }
        if (response.status.isSuccess()) return null
        return runCatching { response.bodyAsText() }.getOrNull()
            ?: "Ошибка сохранения плана (${response.status.value})"
    }

    /** from/to — ISO-8601, опциональны; фильтр по startedAt, границы включительно. */
    open suspend fun getSessions(id: String, from: String? = null, to: String? = null): List<Session> =
        client.get("$base/robots/$id/sessions") {
            from?.let { parameter("from", it) }
            to?.let { parameter("to", it) }
        }.body()

    suspend fun getLogs(id: String): List<LogEntry> =
        client.get("$base/robots/$id/logs").body()

    suspend fun getSettings(id: String): RobotSettings =
        client.get("$base/robots/$id/settings").body()

    suspend fun updateSettings(settings: RobotSettings) {
        client.put("$base/robots/${settings.robotId}/settings") {
            contentType(ContentType.Application.Json)
            setBody(settings)
        }
    }
}
