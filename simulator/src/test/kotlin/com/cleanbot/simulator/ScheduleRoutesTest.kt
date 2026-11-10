package com.cleanbot.simulator

import com.cleanbot.simulator.model.Schedule
import com.cleanbot.simulator.model.ScheduleUpdateRequest
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Маршрутные тесты PUT/GET /robots/{id}/schedules: ошибки валидации доходят
 * до клиента как 400 с текстом, неизвестный робот — 404, валидное расписание
 * сохраняется и читается идентично (записи реально запускают уборку, см. #34).
 */
class ScheduleRoutesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private suspend fun putSchedules(client: HttpClient, id: String, entries: List<Schedule>): HttpResponse =
        client.put("/robots/$id/schedules") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ScheduleUpdateRequest.serializer(), ScheduleUpdateRequest(entries)))
        }

    private suspend fun getSchedules(client: HttpClient, id: String): List<Schedule> =
        json.decodeFromString(
            ListSerializer(Schedule.serializer()),
            client.get("/robots/$id/schedules").bodyAsText()
        )

    private fun entry(
        robotId: String = "cleanbot-01",
        dayOfWeek: Int = 1,
        time: String = "06:00",
        mode: String = "dry_sweep"
    ) = Schedule(id = "$robotId-$dayOfWeek-$time-$mode", robotId = robotId, dayOfWeek = dayOfWeek, time = time, mode = mode)

    private fun validEntries() = listOf(entry(), entry(dayOfWeek = 3, time = "22:00", mode = "wet_mopping"))

    @Test
    fun `put valid schedule returns 200 and get returns it`() = testApplication {
        application { module() }
        val sent = validEntries()
        val response = putSchedules(client, "cleanbot-01", sent)
        assertEquals(HttpStatusCode.OK, response.status)

        val stored = getSchedules(client, "cleanbot-01")
        assertEquals(sent, stored)
    }

    @Test
    fun `put bad dayOfWeek returns 400`() = testApplication {
        application { module() }
        for (day in listOf(0, 8)) {
            val response = putSchedules(client, "cleanbot-01", listOf(entry(dayOfWeek = day)))
            assertEquals(HttpStatusCode.BadRequest, response.status, "dayOfWeek=$day")
            assertTrue(response.bodyAsText().contains("dayOfWeek"), "ожидался текст про dayOfWeek, day=$day")
        }
    }

    @Test
    fun `put bad time returns 400`() = testApplication {
        application { module() }
        for (time in listOf("25:00", "6:00", "12:5")) {
            val response = putSchedules(client, "cleanbot-01", listOf(entry(time = time)))
            assertEquals(HttpStatusCode.BadRequest, response.status, "time=$time")
            assertTrue(response.bodyAsText().contains("HH:mm"), "ожидался текст про формат HH:mm, time=$time")
        }
    }

    @Test
    fun `put bad mode returns 400`() = testApplication {
        application { module() }
        val response = putSchedules(client, "cleanbot-01", listOf(entry(mode = "vacuum")))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("режим"), "ожидался текст про режим")
    }

    @Test
    fun `put entry with mismatched robotId returns 400`() = testApplication {
        application { module() }
        val response = putSchedules(
            client,
            "cleanbot-01",
            listOf(entry(robotId = "cleanbot-02"))
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("не совпадает"), "ожидался текст про robotId из чужого пути")
    }

    @Test
    fun `put unknown robot returns 404`() = testApplication {
        application { module() }
        val response = putSchedules(client, "no-such-robot", validEntries())
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
