package com.pulse.core.sync

import com.pulse.core.api.ApiClient
import com.pulse.core.api.ApiException
import com.pulse.core.storage.DataScope
import com.pulse.core.storage.SyncLedger
import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.PulseJson
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/** File names (`sleep.json`…) of one sync round. A failed round leaves the rest pending for the next one. */
data class SyncResult(val uploaded: List<String>, val downloaded: List<String>, val failed: Boolean)

/**
 * Keeps an account's collection files and the server's documents in step («Синхронизация данных»):
 * versioned uploads (`PUT /sync/{collection}` with `base_version`), downloads of newer server copies, a per-record
 * merge on conflicts, and meal photos sent apart from `nutrition`. Demo accounts, the guest folder and accounts
 * without server tokens never sync: no request is made.
 */
class SyncEngine(private val client: ApiClient, private val scope: DataScope, private val ledger: SyncLedger = SyncLedger(scope)) {
    /** False in a local build (no server). */
    val isEnabled: Boolean get() = client.isConfigured

    /** Uploads every pending collection; on version_conflict pulls, merges, re-uploads (max 3 rounds). */
    suspend fun pushPending(folder: String = scope.folder()): SyncResult = sync(folder) { round ->
        for (file in ledger.pending(folder)) {
            if (collectionOf(file) in CollectionMerge.specs) round.upload(file)
        }
    }

    /** GET /sync, then downloads every collection newer than the ledger's version and merges it in. */
    suspend fun pullChanged(folder: String = scope.folder()): SyncResult = sync(folder) { round ->
        val known = ledger.entries(folder)
        val remote = client.send(HttpMethod.Get, "sync", ListSerializer(RemoteVersion.serializer()))
        for (item in remote.sortedBy { it.collection }) {
            if (item.collection !in CollectionMerge.specs) continue
            val file = fileOf(item.collection)
            if (item.version > (known[file]?.version ?: 0)) round.download(file)
        }
    }

    private fun canSync(folder: String): Boolean {
        val account = folder.substringAfterLast('/')
        return isEnabled && client.tokens.tokens != null && account != GUEST && !account.startsWith(DEMO_PREFIX)
    }

    /** Any API error other than a version conflict ends the round; what was done so far stays done. */
    private suspend fun sync(folder: String, work: suspend (Round) -> Unit): SyncResult {
        if (!canSync(folder)) return SyncResult(emptyList(), emptyList(), failed = false)
        val round = Round(folder)
        val failed = try {
            work(round)
            false
        } catch (error: ApiException) {
            true
        }
        return SyncResult(round.uploaded.toList(), round.downloaded.toList(), failed)
    }

    private inner class Round(private val folder: String) {
        val uploaded = LinkedHashSet<String>()
        val downloaded = LinkedHashSet<String>()

        private fun path(file: String) = "$folder/$file"
        private fun readText(file: String): String? = scope.files.readText(path(file))
        private fun parse(text: String): JsonElement? = runCatching { PulseJson.parseToJsonElement(text) }.getOrNull()

        suspend fun download(file: String) {
            val remote = fetch(collectionOf(file)) ?: return
            if (absorb(file, remote)) upload(file)
        }

        /** Sends the local file; a conflict merges the newer server copy in and tries again. */
        suspend fun upload(file: String) {
            val collection = collectionOf(file)
            var baseVersion = ledger.entries(folder)[file]?.version
            // Photos sent in this attempt: a conflicting write may have dropped them, so they go again after a conflict.
            val sentPhotos = HashSet<String>()
            var attempts = 0
            while (true) {
                val text = readText(file) ?: return
                val local = parse(text) ?: return
                if (collection == NUTRITION) uploadPhotos(local, sentPhotos)
                val document = forServer(collection, local)
                val conflict = try {
                    val written = client.send(HttpMethod.Put, "sync/$collection", Written.serializer(), putBody(baseVersion, document))
                    accepted(file, text, document, written, sentPhotos)
                    return
                } catch (error: ApiException) {
                    attempts += 1
                    if (!error.isVersionConflict || attempts >= MAX_PUT_ATTEMPTS) throw error
                    error
                }
                sentPhotos.clear()
                val remote = if (conflict.currentVersion == 0) null else fetch(collection)
                baseVersion = remote?.version
                if (remote != null && !absorb(file, remote)) return
            }
        }

        private fun accepted(file: String, sentText: String, document: JsonElement, written: Written, sentPhotos: Set<String>) {
            val collection = collectionOf(file)
            if (collection == NUTRITION) {
                // Only now are these photos safe on the server: a nutrition write without their meal would have dropped them.
                val meals = MealPhotos.mealIds(document)
                saveUploadedPhotos((uploadedPhotos() + sentPhotos).filterTo(HashSet()) { it in meals })
            }
            ledger.recordServerCopy(
                file, written.version, CollectionMerge.recordKeys(collection, document), written.updatedAt,
                upToDate = readText(file) == sentText, folder = folder
            )
            uploaded += file
        }

        /**
         * Brings the server copy into the local file: taken whole when there is nothing unsent, merged per record
         * otherwise. Returns true when the result still has to be uploaded.
         */
        private suspend fun absorb(file: String, remote: RemoteDocument): Boolean {
            val collection = collectionOf(file)
            val entry = ledger.entries(folder)[file]
            val local = readText(file)?.let(::parse)
            val unsent = if (local != null && (entry == null || entry.isPending)) local else null
            val merged = unsent?.let {
                val localIsNewer = entry != null && entry.changedAt > remote.updatedAt
                CollectionMerge.merge(collection, forServer(collection, it), remote.document, entry?.baseKeys.orEmpty(), localIsNewer)
            } ?: remote.document
            val needsUpload = unsent != null && merged != remote.document
            val result = if (collection == NUTRITION) withPhotos(merged, local, remote.document) else merged
            if (result != local) {
                scope.files.writeText(path(file), PulseJson.encodeToString(JsonElement.serializer(), result))
                downloaded += file
            }
            ledger.recordServerCopy(
                file, remote.version, CollectionMerge.recordKeys(collection, remote.document), remote.updatedAt,
                upToDate = !needsUpload, folder = folder
            )
            return needsUpload
        }

        private suspend fun uploadPhotos(local: JsonElement, sentPhotos: MutableSet<String>) {
            val onServer = uploadedPhotos()
            for ((id, base64) in MealPhotos.photos(local)) {
                if (id in onServer || id in sentPhotos) continue
                val jpeg = runCatching { Base64.decode(base64) }.getOrNull() ?: continue
                client.sendBytes(HttpMethod.Put, "photos/$id", jpeg)
                sentPhotos += id
            }
        }

        /** Local photos first; photos of other meals are downloaded. A photo that fails leaves its meal without one. */
        private suspend fun withPhotos(merged: JsonElement, local: JsonElement?, remote: JsonElement): JsonElement {
            val localPhotos = local?.let(MealPhotos::photos).orEmpty()
            val meals = MealPhotos.mealIds(merged)
            val wanted = (MealPhotos.referenced(merged) + MealPhotos.referenced(remote)).filter { it in meals && it !in localPhotos }
            val fetched = wanted.mapNotNull { id -> downloadPhoto(id)?.let { id to it } }.toMap()
            if (fetched.isNotEmpty()) saveUploadedPhotos(uploadedPhotos() + fetched.keys)
            return MealPhotos.restore(merged, localPhotos + fetched)
        }

        private suspend fun downloadPhoto(id: String): String? = try {
            Base64.encode(client.getBytes("photos/$id"))
        } catch (error: ApiException) {
            null
        }

        private suspend fun fetch(collection: String): RemoteDocument? = try {
            client.send(HttpMethod.Get, "sync/$collection", RemoteDocument.serializer())
        } catch (error: ApiException) {
            if (error.code == NOT_FOUND) null else throw error
        }

        /** Ids of photos known to be on the server, kept in `sync-photos.json` (never marked changed, never sent). */
        private fun uploadedPhotos(): Set<String> = readText(PHOTOS_FILE)
            ?.let { runCatching { PulseJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            ?.toSet() ?: emptySet()

        private fun saveUploadedPhotos(ids: Set<String>) {
            scope.files.writeText(path(PHOTOS_FILE), PulseJson.encodeToString(ListSerializer(String.serializer()), ids.sorted()))
        }
    }

    private fun forServer(collection: String, local: JsonElement): JsonElement =
        if (collection == NUTRITION) MealPhotos.strip(local) else local

    /** `base_version` is sent even when null (first upload): the server requires the key. */
    private fun putBody(baseVersion: Int?, document: JsonElement) = buildJsonObject {
        put("base_version", baseVersion)
        put("document", document)
    }

    @Serializable
    private data class RemoteVersion(
        val collection: String,
        val version: Int,
        @Serializable(with = IsoInstantSerializer::class) val updatedAt: Instant
    )

    @Serializable
    private data class RemoteDocument(
        val collection: String,
        val version: Int,
        @Serializable(with = IsoInstantSerializer::class) val updatedAt: Instant,
        val document: JsonElement
    )

    @Serializable
    private data class Written(val version: Int, @Serializable(with = IsoInstantSerializer::class) val updatedAt: Instant)

    private companion object {
        const val MAX_PUT_ATTEMPTS = 3
        const val NUTRITION = "nutrition"
        const val NOT_FOUND = "not_found"
        const val PHOTOS_FILE = "sync-photos.json"
        const val GUEST = "guest"
        const val DEMO_PREFIX = "demo-"

        fun collectionOf(file: String) = file.removeSuffix(".json")
        fun fileOf(collection: String) = "$collection.json"
    }
}
