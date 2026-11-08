package com.cleanbot.simulator

import com.cleanbot.simulator.model.Cell
import com.cleanbot.simulator.model.CleaningPlan
import com.cleanbot.simulator.model.GRID_HEIGHT
import com.cleanbot.simulator.model.GRID_WIDTH
import com.cleanbot.simulator.model.Zone
import com.cleanbot.simulator.robot.planRoute
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
 * Маршрутные тесты PUT/GET /robots/{id}/plan (issue #10): ошибки валидации доходят
 * до клиента как 400 с текстом, валидный план сохраняется и читается идентично.
 */
class PlanRoutesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private suspend fun putPlan(client: HttpClient, id: String, plan: CleaningPlan): HttpResponse =
        client.put("/robots/$id/plan") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CleaningPlan.serializer(), plan))
        }

    private suspend fun getPlan(client: HttpClient, id: String): CleaningPlan =
        json.decodeFromString(CleaningPlan.serializer(), client.get("/robots/$id/plan").bodyAsText())

    private fun zone(id: Int = 1, name: String = "Гостиная", cells: List<Cell>) = Zone(id, name, cells)

    private fun plan(zones: List<Zone>) = CleaningPlan(GRID_WIDTH, GRID_HEIGHT, zones)

    @Test
    fun `put invalid plan out of grid returns 400 with message`() = testApplication {
        application { module() }
        val response = putPlan(
            client,
            "cleanbot-01",
            plan(listOf(zone(cells = listOf(Cell(19, 14))))) // y=14 вне сетки 20×14
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("вне сетки"), "ожидался текст про выход за границы")
    }

    @Test
    fun `put invalid plan duplicate cells returns 400`() = testApplication {
        application { module() }
        val response = putPlan(
            client,
            "cleanbot-01",
            plan(listOf(zone(cells = listOf(Cell(1, 1), Cell(1, 1)))))
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Дублирующаяся клетка"))
    }

    @Test
    fun `put invalid plan empty zone returns 400`() = testApplication {
        application { module() }
        val response = putPlan(
            client,
            "cleanbot-01",
            plan(listOf(zone(cells = listOf(Cell(2, 2))), zone(id = 2, name = "Пустая", cells = emptyList())))
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("хотя бы одну клетку"))
    }

    @Test
    fun `put invalid plan wrong grid size returns 400`() = testApplication {
        application { module() }
        val response = putPlan(
            client,
            "cleanbot-01",
            CleaningPlan(10, 10, listOf(zone(cells = listOf(Cell(1, 1)))))
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Сетка плана"))
    }

    @Test
    fun `put unknown robot returns 404`() = testApplication {
        application { module() }
        val response = putPlan(
            client,
            "no-such-robot",
            plan(listOf(zone(cells = listOf(Cell(1, 1)))))
        )
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `put valid plan returns 200 and get returns identical plan`() = testApplication {
        application { module() }
        val zones = listOf(
            zone(id = 1, name = "Гостиная", cells = listOf(Cell(0, 0), Cell(1, 0), Cell(0, 1))),
            zone(id = 2, name = "Кухня", cells = listOf(Cell(5, 3)))
        )
        val sent = plan(zones)
        val response = putPlan(client, "cleanbot-02", sent)
        assertEquals(HttpStatusCode.OK, response.status)

        val stored = getPlan(client, "cleanbot-02")
        assertEquals(sent.gridW, stored.gridW)
        assertEquals(sent.gridH, stored.gridH)
        assertEquals(sent.zones, stored.zones)
        // GET пересчитывает маршрут: он непустой и совпадает с планируемой траекторией
        assertEquals(planRoute(sent), stored.route)
        assertTrue(stored.route.isNotEmpty())
    }
}
