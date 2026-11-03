package com.pulse.core.sync

import com.pulse.core.api.ApiClient
import com.pulse.core.api.AuthTokens
import com.pulse.core.api.TokenStore
import com.pulse.core.platform.JvmFileSystem
import com.pulse.core.platform.MemorySecretStore
import com.pulse.core.storage.DataScope
import com.pulse.core.storage.JsonFileStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files

/**
 * `GET/POST/PUT/DELETE /calendar` and `/lineups` of the Pulse server in the shape `server/lib/calendar-api.ts` and
 * `server/lib/staff/lineups.ts` answer (snake_case keys, enveloped), with a log of every request.
 */
internal class TeamFeedServer {
    data class Recorded(val method: HttpMethod, val path: String, val body: JsonObject?) {
        val line: String get() = "${method.value} $path"
    }

    val requests = mutableListOf<Recorded>()
    var offline = false
    var teamId = "0c1d2e3f-4a5b-4e5f-8a9b-6f1b6a2e1c2d"

    /** Roster the staff `GET /calendar` lists: id → nickname. */
    var players: Map<String, String> = emptyMap()
    val calendar = linkedMapOf<String, JsonObject>()
    val lineups = linkedMapOf<String, JsonObject>()

    /** `"POST calendar"`, `"PUT lineups/<id>"` or a bare path → the HTTP status the server fails with. */
    var failures: Map<String, Int> = emptyMap()

    /** Writes of one record (by its id, in the body or the path) the server fails with this status; other records go through. */
    var refusals: Map<String, Int> = emptyMap()

    fun lines(): List<String> = requests.map { it.line }

    fun bodiesOf(line: String): List<JsonObject> = requests.filter { it.line == line }.mapNotNull { it.body }

    fun engine() = MockEngine { request ->
        if (offline) throw IOException("offline")
        val path = request.url.encodedPath.removePrefix("/v1/")
        val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it).jsonObject }
        requests += Recorded(request.method, path, body)
        val recordId = (body?.get("id")?.jsonPrimitive?.content ?: path.substringAfter('/', "")).lowercase()
        val status = failures["${request.method.value} $path"] ?: failures[path] ?: refusals[recordId]?.takeIf { request.method != HttpMethod.Get }
        if (status != null) return@MockEngine respond(failure(status), HttpStatusCode.fromValue(status), JSON)
        val (code, answer) = answer(request.method, path, body)
        respond(answer, HttpStatusCode.fromValue(code), JSON)
    }

    private fun answer(method: HttpMethod, path: String, body: JsonObject?): Pair<Int, String> {
        val id = path.substringAfter('/', "").lowercase()
        return when {
            path == "auth/refresh" -> 401 to failure(401)
            path == "calendar" && method == HttpMethod.Get -> 200 to ok(buildJsonObject {
                put("team_id", teamId)
                put("events", JsonArray(calendar.values.toList()))
                put("players", JsonArray(players.map { (playerId, nickname) -> buildJsonObject { put("id", playerId); put("nickname", nickname) } }))
            })
            path == "calendar" && method == HttpMethod.Post || path.startsWith("calendar/") && method == HttpMethod.Put -> {
                val event = requireNotNull(body)
                val eventId = event.getValue("id").jsonPrimitive.content.lowercase()
                val playerId = event["player_id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                val nickname = playerId?.let { players[it] } ?: event["player_nickname"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                calendar[eventId] = JsonObject(event + mapOf(
                    "id" to JsonPrimitive(eventId), "player_id" to (playerId?.let(::JsonPrimitive) ?: JsonNull),
                    "player_nickname" to (nickname?.let(::JsonPrimitive) ?: JsonNull), "owner_id" to JsonNull
                ))
                200 to ok(buildJsonObject { put("id", eventId) })
            }
            path.startsWith("calendar/") && method == HttpMethod.Delete ->
                if (calendar.remove(id) != null) 200 to ok(buildJsonObject { put("id", id) }) else 404 to failure(404)
            path == "lineups" && method == HttpMethod.Get -> 200 to ok(buildJsonObject { put("lineups", JsonArray(lineups.values.toList())) })
            path == "lineups" && method == HttpMethod.Post || path.startsWith("lineups/") && method == HttpMethod.Put -> {
                val lineup = requireNotNull(body)
                val lineupId = lineup.getValue("id").jsonPrimitive.content.lowercase()
                lineups[lineupId] = JsonObject(lineup + mapOf("id" to JsonPrimitive(lineupId), "visibility" to JsonPrimitive("team")))
                (if (path == "lineups") 201 else 200) to ok(buildJsonObject { put("lineup", lineups.getValue(lineupId)) })
            }
            path.startsWith("lineups/") && method == HttpMethod.Delete ->
                if (lineups.remove(id) != null) 200 to ok(buildJsonObject { put("deleted", true) }) else 404 to failure(404)
            else -> 404 to failure(404)
        }
    }

    private fun ok(data: JsonElement) = """{"success":true,"data":$data,"error":null}"""

    private fun failure(status: Int): String {
        val code = when (status) {
            401 -> "unauthorized"; 403 -> "forbidden"; 404 -> "not_found"; 409 -> "version_conflict"; 413 -> "payload_too_large"
            422 -> "validation_failed"; 429 -> "rate_limited"; else -> "internal"
        }
        return """{"success":false,"data":null,"error":{"code":"$code","message":null}}"""
    }

    private companion object {
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")
    }
}

/** An account folder on a temp disk and an API client signed in against [server]. */
internal class TeamFeedHarness(val server: TeamFeedServer = TeamFeedServer()) {
    val scope = DataScope(JvmFileSystem(Files.createTempDirectory("pulse-team-feed").toFile())).also { it.current = "accounts-test" }
    val client = ApiClient(HttpClient(server.engine()), "https://api.test/v1", TokenStore(MemorySecretStore()).also { it.tokens = AuthTokens("A", "R") })

    /** A file the sync ledger and the sync triggers never see, like the real team files. */
    fun <T> file(name: String, serializer: KSerializer<T>) = JsonFileStorage(name, serializer, scope, ledger = null, notifyOnChange = false)
}
