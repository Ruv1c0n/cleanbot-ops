package com.cleanbot.simulator

import com.cleanbot.simulator.model.RobotSettings
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Маршрутные тесты GET/PUT /robots/{id}/settings (issue #27):
 * robotId в теле обязан совпадать с id в пути, иначе 400 —
 * иначе PUT по пути одного робота тихо перезаписывал бы настройки другого.
 */
class SettingsRoutesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private suspend fun putSettings(client: HttpClient, id: String, settings: RobotSettings): HttpResponse =
        client.put("/robots/$id/settings") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RobotSettings.serializer(), settings))
        }

    @Test
    fun `put settings with mismatched robotId returns 400`() = testApplication {
        application { module() }
        val response = putSettings(
            client,
            "cleanbot-01",
            RobotSettings(robotId = "cleanbot-02", displayName = "Самозванец", serverAddress = "ws://10.0.2.2:8080")
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("не совпадает"))
    }

    @Test
    fun `put settings with matching robotId returns 200 and get returns it`() = testApplication {
        application { module() }
        val response = putSettings(
            client,
            "cleanbot-01",
            RobotSettings(robotId = "cleanbot-01", displayName = "Новое имя", serverAddress = "ws://10.0.2.2:8080")
        )
        assertEquals(HttpStatusCode.OK, response.status)

        val stored = json.decodeFromString(
            RobotSettings.serializer(),
            client.get("/robots/cleanbot-01/settings").bodyAsText()
        )
        assertEquals("Новое имя", stored.displayName)
    }

    @Test
    fun `put settings for unknown robot returns 404`() = testApplication {
        application { module() }
        val response = putSettings(
            client,
            "no-such-robot",
            RobotSettings(robotId = "no-such-robot", displayName = "X", serverAddress = "ws://x:8080")
        )
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
