package com.cleanbot.simulator.routes

import com.cleanbot.simulator.model.*
import com.cleanbot.simulator.repository.FleetRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant

fun Route.robotRoutes(repo: FleetRepository) {

    route("/robots") {

        get {
            call.respond(repo.allSummaries())
        }

        get("/{id}") {
            val id = call.parameters["id"]!!
            val robot = repo.findRobot(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(robot.summary())
        }

        post("/{id}/commands") {
            val id = call.parameters["id"]!!
            val body = call.receive<CommandRequest>()
            val response = repo.sendCommand(id, body.command, body.requestId)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(response)
        }

        get("/{id}/schedules") {
            val id = call.parameters["id"]!!
            val schedule = repo.getSchedule(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(schedule)
        }

        put("/{id}/schedules") {
            val id = call.parameters["id"]!!
            val body = call.receive<ScheduleUpdateRequest>()
            if (!repo.replaceSchedule(id, body.entries)) return@put call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.OK)
        }

        get("/{id}/sessions") {
            val id = call.parameters["id"]!!
            val from = call.request.queryParameters["from"]?.let { Instant.parse(it) }
            val to = call.request.queryParameters["to"]?.let { Instant.parse(it) }
            val sessions = repo.getSessions(id, from, to) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(sessions)
        }

        get("/{id}/logs") {
            val id = call.parameters["id"]!!
            val logs = repo.getLogs(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(logs)
        }

        get("/{id}/settings") {
            val id = call.parameters["id"]!!
            val settings = repo.getSettings(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(settings)
        }

        put("/{id}/settings") {
            val id = call.parameters["id"]!!
            val body = call.receive<RobotSettings>()
            if (!repo.updateSettings(id, body)) return@put call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.OK)
        }
    }
}
