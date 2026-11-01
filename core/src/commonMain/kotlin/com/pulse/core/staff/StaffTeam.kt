package com.pulse.core.staff

import com.pulse.core.api.ApiClient
import com.pulse.core.api.ApiException
import com.pulse.core.feature.StaffMetrics
import com.pulse.core.platform.locked
import com.pulse.core.storage.DataScope
import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.PulseJson
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.time.Clock
import kotlin.time.Instant

/** One member of the staff account's team, as the roster lists them. */
@Serializable
data class StaffPlayer(
    val id: String,
    val nickname: String,
    /** When the player last changed any of their data; null = never. */
    @Serializable(with = IsoInstantSerializer::class) val lastActivity: Instant? = null
)

/** The team as last downloaded: the roster and each player's stored documents (the app's own JSON, as stored). */
@Serializable
data class TeamSnapshot(
    val teamName: String,
    val inviteCode: String? = null,
    val players: List<StaffPlayer>,
    /** Player id → collection → document. A player without data has an empty map. */
    val documents: Map<String, Map<String, JsonElement>>,
    @Serializable(with = IsoInstantSerializer::class) val fetchedAt: Instant,
    /** Players whose documents could not be downloaded in that refresh (their earlier documents, if any, are kept). */
    val missing: Set<String> = emptySet()
)

sealed interface StaffTeamState {
    /** Not a real staff account of a server build, or nothing downloaded or loaded yet. */
    data object Idle : StaffTeamState
    /** The first download, with no copy to show. */
    data object Loading : StaffTeamState
    /** [stale]: the last refresh failed or none ran since the copy was loaded from the phone. */
    data class Ready(val team: TeamSnapshot, val stale: Boolean) : StaffTeamState
    /** The download failed and there is no copy. */
    data object Failed : StaffTeamState
}

/**
 * The staff account's team (roster and every player's documents) from the staff endpoints, with the last copy kept in the
 * account's folder (`staff-team.json`) for offline use. The copy is written straight to the files — never marked
 * changed, so it is never synced. Only a real staff account of a server build is [activate]d: demo accounts, local
 * builds and players keep [StaffTeamState.Idle] and never reach the server.
 */
class StaffTeam(
    private val client: ApiClient,
    private val scope: DataScope,
    /** Where a refresh works (decoding every player's documents, writing the copy): never the caller's, usually the UI's, thread. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Instant = { Clock.System.now() }
) {
    private val _state = MutableStateFlow<StaffTeamState>(StaffTeamState.Idle)
    val state: StateFlow<StaffTeamState> = _state.asStateFlow()

    /** Reports each failure: what failed, the error's kind, code and HTTP status — never document contents or tokens. */
    var log: (String) -> Unit = {}

    /** Guards the fields below, which [activate] (the UI) and a running refresh both touch. */
    private val lock = Any()
    /** Bumped by every [activate]: a result of an older epoch belongs to another account and is dropped. */
    private var epoch = 0L
    private var enabled = false
    private var folder = ""
    private var completedRefreshes = 0

    private val refreshLock = Mutex()
    private val running = MutableStateFlow<Job?>(null)

    /**
     * The active account changed (sign-in, sign-out, switch): shows that account's copy from the phone, if it has one,
     * and stops a refresh that belongs to the previous account. [enabled] is true for a real staff account only; for
     * any other account a leftover copy in its folder is deleted. The lock is held only to switch state: the copy is
     * read and deleted outside it, so a refresh writing a large file never makes this wait.
     */
    fun activate(enabled: Boolean) {
        running.value?.cancel()
        val on = enabled && client.isConfigured
        val accountFolder = scope.folder()
        val mine = locked(lock) {
            epoch++
            completedRefreshes = 0
            this.enabled = on
            folder = accountFolder
            _state.value = StaffTeamState.Idle
            epoch
        }
        if (!on) {
            removeCopy(accountFolder)
            return
        }
        val copy = loadCopy(accountFolder) ?: return
        locked(lock) {
            // A refresh started meanwhile (Loading) or another switch has the last word.
            if (epoch == mine && _state.value == StaffTeamState.Idle) _state.value = StaffTeamState.Ready(copy, stale = true)
        }
    }

    /**
     * Downloads the roster, then each player's documents (at most [PARALLEL_DOWNLOADS] at a time), and replaces the
     * copy. Never throws for a network or server error: with a copy on screen it is kept and marked stale, otherwise
     * the state becomes [StaffTeamState.Failed]. A refresh already running for the same account is awaited, not repeated.
     * The work runs on [dispatcher], whichever context the caller is in.
     */
    suspend fun refresh() = withContext(dispatcher) { refreshOnWorker() }

    private suspend fun refreshOnWorker() {
        val (ticketEpoch, ticketDone) = locked(lock) { epoch to completedRefreshes }
        refreshLock.withLock {
            // Null: switched off, or the refresh that was running while this call waited already did the work.
            val (current, currentFolder) = locked(lock) {
                if (!enabled || (epoch == ticketEpoch && completedRefreshes != ticketDone)) return@locked null
                if (_state.value !is StaffTeamState.Ready) _state.value = StaffTeamState.Loading
                epoch to folder
            } ?: return
            val downloaded = try {
                coroutineScope {
                    val round = async { download() }
                    running.value = round
                    round.await()
                }
            } catch (stopped: CancellationException) {
                // Only the refresh was stopped (the account changed): the next account's state is not ours to touch.
                currentCoroutineContext().ensureActive()
                return
            } finally {
                running.value = null
            }
            commit(current, currentFolder, downloaded)
        }
    }

    /** The meal's photo (`GET /staff/players/{id}/photos/{meal_id}`), or null: no photo, no network, not allowed. */
    suspend fun photo(playerId: String, mealId: String): ByteArray? {
        val before = locked(lock) { if (enabled) epoch else null } ?: return null
        val bytes = try {
            client.getBytes("staff/players/${playerId.encodeURLPathPart()}/photos/${mealId.encodeURLPathPart()}")
        } catch (error: ApiException) {
            if (error.status != NOT_FOUND) log("staff team: photo failed (${describe(error)})")
            return null
        }
        return bytes.takeIf { locked(lock) { epoch == before } }
    }

    /** Every player of the current copy with their documents, for the Kotlin twins of the site's staff formulas. */
    fun players(): List<StaffMetrics.PlayerDocuments> {
        val team = (state.value as? StaffTeamState.Ready)?.team ?: return emptyList()
        return team.players.map { StaffMetrics.PlayerDocuments(it.id, team.documents[it.id].orEmpty()) }
    }

    /** What one download brought. */
    private sealed interface Outcome {
        class Downloaded(val snapshot: TeamSnapshot) : Outcome
        /** The roster could not be had (offline, server error, rate limit): the copy, if any, stays. */
        data object Failed : Outcome
        /** The server no longer lets this account see a team (`forbidden`, or unauthorized): no copy may stay. */
        data object Revoked : Outcome
    }

    private suspend fun download(): Outcome {
        val roster = try {
            client.send(HttpMethod.Get, "staff/team", Roster.serializer())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("staff team: roster failed (${describe(error)})")
            return if (error is ApiException && error.isAccessRevoked) Outcome.Revoked else Outcome.Failed
        }
        val previous = (state.value as? StaffTeamState.Ready)?.team?.documents.orEmpty()
        val gate = Semaphore(PARALLEL_DOWNLOADS)
        val results = coroutineScope {
            roster.players.map { player ->
                async { player.id to gate.withPermit { documentsOf(player.id) } }
            }.awaitAll()
        }.toMap()
        val missing = results.filterValues { it == null }.keys
        val documents = roster.players.associate { player ->
            player.id to (results[player.id] ?: previous[player.id].orEmpty())
        }
        return Outcome.Downloaded(TeamSnapshot(roster.team.name, roster.team.inviteCode, roster.players, documents, now(), missing))
    }

    /** One player's stored documents among the requested collections; null when the request failed. */
    private suspend fun documentsOf(playerId: String): Map<String, JsonElement>? = try {
        val path = "staff/players/${playerId.encodeURLPathPart()}/documents?collections=${COLLECTIONS.joinToString(",")}"
        client.send(HttpMethod.Get, path, Documents.serializer()).documents
            // The server is asked for these and no others; whatever else arrives is not kept.
            .filter { (collection, document) -> collection in COLLECTIONS && document !is JsonNull }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log("staff team: documents failed (${describe(error)})")
        null
    }

    /**
     * Saves the result and publishes it unless the account changed meanwhile. The file work (encoding and writing a
     * possibly large team, deleting) happens outside the lock: the folder is the refresh's own account's, so a write
     * that is late only lands in that account's folder. The lock is taken to re-check the epoch and publish the state.
     */
    private fun commit(epochOfRefresh: Long, folderOfRefresh: String, outcome: Outcome) {
        if (locked(lock) { epoch != epochOfRefresh }) return
        when (outcome) {
            is Outcome.Downloaded -> saveCopy(folderOfRefresh, outcome.snapshot)
            // A removed staff member must not keep the team's data on the phone.
            Outcome.Revoked -> removeCopy(folderOfRefresh)
            Outcome.Failed -> Unit
        }
        locked(lock) {
            if (epoch != epochOfRefresh) return@locked
            completedRefreshes++
            _state.value = when (outcome) {
                is Outcome.Downloaded -> StaffTeamState.Ready(outcome.snapshot, stale = false)
                Outcome.Failed -> (_state.value as? StaffTeamState.Ready)?.copy(stale = true) ?: StaffTeamState.Failed
                Outcome.Revoked -> StaffTeamState.Idle
            }
        }
    }

    private fun saveCopy(folder: String, snapshot: TeamSnapshot) {
        try {
            scope.files.writeText(path(folder), PulseJson.encodeToString(TeamSnapshot.serializer(), snapshot))
        } catch (error: Exception) {
            // The copy on screen is fresh anyway; only the offline copy is behind.
            log("staff team: copy not saved (${error::class.simpleName})")
        }
    }

    /**
     * Deletes the copy. A file system that does not delete (the call fails silently) must not keep the team's data:
     * the file is then emptied, and [loadCopy] ignores an empty file.
     */
    private fun removeCopy(folder: String) {
        val path = path(folder)
        try {
            if (!scope.files.exists(path)) return
            scope.files.delete(path)
            if (!scope.files.exists(path)) return
            log("staff team: copy not deleted, emptied")
            scope.files.writeText(path, "")
        } catch (error: Exception) {
            log("staff team: copy not removed (${error::class.simpleName})")
        }
    }

    private fun loadCopy(folder: String): TeamSnapshot? {
        val text = scope.files.readText(path(folder))?.takeIf { it.isNotBlank() } ?: return null
        return try {
            PulseJson.decodeFromString(TeamSnapshot.serializer(), text)
        } catch (error: Exception) {
            log("staff team: copy on the phone is unreadable (${error::class.simpleName})")
            null
        }
    }

    private fun path(folder: String) = "$folder/$FILE"

    private val ApiException.isAccessRevoked: Boolean
        get() = kind == ApiException.Kind.UNAUTHORIZED || (kind == ApiException.Kind.REJECTED && code == FORBIDDEN)

    @Serializable
    private data class Roster(val team: TeamInfo, val players: List<StaffPlayer>)

    @Serializable
    private data class TeamInfo(val name: String, val inviteCode: String? = null)

    @Serializable
    private data class Documents(val documents: Map<String, JsonElement> = emptyMap())

    companion object {
        /** The copy in the account's folder; written directly, never marked changed, never sent by the sync. */
        const val FILE = "staff-team.json"
        /** The collections asked for per player (`body-profile` and `lineups` are never among them). */
        val COLLECTIONS = listOf("sleep", "wellbeing", "cognitive", "nutrition", "fitness-state", "screen-time", "faceit", "player-survey")
        const val PARALLEL_DOWNLOADS = 3
        private const val NOT_FOUND = 404
        private const val FORBIDDEN = "forbidden"

        /** Kind, code and HTTP status of an API error; only the type of anything else (its message may quote data). */
        private fun describe(error: Exception): String = when (error) {
            is ApiException -> "${error.kind} ${error.code ?: "-"}, HTTP ${error.status ?: "-"}"
            else -> error::class.simpleName ?: "error"
        }
    }
}
