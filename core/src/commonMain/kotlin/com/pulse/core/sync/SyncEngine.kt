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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/** File names (`sleep.json`…) of one sync round. What failed stays pending for the next one. */
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

    /**
     * Runs `write`, the section that brings a downloaded or merged copy into the collection file [file] of
     * [folder] (check the file, write it, update the ledger). The section never suspends. The app runs it where its
     * stores save, and reloads that store in the same step, so a stale in-memory copy never overwrites the new file.
     */
    var applyFile: suspend (folder: String, file: String, write: () -> Unit) -> Unit = { _, _, write -> write() }

    /** Reports each failure: collection, error kind, code and HTTP status — never document contents or tokens. */
    var log: (String) -> Unit = {}

    /** Uploads every pending collection; on version_conflict pulls, merges, re-uploads (max 3 rounds). */
    suspend fun pushPending(folder: String = scope.folder()): SyncResult = sync(folder) { round ->
        for (file in ledger.pending(folder)) {
            if (collectionOf(file) in CollectionMerge.specs) round.each(file) { round.upload(file) }
        }
    }

    /**
     * GET /sync, then downloads every collection newer than the ledger's version and merges it in. Meal photos that
     * could not be downloaded earlier are fetched first when nutrition itself has not changed.
     */
    suspend fun pullChanged(folder: String = scope.folder()): SyncResult = sync(folder) { round ->
        val known = ledger.entries(folder)
        val remote = client.send(HttpMethod.Get, "sync", ListSerializer(RemoteVersion.serializer()))
        val newer = remote.sortedBy { it.collection }
            .filter { it.collection in CollectionMerge.specs && it.version > (known[fileOf(it.collection)]?.version ?: 0) }
            .map { fileOf(it.collection) }
        val nutrition = fileOf(NUTRITION)
        if (nutrition !in newer) round.each(nutrition) { round.retryPhotoDownloads() }
        for (file in newer) round.each(file) { round.download(file) }
    }

    private fun canSync(folder: String): Boolean {
        val account = folder.substringAfterLast('/')
        return isEnabled && client.tokens.tokens != null && account != GUEST && !account.startsWith(DEMO_PREFIX)
    }

    /**
     * A failing collection ends only its own part of the round (the others still sync); a failing version list ends
     * the download half. What was done so far stays done.
     */
    private suspend fun sync(folder: String, work: suspend (Round) -> Unit): SyncResult {
        if (!canSync(folder)) return SyncResult(emptyList(), emptyList(), failed = false)
        val round = Round(folder)
        round.each(null) { work(round) }
        return SyncResult(round.uploaded.toList(), round.downloaded.toList(), round.failed)
    }

    private inner class Round(private val folder: String) {
        val uploaded = LinkedHashSet<String>()
        val downloaded = LinkedHashSet<String>()
        var failed = false
            private set

        private fun path(file: String) = "$folder/$file"
        private fun readText(file: String): String? = scope.files.readText(path(file))
        private fun parse(text: String): JsonElement? = runCatching { PulseJson.parseToJsonElement(text) }.getOrNull()

        /** Runs [work] for [file] (null: the version list); a failure is reported and the round goes on. */
        suspend fun each(file: String?, work: suspend () -> Unit) {
            try {
                work()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed = true
                log("sync: ${file?.let(::collectionOf) ?: "version list"} failed (${describe(error)})")
            }
        }

        suspend fun download(file: String) {
            val remote = fetch(collectionOf(file)) ?: return
            if (absorb(file, remote)) upload(file)
        }

        /** Sends the local file; a conflict merges the newer server copy in and tries again. */
        suspend fun upload(file: String) {
            val collection = collectionOf(file)
            var baseVersion = ledger.entries(folder)[file]?.version
            var attempts = 0
            while (true) {
                val text = readText(file) ?: return
                val local = parse(text) ?: return
                val unsentPhotos = if (collection == NUTRITION) uploadPhotos(local) else emptySet()
                val document = forServer(collection, local, unsentPhotos)
                val conflict = try {
                    val written = client.send(HttpMethod.Put, "sync/$collection", Written.serializer(), putBody(baseVersion, document))
                    accepted(file, text, document, written, photosComplete = unsentPhotos.isEmpty())
                    return
                } catch (error: ApiException) {
                    // Another phone's nutrition write may have dropped the photos sent for this one: they go again.
                    if (error.isVersionConflict && collection == NUTRITION) updatePhotoLedger { it.copy(sent = emptySet()) }
                    attempts += 1
                    if (!error.isVersionConflict || attempts >= MAX_PUT_ATTEMPTS) throw error
                    error
                }
                val remote = if (conflict.currentVersion == 0) null else fetch(collection)
                baseVersion = remote?.version
                if (remote != null && !absorb(file, remote)) return
            }
        }

        /** With photos still to send ([photosComplete] false), nutrition stays pending: the next round sends them. */
        private fun accepted(file: String, sentText: String, document: JsonElement, written: Written, photosComplete: Boolean) {
            val collection = collectionOf(file)
            if (collection == NUTRITION) {
                // Only now are the sent photos safe on the server: a nutrition write without their meal would have dropped them.
                val meals = MealPhotos.mealIds(document)
                updatePhotoLedger { photos ->
                    PhotoLedger(
                        uploaded = (photos.uploaded + photos.sent).filterTo(HashSet()) { it in meals },
                        rejected = photos.rejected.filterTo(HashSet()) { it in meals },
                        pendingDownloads = photos.pendingDownloads.filterTo(HashSet()) { it in meals }
                    )
                }
            }
            ledger.recordServerCopy(
                file, written.version, CollectionMerge.recordKeys(collection, document), written.updatedAt,
                upToDate = photosComplete && readText(file) == sentText, folder = folder
            )
            uploaded += file
        }

        /**
         * Brings the server copy into the local file: taken whole when there is nothing unsent, merged per record
         * otherwise. Returns true when the result still has to be uploaded.
         *
         * Photos are downloaded before anything is written. If the app saved the file meanwhile, the merge starts
         * again with that save (downloaded photos are kept), so the edit is neither overwritten nor marked as sent.
         * A photo that cannot be fetched now keeps its meal's `photoRef` and waits for a later round.
         */
        private suspend fun absorb(file: String, remote: RemoteDocument): Boolean {
            val collection = collectionOf(file)
            // Meal id → base64 photo, or null when the server has none (404).
            val photoCache = HashMap<String, String?>()
            // Photos the server did not hand out this round (offline, rate limit…).
            val unreachable = HashSet<String>()
            repeat(MAX_ABSORB_PASSES) {
                val text = readText(file)
                val entry = ledger.entries(folder)[file]
                val local = text?.let(::parse)
                val unsent = if (local != null && hasUnsentChanges(entry)) local else null
                val merged = unsent?.let {
                    // A copy that was never synced is older than the server's for whole values: the first download
                    // after sign-in must not let the empty settings of a fresh install replace the player's own.
                    val localIsNewer = entry != null && entry.version != null && entry.changedAt > remote.updatedAt
                    CollectionMerge.merge(collection, forServer(collection, it), remote.document, entry?.baseKeys.orEmpty(), localIsNewer)
                } ?: remote.document
                val needsUpload = unsent != null && merged != remote.document
                val localPhotos = local?.let(MealPhotos::photos).orEmpty()
                val wanted = if (collection == NUTRITION) wantedPhotos(merged, remote.document, localPhotos) else emptySet()
                downloadPhotos(wanted, photoCache, unreachable)

                // Null while the app saved the file meanwhile: the merge starts again with that save.
                var applied: Boolean? = null
                applyFile(folder, file) write@{
                    if (readText(file) != text) return@write
                    // Nothing in here suspends: the file and the ledger are updated together.
                    val fetched = photoCache.mapNotNull { (id, photo) -> photo?.let { id to it } }.toMap()
                    val waiting = wanted - photoCache.keys
                    val result = if (collection == NUTRITION) MealPhotos.restore(merged, localPhotos + fetched, keep = waiting) else merged
                    if (result != local) {
                        scope.files.writeText(path(file), PulseJson.encodeToString(JsonElement.serializer(), result))
                        downloaded += file
                    }
                    if (collection == NUTRITION) {
                        updatePhotoLedger { it.copy(uploaded = it.uploaded + fetched.keys, pendingDownloads = waiting) }
                    }
                    ledger.recordServerCopy(
                        file, remote.version, CollectionMerge.recordKeys(collection, remote.document), remote.updatedAt,
                        upToDate = !needsUpload, folder = folder
                    )
                    applied = needsUpload
                }
                applied?.let { return it }
            }
            throw LocalFileBusy()
        }

        /** No ledger entry, a pending change, or an entry from before versioned sync (never really synced). */
        private fun hasUnsentChanges(entry: SyncLedger.Entry?): Boolean = entry == null || entry.isPending || entry.version == null

        /**
         * Uploads the photos the server does not have yet and returns the ids left unsent. Each sent photo is recorded
         * at once (`sent`), so a later round does not send it again. A photo the server refuses for good (too large, not
         * a JPEG) is recorded as rejected and never sent again. Any other error (rate limit, network) stops the photos
         * of this round: their meals go up without `photoRef` and get it once a later round has sent the photo.
         */
        private suspend fun uploadPhotos(local: JsonElement): Set<String> {
            val unsent = LinkedHashSet<String>()
            for ((id, base64) in MealPhotos.photos(local)) {
                val known = photoLedger()
                if (id in known.uploaded || id in known.rejected || id in known.sent) continue
                if (unsent.isNotEmpty()) {
                    unsent += id
                    continue
                }
                val jpeg = runCatching { Base64.decode(base64) }.getOrNull()
                if (jpeg == null) {
                    updatePhotoLedger { it.copy(rejected = it.rejected + id) }
                    continue
                }
                try {
                    client.sendBytes(HttpMethod.Put, "photos/$id", jpeg)
                    updatePhotoLedger { it.copy(sent = it.sent + id) }
                } catch (error: ApiException) {
                    if (error.code in PHOTO_REJECTIONS) {
                        updatePhotoLedger { it.copy(rejected = it.rejected + id) }
                    } else {
                        unsent += id
                        log("sync: nutrition photo upload failed (${describe(error)}), the rest waits for a later round")
                    }
                }
            }
            return unsent
        }

        /** Server photos of meals in [merged] that the phone does not have. */
        private fun wantedPhotos(merged: JsonElement, remote: JsonElement, localPhotos: Map<String, String>): Set<String> {
            val meals = MealPhotos.mealIds(merged)
            return (MealPhotos.referenced(merged) + MealPhotos.referenced(remote)).filterTo(LinkedHashSet()) { it in meals && it !in localPhotos }
        }

        /**
         * Downloads [wanted] photos into [cache] (null = the server has none, 404). After any other failure the rest
         * is left for a later round ([unreachable]).
         */
        private suspend fun downloadPhotos(wanted: Set<String>, cache: MutableMap<String, String?>, unreachable: MutableSet<String>) {
            for (id in wanted) {
                if (id in cache || id in unreachable) continue
                if (unreachable.isNotEmpty()) {
                    unreachable += id
                    continue
                }
                when (val photo = downloadPhoto(id)) {
                    is Fetched.Photo -> cache[id] = photo.base64
                    Fetched.Missing -> cache[id] = null
                    Fetched.Later -> unreachable += id
                }
            }
        }

        private suspend fun downloadPhoto(id: String): Fetched = try {
            Fetched.Photo(Base64.encode(client.getBytes("photos/$id")))
        } catch (error: ApiException) {
            if (error.code == NOT_FOUND) {
                Fetched.Missing
            } else {
                log("sync: nutrition photo download failed (${describe(error)}), it waits for a later round")
                Fetched.Later
            }
        }

        /** Photos that an earlier round could not download (`pendingDownloads`) go into the local nutrition file. */
        suspend fun retryPhotoDownloads() {
            val waiting = photoLedger().pendingDownloads
            if (waiting.isEmpty()) return
            val fetched = HashMap<String, String>()
            val missing = HashSet<String>()
            for (id in waiting) {
                when (val photo = downloadPhoto(id)) {
                    is Fetched.Photo -> fetched[id] = photo.base64
                    Fetched.Missing -> missing += id
                    Fetched.Later -> break
                }
            }
            if (fetched.isEmpty() && missing.isEmpty()) return
            val file = fileOf(NUTRITION)
            applyFile(folder, file) {
                val local = readText(file)?.let(::parse)
                val meals = local?.let(MealPhotos::mealIds).orEmpty()
                // A photo the player attached meanwhile wins over the server's.
                val ownPhotos = local?.let(MealPhotos::photos).orEmpty().keys
                val restored = fetched.filterKeys { it in meals && it !in ownPhotos }
                if (local != null) {
                    val result = MealPhotos.restore(local, restored, keep = waiting - fetched.keys - missing)
                    if (result != local) {
                        scope.files.writeText(path(file), PulseJson.encodeToString(JsonElement.serializer(), result))
                        downloaded += file
                    }
                }
                updatePhotoLedger {
                    it.copy(uploaded = it.uploaded + restored.keys, pendingDownloads = it.pendingDownloads - fetched.keys - missing)
                }
            }
        }

        private suspend fun fetch(collection: String): RemoteDocument? = try {
            client.send(HttpMethod.Get, "sync/$collection", RemoteDocument.serializer())
        } catch (error: ApiException) {
            if (error.code == NOT_FOUND) null else throw error
        }

        /** Photo ids and their state, kept in `sync-photos.json` (never marked changed, never sent). */
        private fun photoLedger(): PhotoLedger = readText(PHOTOS_FILE)
            ?.let { runCatching { PulseJson.decodeFromString(PhotoLedger.serializer(), it) }.getOrNull() }
            ?: PhotoLedger()

        private fun updatePhotoLedger(change: (PhotoLedger) -> PhotoLedger) {
            val current = photoLedger()
            val next = change(current)
            if (next == current) return
            val sorted = PhotoLedger(
                next.uploaded.sorted().toSet(), next.rejected.sorted().toSet(), next.sent.sorted().toSet(), next.pendingDownloads.sorted().toSet()
            )
            scope.files.writeText(path(PHOTOS_FILE), PulseJson.encodeToString(PhotoLedger.serializer(), sorted))
        }

        /**
         * The copy for the server: nutrition without photos. Refused photos and [unsentPhotos] leave no `photoRef`
         * behind; photos still to download keep theirs, so an upload never drops the server's reference.
         */
        private fun forServer(collection: String, local: JsonElement, unsentPhotos: Set<String> = emptySet()): JsonElement {
            if (collection != NUTRITION) return local
            val photos = photoLedger()
            return MealPhotos.strip(local, unreferenced = photos.rejected + unsentPhotos, referenced = photos.pendingDownloads)
        }
    }

    /** What one photo download gave. */
    private sealed interface Fetched {
        data class Photo(val base64: String) : Fetched
        /** The server has no photo for that meal (404): the meal stays without one. */
        data object Missing : Fetched
        /** Not now (network, rate limit, server error): a later round asks again. */
        data object Later : Fetched
    }

    /** The app kept rewriting a file while its server copy was being merged in: the round stops, the next one retries. */
    private class LocalFileBusy : Exception("Local file changed during sync")

    /**
     * `uploaded`: on the server (sent with an accepted nutrition write, or downloaded). `rejected`: refused for good.
     * `sent`: uploaded, but no nutrition write with its meal accepted yet. `pendingDownloads`: on the server, not yet
     * on the phone.
     */
    @Serializable
    private data class PhotoLedger(
        val uploaded: Set<String> = emptySet(),
        val rejected: Set<String> = emptySet(),
        val sent: Set<String> = emptySet(),
        val pendingDownloads: Set<String> = emptySet()
    )

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
        const val MAX_ABSORB_PASSES = 3
        const val NUTRITION = "nutrition"
        const val NOT_FOUND = "not_found"
        /** Photo errors that a retry cannot fix. */
        val PHOTO_REJECTIONS = setOf("payload_too_large", "validation_failed")
        const val PHOTOS_FILE = "sync-photos.json"
        const val GUEST = "guest"
        const val DEMO_PREFIX = "demo-"

        fun collectionOf(file: String) = file.removeSuffix(".json")
        fun fileOf(collection: String) = "$collection.json"

        /** Kind, code and HTTP status of an API error; only the type of anything else (its message may quote data). */
        fun describe(error: Exception): String = when (error) {
            is ApiException -> "${error.kind} ${error.code ?: "-"}, HTTP ${error.status ?: "-"}"
            is LocalFileBusy -> "file kept changing"
            else -> error::class.simpleName ?: "error"
        }
    }
}
