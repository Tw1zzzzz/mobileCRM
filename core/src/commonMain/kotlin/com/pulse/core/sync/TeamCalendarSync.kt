package com.pulse.core.sync

import com.pulse.core.api.ApiClient
import com.pulse.core.api.ApiException
import com.pulse.core.api.ApiJson
import com.pulse.core.model.CalendarEvent
import com.pulse.core.model.EventTag
import com.pulse.core.store.CalendarStore
import com.pulse.core.util.IsoInstantSerializer
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
private data class TeamCalendarResponse(
    val teamId: String,
    val events: List<TeamCalendarEvent>,
    val players: List<TeamCalendarPlayer> = emptyList()
)

@Serializable
private data class TeamCalendarPlayer(val id: String, val nickname: String)

@Serializable
private data class TeamCalendarEvent(
    val id: String,
    val title: String,
    val details: String,
    @Serializable(with = IsoInstantSerializer::class) val start: kotlin.time.Instant,
    @Serializable(with = IsoInstantSerializer::class) val end: kotlin.time.Instant,
    val links: List<String> = emptyList(),
    val tag: String,
    val playerNickname: String? = null,
    val ownerId: String? = null
) {
    fun toModel(): CalendarEvent? {
        val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return null
        val tagValue = when (tag) {
            "team" -> EventTag.Team
            "player" -> playerNickname?.let(EventTag::Player) ?: return null
            else -> return null
        }
        val owner = ownerId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        return CalendarEvent(uuid, title, details, start, end, links, tagValue, owner)
    }
}

@Serializable
private data class TeamCalendarWrite(
    val id: String,
    val title: String,
    val details: String,
    @Serializable(with = IsoInstantSerializer::class) val start: kotlin.time.Instant,
    @Serializable(with = IsoInstantSerializer::class) val end: kotlin.time.Instant,
    val links: List<String>,
    val tag: String,
    /** Nicknames are not unique in a team: the id is preferred, the nickname stays for servers that predate it. */
    val playerId: String? = null,
    val playerNickname: String? = null
) {
    constructor(event: CalendarEvent, playerIds: Map<String, String>) : this(
        id = event.id.toString(), title = event.title, details = event.details, start = event.start, end = event.end,
        links = event.links, tag = if (event.tag == EventTag.Team) "team" else "player",
        playerId = (event.tag as? EventTag.Player)?.let { playerIds[it.name] },
        playerNickname = (event.tag as? EventTag.Player)?.name
    )
}

/** Team events have a separate team-scoped endpoint; personal calendar entries stay in the per-user sync document. */
class TeamCalendarSync(
    private val client: ApiClient,
    private val calendar: CalendarStore,
    private val notices: TeamSyncNotices? = null
) {
    var onCalendarChanged: (() -> Unit)? = null
    var log: (String) -> Unit = {}

    /**
     * Pulls the team's events, then sends the queued changes one by one. A change the server will never accept (4xx
     * other than 401/408/429) leaves the queue with a notice and the server's version of the event; anything else
     * (offline, 5xx, 401, 408, 429) keeps it queued and ends the round with the error.
     */
    suspend fun sync(): SyncResult {
        var changed = false
        var uploaded = false
        val remote = fetch()
        apply(remote)
        val existing = remote.events.mapNotNull { runCatching { Uuid.parse(it.id) }.getOrNull() }.toSet()
        val titles = remote.events.associate { it.id.lowercase() to it.title }
        val playerIds = playerIds(remote.players)
        for ((id, event) in calendar.pendingSharedChanges()) {
            try {
                if (event == null) {
                    client.sendEmpty(HttpMethod.Delete, "calendar/$id")
                } else {
                    val method = if (runCatching { Uuid.parse(id) }.getOrNull() in existing) HttpMethod.Put else HttpMethod.Post
                    val path = if (method == HttpMethod.Put) "calendar/$id" else "calendar"
                    client.sendEmpty(method, path, body(event, playerIds))
                }
                uploaded = true
            } catch (error: ApiException) {
                when {
                    // Already gone: another editor removed it first.
                    event == null && error.status == 404 -> uploaded = true
                    TeamSyncRefusal.isPermanent(error) -> {
                        refuse(id, event?.title ?: titles[id.lowercase()] ?: "событие", error)
                        changed = true
                    }
                    else -> {
                        // Whatever was sent or refused so far shows the server's state even though the round ends here.
                        if (changed) runCatching { apply(fetch()) }.onFailure { if (it !is ApiException) throw it }
                        throw error
                    }
                }
            }
            calendar.acknowledgeSharedChange(id, event)
            changed = true
        }
        if (changed) apply(fetch())
        return SyncResult(
            uploaded = if (uploaded) listOf("calendar") else emptyList(),
            downloaded = listOf("calendar"),
            failed = false
        )
    }

    private fun refuse(id: String, title: String, error: ApiException) {
        log(TeamSyncRefusal.logLine("team-calendar", error))
        notices?.add(id, TeamSyncRefusal.text(title, error.status))
    }

    /** Nickname → player id, only where the nickname names a single player. */
    private fun playerIds(players: List<TeamCalendarPlayer>): Map<String, String> =
        players.groupBy { it.nickname }.filterValues { it.size == 1 }.mapValues { it.value.single().id }

    private suspend fun fetch(): TeamCalendarResponse = client.send(HttpMethod.Get, "calendar", TeamCalendarResponse.serializer())

    private fun apply(response: TeamCalendarResponse) {
        if (calendar.applyServerCalendar(response.events.mapNotNull(TeamCalendarEvent::toModel), response.players.map(TeamCalendarPlayer::nickname), response.teamId)) {
            onCalendarChanged?.invoke()
        }
    }

    private fun body(event: CalendarEvent, playerIds: Map<String, String>): kotlinx.serialization.json.JsonElement =
        ApiJson.encodeToJsonElement(TeamCalendarWrite.serializer(), TeamCalendarWrite(event, playerIds))
}
