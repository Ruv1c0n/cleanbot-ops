package com.cleanbot.app.data.remote

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
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class RobotApi {

    private val client get() = ApiClient.http
    private val base get() = ServerConfig.httpBaseUrl

    suspend fun listRobots(): List<RobotSummary> =
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

    suspend fun getSessions(id: String): List<Session> =
        client.get("$base/robots/$id/sessions").body()

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
