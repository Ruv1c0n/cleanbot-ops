package com.cleanbot.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object ApiClient {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val http: HttpClient by lazy {
        HttpClient(OkHttp) {
            // Fail-fast для локальной сети: симулятор — localhost/LAN, отказ
            // соединения должен занимать секунды, а не дефолтные ~10-20с OkHttp,
            // иначе оффлайн-состояние подменяется вечным «подключением»
            // (аудит 2026-11-11). Смежно: pingInterval — умершая половина
            // WebSocket-соединения обнаруживается по таймауту, а не по первой посылке.
            engine {
                config {
                    connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
                    retryOnConnectionFailure(false)
                }
            }
            install(ContentNegotiation) { json(json) }
            install(WebSockets)
            install(Logging) { level = LogLevel.INFO }
        }
    }
}
