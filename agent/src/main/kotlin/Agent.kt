import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

val env = File(".env").readLines()
    .filter { "=" in it && !it.startsWith("#") }
    .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
val sim = env["SIMULATOR_URL"] ?: "http://localhost:8080"
val ollama = env["OLLAMA_URL"] ?: "http://localhost:11434"
val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 300_000 } }

fun tool(name: String, desc: String, props: JsonObjectBuilder.() -> Unit, req: List<String>) = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", name); put("description", desc)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties", props)
            putJsonArray("required") { req.forEach(::add) }
        }
    }
}

val tools = buildJsonArray {
    add(tool("list_robots", "Список роботов парка: id, состояние, заряд, ошибка", {}, emptyList()))
    add(tool("send_command", "Отправить команду роботу. Недопустимая команда вернёт ILLEGAL_STATE", {
        putJsonObject("robot_id") { put("type", "string") }
        putJsonObject("command") {
            put("type", "string")
            putJsonArray("enum") { listOf("START", "PAUSE", "RESUME", "ABORT", "RETURN_HOME").forEach(::add) }
        }
    }, listOf("robot_id", "command")))
}

suspend fun runTool(name: String, args: JsonObject): String = when (name) {
    "list_robots" -> http.get("$sim/robots").bodyAsText()
    "send_command" -> http.post("$sim/robots/${args["robot_id"]!!.jsonPrimitive.content}/commands") {
        contentType(ContentType.Application.Json)
        setBody("""{"command":"${args["command"]!!.jsonPrimitive.content}","requestId":"${UUID.randomUUID()}"}""")
    }.bodyAsText()
    else -> """{"error":"unknown tool"}"""
}

fun main() = runBlocking {
    print("> "); val prompt = readln()
    val messages = mutableListOf<JsonElement>(
        buildJsonObject {
            put("role", "system")
            put("content", "Ты ассистент оператора парка клининговых роботов. Сначала вызови list_robots, потом действуй. Отвечай по-русски кратко.")
        },
        buildJsonObject { put("role", "user"); put("content", prompt) }
    )
    var inTok = 0; var outTok = 0

    repeat(10) { step ->
        val resp = http.post("$ollama/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", env["MODEL"] ?: "qwen3:4b-instruct")
                put("stream", false)
                putJsonObject("options") { put("num_ctx", 8192) }
                put("tools", tools)
                put("messages", JsonArray(messages))
            }.toString())
        }
        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        inTok += json["prompt_eval_count"]?.jsonPrimitive?.int ?: 0
        outTok += json["eval_count"]?.jsonPrimitive?.int ?: 0
        val msg = json["message"]!!.jsonObject
        messages += msg

        val calls = msg["tool_calls"]?.jsonArray
        if (calls.isNullOrEmpty()) {
            println("🤖 " + msg["content"]?.jsonPrimitive?.content)
            println("tokens: in=$inTok out=$outTok")
            return@runBlocking
        }
        calls.forEach { c ->
            val f = c.jsonObject["function"]!!.jsonObject
            val name = f["name"]!!.jsonPrimitive.content
            val args = f["arguments"]!!.jsonObject
            val out = runTool(name, args)
            println("🔧 [$step] $name $args -> $out")
            messages += buildJsonObject {
                put("role", "tool"); put("tool_name", name); put("content", out)
            }
        }
    }
}