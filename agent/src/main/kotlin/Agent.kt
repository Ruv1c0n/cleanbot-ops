import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

val env = File(".env").readLines()
    .filter { "=" in it && !it.startsWith("#") }
    .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
val sim = env["SIMULATOR_URL"] ?: "http://localhost:8080"
val http = HttpClient(CIO)

// ---------- инструменты ----------
val tools = buildJsonArray {
    add(buildJsonObject {
        put("name", "list_robots")
        put("description", "Список роботов парка: id, состояние, заряд, ошибка")
        putJsonObject("input_schema") { put("type", "object"); putJsonObject("properties") {} }
    })
    add(buildJsonObject {
        put("name", "send_command")
        put("description", "Отправить команду роботу. Недопустимая в текущем состоянии команда вернёт ILLEGAL_STATE")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("robot_id") { put("type", "string") }
                putJsonObject("command") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("START","PAUSE","RESUME","ABORT","RETURN_HOME").forEach(::add) }
                }
            }
            putJsonArray("required") { add("robot_id"); add("command") }
        }
    })
}

suspend fun runTool(name: String, input: JsonObject): String = when (name) {
    "list_robots" -> http.get("$sim/robots").bodyAsText()
    "send_command" -> http.post("$sim/robots/${input["robot_id"]!!.jsonPrimitive.content}/commands") {
        contentType(ContentType.Application.Json)
        setBody("""{"command":"${input["command"]!!.jsonPrimitive.content}","requestId":"${UUID.randomUUID()}"}""")
    }.bodyAsText()
    else -> """{"error":"unknown tool"}"""
}

// ---------- цикл агента ----------
fun main() = runBlocking {
    val prompt = readln()
    val messages = mutableListOf<JsonElement>(buildJsonObject { put("role", "user"); put("content", prompt) })
    var inTok = 0; var outTok = 0

    repeat(10) { step ->                                   // лимит итераций
        val resp = http.post("https://api.anthropic.com/v1/messages") {
            header("x-api-key", env["ANTHROPIC_API_KEY"]!!)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", env["MODEL"] ?: "claude-sonnet-5-5")
                put("max_tokens", 1024)
                put("system", "Ты ассистент оператора парка клининговых роботов. Сначала смотри состояние, потом действуй.")
                put("tools", tools)
                put("messages", JsonArray(messages))
            }.toString())
        }
        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        inTok += json["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.int
        outTok += json["usage"]!!.jsonObject["output_tokens"]!!.jsonPrimitive.int
        val content = json["content"]!!.jsonArray
        messages += buildJsonObject { put("role", "assistant"); put("content", content) }

        if (json["stop_reason"]!!.jsonPrimitive.content != "tool_use") {
            content.filter { it.jsonObject["type"]!!.jsonPrimitive.content == "text" }
                .forEach { println("🤖 " + it.jsonObject["text"]!!.jsonPrimitive.content) }
            println("tokens: in=$inTok out=$outTok")
            return@runBlocking
        }
        val results = content.filter { it.jsonObject["type"]!!.jsonPrimitive.content == "tool_use" }.map { b ->
            val o = b.jsonObject
            val out = runTool(o["name"]!!.jsonPrimitive.content, o["input"]!!.jsonObject)
            println("🔧 [$step] ${o["name"]} ${o["input"]} -> $out")   // лог для LAB2.md
            buildJsonObject {
                put("type", "tool_result"); put("tool_use_id", o["id"]!!); put("content", out)
            }
        }
        messages += buildJsonObject { put("role", "user"); put("content", JsonArray(results)) }
    }
}