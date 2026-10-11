package com.cleanbot.simulator

import com.cleanbot.simulator.repository.FleetRepository
import com.cleanbot.simulator.routes.robotRoutes
import com.cleanbot.simulator.routes.telemetrySocket
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.time.Duration

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    install(ContentNegotiation) { json(json) }
    install(WebSockets) { pingPeriod = Duration.ofSeconds(15) }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Post)
    }

    val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repository = FleetRepository(repoScope)
    repository.start()

    routing {
        robotRoutes(repository)
        telemetrySocket(repository, json)
    }
}
