package com.cleanbot.simulator.routes

import com.cleanbot.simulator.repository.FleetRepository
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * GET ws://host:8080/telemetry
 * Каждую ~секунду присылает JSON-массив Telemetry по всем роботам сразу:
 * [{"robotId":"cleanbot-01", ...}, {"robotId":"cleanbot-02", ...}, ...]
 */
fun Route.telemetrySocket(repo: FleetRepository, json: Json) {
    webSocket("/telemetry") {
        repo.telemetryFlow.collect { snapshot ->
            send(Frame.Text(json.encodeToString(snapshot)))
        }
    }
}
