package com.cleanbot.app.data.remote

import com.cleanbot.app.data.model.Telemetry
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.decodeFromString

class TelemetrySocket {

    /**
     * Подключается к /telemetry и эмиттит очередной батч телеметрии по всем роботам.
     * Flow завершится (с исключением или штатно), если соединение разорвётся —
     * переподключение делает вызывающая сторона (см. RobotRepository.startTelemetryLoop).
     */
    fun stream(): Flow<List<Telemetry>> = flow {
        ApiClient.http.webSocket(ServerConfig.wsUrl) {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    val telemetry = ApiClient.json.decodeFromString<List<Telemetry>>(text)
                    emit(telemetry)
                }
            }
        }
    }
}
