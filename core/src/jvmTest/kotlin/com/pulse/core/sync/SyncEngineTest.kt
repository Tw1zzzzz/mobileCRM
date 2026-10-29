package com.pulse.core.sync

import com.pulse.core.api.ApiClient
import com.pulse.core.api.ApiException
import com.pulse.core.api.AuthTokens
import com.pulse.core.api.TokenStore
import com.pulse.core.model.Meal
import com.pulse.core.model.MealSource
import com.pulse.core.platform.JvmFileSystem
import com.pulse.core.platform.MemorySecretStore
import com.pulse.core.storage.DataScope
import com.pulse.core.storage.SyncLedger
import com.pulse.core.store.NutritionStore
import com.pulse.core.util.PulseJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * In-memory Pulse sync server (wire format of `server/lib/sync.ts` and `server/lib/photos.ts`):
 * documents with versions, meal photos, and a log of every request.
 */
private class FakeSyncServer {
    data class Stored(val version: Int, val updatedAt: Instant, val document: JsonElement)
    data class Recorded(val method: HttpMethod, val path: String, val body: ByteArray, val contentType: ContentType?) {
        val json: JsonObject get() = Json.parseToJsonElement(body.decodeToString()).jsonObject
    }

    val documents = mutableMapOf<String, Stored>()
    val photos = mutableMapOf<String, ByteArray>()
    val requests = mutableListOf<Recorded>()
    var offline = false
    /** `"PUT sync/sleep"` or a bare path (any method) → HTTP status the server fails with. */
    var failures: Map<String, Int> = emptyMap()
    /** Paths whose requests never reach the server (connection lost). */
    var unreachable: Set<String> = emptySet()
    /** Runs when a photo is asked for, before the answer (e.g. the app saves a file meanwhile). */
    var beforePhotoGet: (() -> Unit)? = null
    /** Runs right after a photo is stored (e.g. another phone writes in between). */
    var afterPhotoStored: (() -> Unit)? = null
    /** Runs before a document write is checked (e.g. another phone always writes first). */
    var beforePut: ((String) -> Unit)? = null
    /** Photo requests the server still answers this minute; past it they get `429 rate_limited`. Null = no limit. */
    var photoRequestsLeft: Int? = null

    /** Another phone's accepted write. */
    fun write(collection: String, document: JsonElement) {
        val version = (documents[collection]?.version ?: 0) + 1
        documents[collection] = Stored(version, Clock.System.now(), document)
        if (collection == "nutrition") dropOrphanPhotos(document)
    }

    fun puts(collection: String) = requests.filter { it.method == HttpMethod.Put && it.path == "sync/$collection" }

    fun photoPuts(id: String) = requests.filter { it.method == HttpMethod.Put && it.path == "photos/$id" }

    fun engine() = MockEngine { request ->
        if (offline) throw IOException("offline")
        val path = request.url.encodedPath.removePrefix("/v1/")
        if (path in unreachable) throw IOException("connection lost")
        val recorded = Recorded(request.method, path, bytesOf(request), request.body.contentType)
        requests += recorded
        (failures["${request.method.value} $path"] ?: failures[path])?.let { return@MockEngine respond(fail(codeFor(it)), HttpStatusCode.fromValue(it)) }
        if (path.startsWith("photos/")) photoRequestsLeft?.let { left ->
            if (left <= 0) return@MockEngine respond(fail(codeFor(429)), HttpStatusCode.TooManyRequests)
            photoRequestsLeft = left - 1
        }
        val (status, body) = when {
            path == "sync" -> 200 to ok(JsonArray(documents.toSortedMap().map { (name, stored) -> summary(name, stored) }))
            path.startsWith("sync/") && request.method == HttpMethod.Get -> getDocument(path.removePrefix("sync/"))
            path.startsWith("sync/") -> putDocument(path.removePrefix("sync/"), recorded.json)
            path.startsWith("photos/") && request.method == HttpMethod.Put -> {
                photos[path.removePrefix("photos/")] = recorded.body
                afterPhotoStored?.invoke()
                200 to ok(JsonObject(emptyMap()))
            }
            else -> photos[path.removePrefix("photos/")].also { beforePhotoGet?.invoke() }?.let { jpeg ->
                return@MockEngine respond(jpeg, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
            } ?: (404 to fail("not_found"))
        }
        respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun getDocument(collection: String): Pair<Int, String> {
        val stored = documents[collection] ?: return 404 to fail("not_found")
        return 200 to ok(buildJsonObject {
            put("collection", collection)
            put("version", stored.version)
            put("updated_at", stored.updatedAt.toString())
            put("document", stored.document)
        })
    }

    private fun putDocument(collection: String, body: JsonObject): Pair<Int, String> {
        beforePut?.invoke(collection)
        val base = body.getValue("base_version").let { if (it is JsonNull) null else it.jsonPrimitive.content.toInt() }
        val current = documents[collection]
        if (base != current?.version) {
            return 409 to """{"success":false,"data":null,"error":{"code":"version_conflict","message":null},"meta":{"current_version":${current?.version ?: 0}}}"""
        }
        write(collection, body.getValue("document"))
        val stored = documents.getValue(collection)
        return 200 to ok(buildJsonObject { put("version", stored.version); put("updated_at", stored.updatedAt.toString()) })
    }

    private fun dropOrphanPhotos(document: JsonElement) {
        val meals = (document as? JsonObject)?.get("meals") as? JsonArray ?: return
        val ids = meals.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content?.lowercase() }.toSet()
        photos.keys.removeAll { it.lowercase() !in ids }
    }

    private fun summary(name: String, stored: Stored) = buildJsonObject {
        put("collection", name)
        put("version", stored.version)
        put("updated_at", stored.updatedAt.toString())
    }

    private fun codeFor(status: Int) = when (status) {
        413 -> "payload_too_large"
        422 -> "validation_failed"
        429 -> "rate_limited"
        else -> "server_error"
    }

    private fun ok(data: JsonElement) = """{"success":true,"data":$data,"error":null}"""
    private fun fail(code: String) = """{"success":false,"data":null,"error":{"code":"$code","message":null}}"""

    private fun bytesOf(request: HttpRequestData): ByteArray = when (val body = request.body) {
        is OutgoingContent.ByteArrayContent -> body.bytes()
        else -> ByteArray(0)
    }
}

class SyncEngineTest {
    private val server = FakeSyncServer()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)

    /** One phone signed in to the fake server, with its own data folder. */
    private inner class Phone(account: String = "6F1B6A2E-1C2D-4E5F-8A9B-0C1D2E3F4A5B", signedIn: Boolean = true) {
        val scope = DataScope(JvmFileSystem(Files.createTempDirectory("pulse-sync").toFile())).also { it.current = account }
        val client = ApiClient(HttpClient(server.engine()), "https://api.test/v1", TokenStore(MemorySecretStore()).also {
            if (signedIn) it.tokens = AuthTokens("A1", "R1")
        })
        val engine = SyncEngine(client, scope)
        val ledger = SyncLedger(scope)

        fun save(file: String, json: JsonElement) {
            scope.files.writeText("${scope.folder()}/$file", json.toString())
            ledger.markChanged(file)
        }

        fun text(file: String): String? = scope.files.readText("${scope.folder()}/$file")
        fun read(file: String): JsonElement = Json.parseToJsonElement(assertNotNull(text(file), file))
        fun entry(file: String): SyncLedger.Entry = assertNotNull(ledger.entries()[file], file)
    }

    private fun record(id: String) = buildJsonObject { put("id", id); put("minutes", 420) }

    private fun sleep(vararg ids: String) = buildJsonObject {
        put("sessions", JsonArray(ids.map(::record)))
        put("window", buildJsonObject { put("bedtimeHour", 23) })
    }

    private fun ids(document: JsonElement, field: String = "sessions"): Set<String> =
        document.jsonObject.getValue(field).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()

    private fun meal(id: Uuid, photo: ByteArray? = null) = Meal(
        id, Instant.parse("2026-10-27T09:00:00Z"), "Овсянка", 350, 12, 8, 55, Meal.encodePhoto(photo), MealSource.MANUAL, "Nova"
    )

    private fun nutrition(vararg meals: Meal, water: Map<String, Int> = emptyMap()): JsonElement =
        PulseJson.encodeToJsonElement(NutritionStore.Saved.serializer(), NutritionStore.Saved(meals.toList(), water))

    /** The server copy of a nutrition document: meals of [withPhoto] carry `photoRef` instead of the photo. */
    private fun serverNutrition(vararg meals: Meal, withPhoto: Set<Uuid> = emptySet()): JsonElement {
        val document = nutrition(*meals).jsonObject
        return JsonObject(document + ("meals" to buildJsonArray {
            for (item in document.getValue("meals").jsonArray) {
                val id = item.jsonObject.getValue("id").jsonPrimitive.content
                add(if (withPhoto.any { it.toString().equals(id, ignoreCase = true) }) JsonObject(item.jsonObject + ("photoRef" to JsonPrimitive(true))) else item)
            }
        }))
    }

    private fun idOf(meal: Uuid) = meal.toString().uppercase()

    /** The meals of the last nutrition document the phone sent. */
    private fun sentMeals(): List<JsonObject> =
        server.puts("nutrition").last().json.getValue("document").jsonObject.getValue("meals").jsonArray.map { it.jsonObject }

    private fun List<JsonObject>.withPhotoRef(): Set<String> =
        filter { it["photoRef"] == JsonPrimitive(true) }.map { it.getValue("id").jsonPrimitive.content }.toSet()

    private fun JsonElement.meal(id: Uuid): JsonObject =
        jsonObject.getValue("meals").jsonArray.map { it.jsonObject }.single { it["id"]?.jsonPrimitive?.content == idOf(id) }

    private fun photoGets(id: Uuid) = server.requests.count { it.method == HttpMethod.Get && it.path == "photos/${idOf(id)}" }

    /** `sync-photos.json` of [phone]: field → ids. */
    private fun photoLedger(phone: Phone): Map<String, Set<String>> =
        phone.text("sync-photos.json")?.let { text ->
            Json.parseToJsonElement(text).jsonObject.mapValues { (_, ids) -> ids.jsonArray.map { it.jsonPrimitive.content }.toSet() }
        }.orEmpty()

    @Test fun pushSendsBaseVersionAndStoresNewVersion() = runTest {
        val phone = Phone()
        phone.save("sleep.json", sleep("a"))
        assertEquals(SyncResult(listOf("sleep.json"), emptyList(), failed = false), phone.engine.pushPending())
        assertEquals(JsonNull, server.puts("sleep").single().json["base_version"])
        val first = phone.entry("sleep.json")
        assertEquals(1, first.version)
        assertFalse(first.isPending)
        assertEquals(mapOf("sessions" to setOf("a")), first.baseKeys)
        assertEquals(server.documents.getValue("sleep").updatedAt, first.remoteUpdatedAt)

        phone.save("sleep.json", sleep("a", "b"))
        assertEquals(listOf("sleep.json"), phone.engine.pushPending().uploaded)
        assertEquals(JsonPrimitive(1), server.puts("sleep").last().json["base_version"])
        assertEquals(2, phone.entry("sleep.json").version)
        assertEquals(2, server.documents.getValue("sleep").version)
        assertEquals(phone.read("sleep.json"), server.documents.getValue("sleep").document)

        // Nothing pending: no request.
        val before = server.requests.size
        assertEquals(SyncResult(emptyList(), emptyList(), failed = false), phone.engine.pushPending())
        assertEquals(before, server.requests.size)
    }

    @Test fun conflictPullsMergesAndRepushes() = runTest {
        val phone = Phone()
        phone.save("sleep.json", sleep("a"))
        phone.engine.pushPending()
        server.write("sleep", sleep("a", "c")) // another phone: version 2
        phone.save("sleep.json", sleep("a", "b"))

        val result = phone.engine.pushPending()

        assertEquals(SyncResult(listOf("sleep.json"), listOf("sleep.json"), failed = false), result)
        val puts = server.puts("sleep")
        assertEquals(JsonPrimitive(1), puts[puts.size - 2].json["base_version"])
        assertEquals(JsonPrimitive(2), puts.last().json["base_version"])
        assertEquals(setOf("a", "b", "c"), ids(puts.last().json.getValue("document")))
        assertEquals(3, server.documents.getValue("sleep").version)
        assertEquals(setOf("a", "b", "c"), ids(phone.read("sleep.json")))
        val entry = phone.entry("sleep.json")
        assertEquals(3, entry.version)
        assertFalse(entry.isPending)
        assertEquals(mapOf("sessions" to setOf("a", "b", "c")), entry.baseKeys)
    }

    @Test fun concurrentAddsOnTwoPhonesKeepBoth() = runTest {
        val first = Phone()
        val second = Phone()
        first.save("sleep.json", sleep("from-first"))
        second.save("sleep.json", sleep("from-second"))

        assertFalse(first.engine.pushPending().failed)
        assertFalse(second.engine.pushPending().failed)
        assertFalse(first.engine.pullChanged().failed)
        assertFalse(second.engine.pullChanged().failed)

        val both = setOf("from-first", "from-second")
        assertEquals(both, ids(first.read("sleep.json")))
        assertEquals(both, ids(second.read("sleep.json")))
        assertEquals(both, ids(server.documents.getValue("sleep").document))
        assertTrue(first.ledger.pending().isEmpty())
        assertTrue(second.ledger.pending().isEmpty())
    }

    @Test fun pullMergesPendingLocalChangesAndUploadsThem() = runTest {
        val phone = Phone()
        phone.save("sleep.json", sleep("a"))
        phone.engine.pushPending()
        server.write("sleep", sleep("a", "c"))
        phone.save("sleep.json", sleep("a", "b"))

        val result = phone.engine.pullChanged()

        assertEquals(SyncResult(listOf("sleep.json"), listOf("sleep.json"), failed = false), result)
        assertEquals(JsonPrimitive(2), server.puts("sleep").last().json["base_version"])
        assertEquals(setOf("a", "b", "c"), ids(server.documents.getValue("sleep").document))
        assertEquals(setOf("a", "b", "c"), ids(phone.read("sleep.json")))
        assertFalse(phone.entry("sleep.json").isPending)
    }

    @Test fun pullAllRestoresMealsAndPhotos() = runTest {
        val withPhoto = Uuid.random()
        val plain = Uuid.random()
        server.write("nutrition", serverNutrition(meal(withPhoto), meal(plain), withPhoto = setOf(withPhoto)))
        server.photos[idOf(withPhoto)] = jpeg
        server.write("sleep", sleep("a"))
        server.write("sleep", sleep("a", "b"))
        val phone = Phone()

        val result = phone.engine.pullChanged()

        assertEquals(SyncResult(emptyList(), listOf("nutrition.json", "sleep.json"), failed = false), result)
        val saved = PulseJson.decodeFromString(NutritionStore.Saved.serializer(), assertNotNull(phone.text("nutrition.json")))
        assertEquals(setOf(withPhoto, plain), saved.meals.map { it.id }.toSet())
        assertContentEquals(jpeg, saved.meals.single { it.id == withPhoto }.photo)
        assertNull(saved.meals.single { it.id == plain }.photo)
        assertFalse(assertNotNull(phone.text("nutrition.json")).contains("photoRef"))
        assertEquals(setOf("a", "b"), ids(phone.read("sleep.json")))
        assertEquals(1, phone.entry("nutrition.json").version)
        assertEquals(2, phone.entry("sleep.json").version)
        assertTrue(phone.ledger.pending().isEmpty())
        assertTrue(server.requests.none { it.method == HttpMethod.Put })

        // Up to date: only the version list is asked for.
        val before = server.requests.size
        assertEquals(SyncResult(emptyList(), emptyList(), failed = false), phone.engine.pullChanged())
        assertEquals(listOf("sync"), server.requests.drop(before).map { it.path })
    }

    @Test fun missingPhotoKeepsMeal() = runTest {
        val lost = Uuid.random()
        server.write("nutrition", serverNutrition(meal(lost), withPhoto = setOf(lost)))
        val phone = Phone()

        val result = phone.engine.pullChanged()

        assertFalse(result.failed)
        assertTrue(server.requests.any { it.method == HttpMethod.Get && it.path == "photos/${idOf(lost)}" })
        val saved = PulseJson.decodeFromString(NutritionStore.Saved.serializer(), assertNotNull(phone.text("nutrition.json")))
        assertEquals(lost, saved.meals.single().id)
        assertNull(saved.meals.single().photo)
    }

    @Test fun saveDuringPhotoDownloadIsKept() = runTest {
        val remoteMeal = Uuid.random()
        val added = Uuid.random()
        val phone = Phone()
        server.write("nutrition", serverNutrition(meal(remoteMeal), withPhoto = setOf(remoteMeal)))
        server.photos[idOf(remoteMeal)] = jpeg
        // The app saves a new meal while the engine waits for the photo.
        server.beforePhotoGet = {
            server.beforePhotoGet = null
            phone.save("nutrition.json", nutrition(meal(added)))
        }

        val result = phone.engine.pullChanged()

        assertEquals(SyncResult(listOf("nutrition.json"), listOf("nutrition.json"), failed = false), result)
        val saved = PulseJson.decodeFromString(NutritionStore.Saved.serializer(), assertNotNull(phone.text("nutrition.json")))
        assertEquals(setOf(remoteMeal, added), saved.meals.map { it.id }.toSet())
        assertContentEquals(jpeg, saved.meals.single { it.id == remoteMeal }.photo)
        assertEquals(setOf(idOf(remoteMeal), idOf(added)), ids(server.documents.getValue("nutrition").document, "meals"))
        assertEquals(1, server.requests.count { it.method == HttpMethod.Get && it.path == "photos/${idOf(remoteMeal)}" })
        assertTrue(server.photoPuts(idOf(remoteMeal)).isEmpty(), "a downloaded photo is not sent back")
        assertTrue(phone.ledger.pending().isEmpty())
    }

    @Test fun unreachablePhotoKeepsItsReferenceAndIsFetchedLater() = runTest {
        val kept = Uuid.random()
        val remoteMeal = Uuid.random()
        val phone = Phone()
        phone.save("nutrition.json", nutrition(meal(kept)))
        phone.engine.pushPending()
        server.write("nutrition", serverNutrition(meal(kept), meal(remoteMeal), withPhoto = setOf(remoteMeal)))
        server.photos[idOf(remoteMeal)] = jpeg
        server.unreachable = setOf("photos/${idOf(remoteMeal)}")

        // The document still arrives; the meal keeps its photoRef and the photo waits.
        assertEquals(SyncResult(emptyList(), listOf("nutrition.json"), failed = false), phone.engine.pullChanged())
        val waiting = phone.read("nutrition.json").meal(remoteMeal)
        assertNull(waiting["photo"])
        assertEquals(JsonPrimitive(true), waiting["photoRef"])
        assertEquals(2, phone.entry("nutrition.json").version)
        assertTrue(phone.ledger.pending().isEmpty())
        assertEquals(setOf(idOf(remoteMeal)), photoLedger(phone)["pendingDownloads"])

        // An edit in between: the store rewrites the file without the marker, the upload still keeps the server's photo.
        phone.save("nutrition.json", nutrition(meal(kept), meal(remoteMeal), water = mapOf("2026-10-27" to 250)))
        assertEquals(listOf("nutrition.json"), phone.engine.pushPending().uploaded)
        assertEquals(setOf(idOf(remoteMeal)), sentMeals().withPhotoRef())
        assertContentEquals(jpeg, server.photos[idOf(remoteMeal)])

        // The next round fetches it, although nutrition itself did not change on the server.
        server.unreachable = emptySet()
        assertEquals(SyncResult(emptyList(), listOf("nutrition.json"), failed = false), phone.engine.pullChanged())
        val saved = PulseJson.decodeFromString(NutritionStore.Saved.serializer(), assertNotNull(phone.text("nutrition.json")))
        assertContentEquals(jpeg, saved.meals.single { it.id == remoteMeal }.photo)
        assertFalse(assertNotNull(phone.text("nutrition.json")).contains("photoRef"))
        assertEquals(emptySet(), photoLedger(phone)["pendingDownloads"].orEmpty())
        assertTrue(phone.ledger.pending().isEmpty())
        assertTrue(server.photoPuts(idOf(remoteMeal)).isEmpty(), "a downloaded photo is not sent back")

        // Fetched once: later rounds do not ask again.
        phone.engine.pullChanged()
        assertEquals(1, photoGets(remoteMeal))
    }

    @Test fun missingPhotoIsNotAskedForAgain() = runTest {
        val lost = Uuid.random()
        val phone = Phone()
        server.write("nutrition", serverNutrition(meal(lost), withPhoto = setOf(lost)))
        server.unreachable = setOf("photos/${idOf(lost)}")
        phone.engine.pullChanged()
        assertEquals(setOf(idOf(lost)), photoLedger(phone)["pendingDownloads"])

        // The server answers 404 at the retry: the meal stays without a photo and the id is dropped.
        server.unreachable = emptySet()
        assertFalse(phone.engine.pullChanged().failed)
        assertEquals(1, photoGets(lost))
        assertNull(phone.read("nutrition.json").meal(lost)["photoRef"])
        assertEquals(emptySet(), photoLedger(phone)["pendingDownloads"].orEmpty())
        phone.engine.pullChanged()
        assertEquals(1, photoGets(lost))
    }

    @Test fun photosUploadedOnceAndStrippedFromDocument() = runTest {
        val withPhoto = Uuid.random()
        val plain = Uuid.random()
        val phone = Phone()
        phone.save("nutrition.json", nutrition(meal(withPhoto, jpeg), meal(plain)))
        val local = phone.text("nutrition.json")

        assertFalse(phone.engine.pushPending().failed)

        val photo = server.photoPuts(idOf(withPhoto)).single()
        assertContentEquals(jpeg, photo.body)
        assertEquals(ContentType.Image.JPEG, photo.contentType?.withoutParameters())
        assertTrue(server.requests.indexOf(photo) < server.requests.indexOf(server.puts("nutrition").single()))
        val sent = server.puts("nutrition").single().json.getValue("document").jsonObject.getValue("meals").jsonArray.map { it.jsonObject }
        assertTrue(sent.none { "photo" in it }, sent.toString())
        assertEquals(JsonPrimitive(true), sent.single { it["id"]?.jsonPrimitive?.content == idOf(withPhoto) }["photoRef"])
        assertNull(sent.single { it["id"]?.jsonPrimitive?.content == idOf(plain) }["photoRef"])
        assertEquals(local, phone.text("nutrition.json"), "the phone keeps its photos")
        assertTrue(assertNotNull(phone.text("sync-photos.json")).contains(idOf(withPhoto)))

        phone.save("nutrition.json", nutrition(meal(withPhoto, jpeg), meal(plain), water = mapOf("2026-10-27" to 500)))
        assertEquals(listOf("nutrition.json"), phone.engine.pushPending().uploaded)
        assertEquals(1, server.photoPuts(idOf(withPhoto)).size)
        assertEquals(2, server.puts("nutrition").size)
    }

    @Test fun photoReuploadedAfterConflict() = runTest {
        val kept = Uuid.random()
        val fresh = Uuid.random()
        val other = Uuid.random()
        val phone = Phone()
        phone.save("nutrition.json", nutrition(meal(kept)))
        phone.engine.pushPending()
        // Another phone's nutrition write lands right after our photo upload: it lacks our new meal, so the server drops the photo.
        server.afterPhotoStored = {
            server.afterPhotoStored = null
            server.write("nutrition", serverNutrition(meal(kept), meal(other)))
        }
        phone.save("nutrition.json", nutrition(meal(kept), meal(fresh, jpeg)))

        val result = phone.engine.pushPending()

        assertFalse(result.failed)
        assertEquals(2, server.photoPuts(idOf(fresh)).size)
        assertContentEquals(jpeg, server.photos[idOf(fresh)])
        assertEquals(setOf(idOf(kept), idOf(fresh), idOf(other)), ids(server.documents.getValue("nutrition").document, "meals"))
        assertTrue(assertNotNull(phone.text("sync-photos.json")).contains(idOf(fresh)))
    }

    @Test fun rejectedPhotoDoesNotBlockNutrition() = runTest {
        val tooBig = Uuid.random()
        val fine = Uuid.random()
        val phone = Phone()
        server.failures = mapOf("photos/${idOf(tooBig)}" to 413)
        phone.save("nutrition.json", nutrition(meal(tooBig, jpeg), meal(fine, jpeg)))
        val local = phone.text("nutrition.json")

        assertEquals(SyncResult(listOf("nutrition.json"), emptyList(), failed = false), phone.engine.pushPending())

        fun sentMeal(id: Uuid) = server.puts("nutrition").last().json.getValue("document").jsonObject.getValue("meals").jsonArray
            .map { it.jsonObject }.single { it["id"]?.jsonPrimitive?.content == idOf(id) }
        assertNull(sentMeal(tooBig)["photo"])
        assertNull(sentMeal(tooBig)["photoRef"], "other phones must not try to download a rejected photo")
        assertEquals(JsonPrimitive(true), sentMeal(fine)["photoRef"])
        assertNull(server.photos[idOf(tooBig)])
        assertContentEquals(jpeg, server.photos[idOf(fine)])
        assertEquals(local, phone.text("nutrition.json"), "the phone keeps the rejected photo")

        phone.save("nutrition.json", nutrition(meal(tooBig, jpeg), meal(fine, jpeg), water = mapOf("2026-10-27" to 250)))
        assertEquals(listOf("nutrition.json"), phone.engine.pushPending().uploaded)
        assertEquals(1, server.photoPuts(idOf(tooBig)).size, "a rejected photo is not retried")
        assertEquals(1, server.photoPuts(idOf(fine)).size)
        assertNull(sentMeal(tooBig)["photoRef"])

        // Another phone's write merges in without bringing a photoRef back for the rejected meal.
        server.write("nutrition", server.documents.getValue("nutrition").document)
        phone.save("nutrition.json", nutrition(meal(tooBig, jpeg), meal(fine, jpeg), water = mapOf("2026-10-27" to 500)))
        assertFalse(phone.engine.pushPending().failed)
        assertNull(sentMeal(tooBig)["photoRef"])
        assertEquals(1, server.photoPuts(idOf(tooBig)).size)
    }

    @Test fun photoRateLimitMidUploadStillPushesNutrition() = runTest {
        val meals = List(4) { Uuid.random() }
        val phone = Phone()
        phone.save("nutrition.json", nutrition(*meals.map { meal(it, jpeg) }.toTypedArray()))
        server.photoRequestsLeft = 2

        // Two photos go, the third gets 429: the rest wait, the meals go up without photoRef.
        assertEquals(SyncResult(listOf("nutrition.json"), emptyList(), failed = false), phone.engine.pushPending())
        assertEquals(meals.map(::idOf).toSet(), sentMeals().map { it.getValue("id").jsonPrimitive.content }.toSet())
        assertTrue(sentMeals().none { "photo" in it })
        assertEquals(setOf(idOf(meals[0]), idOf(meals[1])), sentMeals().withPhotoRef())
        assertEquals(listOf(1, 1, 1, 0), meals.map { server.photoPuts(idOf(it)).size })
        assertEquals(listOf("nutrition.json"), phone.ledger.pending(), "their photos are still to send")

        // A later round sends the rest; the photos already on the server are not sent again.
        server.photoRequestsLeft = null
        assertEquals(SyncResult(listOf("nutrition.json"), emptyList(), failed = false), phone.engine.pushPending())
        assertEquals(listOf(1, 1, 2, 1), meals.map { server.photoPuts(idOf(it)).size })
        assertEquals(meals.map(::idOf).toSet(), sentMeals().withPhotoRef())
        assertTrue(meals.all { server.photos[idOf(it)] != null })
        assertTrue(phone.ledger.pending().isEmpty())
    }

    @Test fun photoServerErrorSkipsThePhotoNotTheRound() = runTest {
        val broken = Uuid.random()
        val phone = Phone()
        server.failures = mapOf("photos/${idOf(broken)}" to 500)
        phone.save("nutrition.json", nutrition(meal(broken, jpeg)))

        assertEquals(SyncResult(listOf("nutrition.json"), emptyList(), failed = false), phone.engine.pushPending())
        assertTrue(sentMeals().withPhotoRef().isEmpty())
        assertEquals(listOf("nutrition.json"), phone.ledger.pending())

        server.failures = emptyMap()
        assertEquals(listOf("nutrition.json"), phone.engine.pushPending().uploaded)
        assertEquals(setOf(idOf(broken)), sentMeals().withPhotoRef())
        assertTrue(phone.ledger.pending().isEmpty())
    }

    @Test fun photoSentInAFailedRoundIsNotSentAgain() = runTest {
        val fresh = Uuid.random()
        val phone = Phone()
        phone.save("nutrition.json", nutrition(meal(fresh, jpeg)))
        server.failures = mapOf("PUT sync/nutrition" to 500)
        assertTrue(phone.engine.pushPending().failed)
        assertEquals(1, server.photoPuts(idOf(fresh)).size)

        server.failures = emptyMap()
        assertEquals(listOf("nutrition.json"), phone.engine.pushPending().uploaded)
        assertEquals(1, server.photoPuts(idOf(fresh)).size, "the photo reached the server in the failed round")
        assertEquals(setOf(idOf(fresh)), sentMeals().withPhotoRef())
        assertEquals(setOf(idOf(fresh)), photoLedger(phone)["uploaded"])
    }

    @Test fun photoSentInAFailedRoundGoesAgainAfterAConflict() = runTest {
        val kept = Uuid.random()
        val fresh = Uuid.random()
        val phone = Phone()
        phone.save("nutrition.json", nutrition(meal(kept)))
        phone.engine.pushPending()
        phone.save("nutrition.json", nutrition(meal(kept), meal(fresh, jpeg)))
        server.failures = mapOf("PUT sync/nutrition" to 500)
        assertTrue(phone.engine.pushPending().failed)
        // Another phone's write, without our new meal, drops the photo on the server.
        server.failures = emptyMap()
        server.write("nutrition", serverNutrition(meal(kept)))
        assertNull(server.photos[idOf(fresh)])

        assertFalse(phone.engine.pushPending().failed)
        assertEquals(2, server.photoPuts(idOf(fresh)).size)
        assertContentEquals(jpeg, server.photos[idOf(fresh)])
        assertEquals(setOf(idOf(fresh)), sentMeals().withPhotoRef())
    }

    @Test fun offlineLeavesChangesPending() = runTest {
        val phone = Phone()
        phone.save("sleep.json", sleep("a"))
        val local = phone.text("sleep.json")
        server.offline = true

        assertEquals(SyncResult(emptyList(), emptyList(), failed = true), phone.engine.pushPending())
        assertEquals(SyncResult(emptyList(), emptyList(), failed = true), phone.engine.pullChanged())
        assertEquals(listOf("sleep.json"), phone.ledger.pending())
        assertEquals(local, phone.text("sleep.json"))
        assertNull(phone.entry("sleep.json").version)

        server.offline = false
        assertEquals(listOf("sleep.json"), phone.engine.pushPending().uploaded)
    }

    @Test fun failingCollectionDoesNotStopTheOthers() = runTest {
        val phone = Phone()
        phone.save("game-goals.json", buildJsonObject { put("elo", 1) })
        phone.save("sleep.json", sleep("a"))
        phone.save("wellbeing.json", buildJsonObject { put("mood", JsonArray(emptyList())) })
        server.failures = mapOf("sync/sleep" to 500)

        assertEquals(SyncResult(listOf("game-goals.json", "wellbeing.json"), emptyList(), failed = true), phone.engine.pushPending())
        assertEquals(listOf("sleep.json"), phone.ledger.pending())

        // Downloads too: one unreadable document, the others still arrive.
        server.failures = mapOf("GET sync/cognitive" to 500)
        server.write("cognitive", buildJsonObject { put("results", JsonArray(listOf(record("r1")))) })
        server.write("lineups", JsonArray(listOf(record("l1"))))
        assertEquals(SyncResult(emptyList(), listOf("lineups.json"), failed = true), phone.engine.pullChanged())
        assertNull(phone.text("cognitive.json"))

        server.failures = emptyMap()
        assertEquals(SyncResult(listOf("sleep.json"), listOf("cognitive.json"), failed = false), phone.engine.syncBoth())
    }

    private suspend fun SyncEngine.syncBoth(): SyncResult {
        val pulled = pullChanged()
        val pushed = pushPending()
        return SyncResult(pulled.uploaded + pushed.uploaded, pulled.downloaded + pushed.downloaded, pulled.failed || pushed.failed)
    }

    @Test fun failuresAreReportedWithoutContents() = runTest {
        val phone = Phone()
        val messages = mutableListOf<String>()
        phone.engine.log = { messages += it }
        phone.save("sleep.json", sleep("secret-night"))
        server.failures = mapOf("sync/sleep" to 500)
        phone.engine.pushPending()
        assertEquals(1, messages.size, messages.toString())
        assertTrue(listOf("sleep", "server_error", "500").all { it in messages.single() }, messages.single())

        val meal = Uuid.random()
        phone.save("nutrition.json", nutrition(meal(meal, jpeg)))
        server.photoRequestsLeft = 0
        phone.engine.pushPending()
        assertTrue(messages.any { "photo" in it && "rate_limited" in it && "429" in it }, messages.toString())

        server.offline = true
        phone.engine.pullChanged()
        assertTrue(messages.last().contains("OFFLINE"), messages.last())
        assertTrue(messages.none { "secret-night" in it || "A1" in it || "R1" in it || "Овсянка" in it }, messages.toString())
    }

    @Test fun localDeletionSurvivesAConflictMergeWithAnotherPhonesAddition() = runTest {
        val phone = Phone()
        phone.save("sleep.json", sleep("a", "b"))
        phone.engine.pushPending()
        server.write("sleep", sleep("a", "b", "c")) // another phone adds «c»
        phone.save("sleep.json", sleep("a")) // this phone deletes «b»

        assertEquals(SyncResult(listOf("sleep.json"), listOf("sleep.json"), failed = false), phone.engine.pushPending())
        assertEquals(setOf("a", "c"), ids(server.documents.getValue("sleep").document))
        assertEquals(setOf("a", "c"), ids(phone.read("sleep.json")))
        assertEquals(mapOf("sessions" to setOf("a", "c")), phone.entry("sleep.json").baseKeys)
    }

    /** Another phone answered the questionnaire and «О тебе»; this one tapped «Позже» and saved a draft before its first download. */
    private fun neverSyncedSurveyAndBody(): Triple<Phone, JsonElement, JsonElement> {
        val survey = buildJsonObject {
            put("survey", buildJsonObject { put("role", "igl") })
            put("mindset", JsonArray(listOf(record("m1"))))
        }
        val body = buildJsonObject { put("heightCm", 182); put("weightKg", 74.0) }
        server.write("player-survey", survey)
        server.write("body-profile", body)
        val phone = Phone()
        phone.save("player-survey.json", buildJsonObject { put("mindset", JsonArray(emptyList())); put("onboardingDismissed", true) })
        phone.save("body-profile.json", buildJsonObject { put("heightCm", 170) })
        // Pending, never synced, and newer than the server's copy.
        assertNull(phone.entry("player-survey.json").version)
        assertTrue(phone.entry("player-survey.json").changedAt > server.documents.getValue("player-survey").updatedAt)
        return Triple(phone, survey, body)
    }

    @Test fun neverSyncedLocalValuesLoseToTheServerCopyOnPull() = runTest {
        val (phone, survey, body) = neverSyncedSurveyAndBody()

        assertEquals(SyncResult(emptyList(), listOf("body-profile.json", "player-survey.json"), failed = false), phone.engine.pullChanged())

        assertEquals(survey, phone.read("player-survey.json"))
        assertEquals(body, phone.read("body-profile.json"))
        assertEquals(survey, server.documents.getValue("player-survey").document)
        assertEquals(body, server.documents.getValue("body-profile").document)
        assertTrue(server.requests.none { it.method == HttpMethod.Put })
        assertTrue(phone.ledger.pending().isEmpty())
    }

    @Test fun neverSyncedLocalValuesLoseToTheServerCopyOnPush() = runTest {
        val (phone, survey, body) = neverSyncedSurveyAndBody()

        val result = phone.engine.pushPending()

        assertFalse(result.failed)
        assertEquals(listOf("body-profile.json", "player-survey.json"), result.downloaded)
        assertEquals(survey, server.documents.getValue("player-survey").document)
        assertEquals(body, server.documents.getValue("body-profile").document)
        assertEquals(1, server.documents.getValue("body-profile").version)
        assertEquals(survey, phone.read("player-survey.json"))
        assertEquals(body, phone.read("body-profile.json"))
        assertTrue(phone.ledger.pending().isEmpty())
    }

    @Test fun stopsAfterThreeConflicts() = runTest {
        val phone = Phone()
        server.write("sleep", sleep("a"))
        // Another phone always writes first.
        server.beforePut = { collection -> server.write(collection, server.documents.getValue(collection).document) }
        phone.save("sleep.json", sleep("b"))

        val result = phone.engine.pushPending()

        assertTrue(result.failed)
        assertTrue(result.uploaded.isEmpty())
        assertEquals(3, server.puts("sleep").size)
        assertEquals(listOf("sleep.json"), phone.ledger.pending())
        assertTrue("b" in ids(phone.read("sleep.json")))
    }

    @Test fun demoFolderNeverSyncs() = runTest {
        for (phone in listOf(Phone("demo-teamPlayer"), Phone("guest"), Phone(signedIn = false))) {
            phone.save("sleep.json", sleep("a"))
            assertEquals(SyncResult(emptyList(), emptyList(), failed = false), phone.engine.pushPending())
            assertEquals(SyncResult(emptyList(), emptyList(), failed = false), phone.engine.pullChanged())
            assertEquals(SyncResult(emptyList(), emptyList(), failed = false), phone.engine.pushPending("accounts/demo-staff"))
            assertEquals(listOf("sleep.json"), phone.ledger.pending())
        }
        assertTrue(server.requests.isEmpty(), server.requests.map { it.path }.toString())
    }

    @Test fun oldLedgerFileStillLoads() = runTest {
        val phone = Phone()
        phone.scope.files.writeText("${phone.scope.folder()}/sleep.json", sleep("a").toString())
        phone.scope.files.writeText(
            "${phone.scope.folder()}/sync-ledger.json",
            """{"sleep.json":{"changedAt":"2026-10-20T08:00:00Z"},"faceit.json":{"changedAt":"2026-10-19T08:00:00Z","syncedAt":"2026-10-19T09:00:00Z"}}"""
        )

        val entry = phone.entry("sleep.json")
        assertEquals(Instant.parse("2026-10-20T08:00:00Z"), entry.changedAt)
        assertNull(entry.version)
        assertNull(entry.baseKeys)
        assertNull(entry.remoteUpdatedAt)
        assertEquals(listOf("sleep.json"), phone.ledger.pending())

        assertEquals(listOf("sleep.json"), phone.engine.pushPending().uploaded)
        assertEquals(JsonNull, server.puts("sleep").single().json["base_version"])
        assertEquals(1, phone.entry("sleep.json").version)
    }

    @Test fun oldSyncedEntryIsMergedNotReplaced() = runTest {
        val phone = Phone()
        phone.scope.files.writeText("${phone.scope.folder()}/sleep.json", sleep("local-only").toString())
        phone.scope.files.writeText(
            "${phone.scope.folder()}/sync-ledger.json",
            """{"sleep.json":{"changedAt":"2026-10-19T08:00:00Z","syncedAt":"2026-10-19T09:00:00Z"}}"""
        )
        assertTrue(phone.ledger.pending().isEmpty(), "plan 1 counted it as sent")
        server.write("sleep", sleep("remote"))
        server.failures = mapOf("PUT sync/sleep" to 500)

        val result = phone.engine.pullChanged()

        assertEquals(SyncResult(emptyList(), listOf("sleep.json"), failed = true), result)
        assertEquals(setOf("local-only", "remote"), ids(phone.read("sleep.json")))
        assertEquals(listOf("sleep.json"), phone.ledger.pending(), "the merge still has to reach the server")
        assertEquals(1, phone.entry("sleep.json").version)

        server.failures = emptyMap()
        assertEquals(listOf("sleep.json"), phone.engine.pushPending().uploaded)
        assertEquals(setOf("local-only", "remote"), ids(server.documents.getValue("sleep").document))
    }

    @Test fun conflictCarriesTheServerVersion() = runTest {
        server.write("sleep", sleep("a"))
        server.write("sleep", sleep("a", "b"))
        val client = Phone().client
        val body = buildJsonObject { put("base_version", 1); put("document", sleep("c")) }
        val error = assertFailsWith<ApiException> { client.sendEmpty(HttpMethod.Put, "sync/sleep", body) }
        assertTrue(error.isVersionConflict)
        assertEquals(2, error.currentVersion)
        assertEquals(409, error.status)
    }
}
