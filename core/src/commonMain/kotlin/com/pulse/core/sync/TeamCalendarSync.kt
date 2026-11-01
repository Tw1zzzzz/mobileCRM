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
    val playerNickname: String? = null
) {
    constructor(event: CalendarEvent) : this(
        id = event.id.toString(), title = event.title, details = event.details, start = event.start, end = event.end,
        links = event.links, tag = if (event.tag == EventTag.Team) "team" else "player",
        playerNickname = (event.tag as? EventTag.Player)?.name
    )
}

/** Team events have a separate team-scoped endpoint; personal calendar entries stay in the per-user sync document. */
class TeamCalendarSync(private val client: ApiClient, private val calendar: CalendarStore) {
    var onCalendarChanged: (() -> Unit)? = null

    suspend fun sync(): SyncResult {
        var uploaded = false
        val remote = fetch()
        apply(remote)
        val existing = remote.events.mapNotNull { runCatching { Uuid.parse(it.id) }.getOrNull() }.toSet()
        for ((id, event) in calendar.pendingSharedChanges()) {
            if (event == null) {
                try {
                    client.sendEmpty(HttpMethod.Delete, "calendar/$id")
                } catch (error: ApiException) {
                    if (error.status != 404) throw error
                }
            } else {
                val method = if (runCatching { Uuid.parse(id) }.getOrNull() in existing) HttpMethod.Put else HttpMethod.Post
                val path = if (method == HttpMethod.Put) "calendar/$id" else "calendar"
                try {
                    client.sendEmpty(method, path, body(event))
                } catch (error: ApiException) {
                    // A web editor may have removed or made the event private while this phone was offline.
                    if (error.status != 404) throw error
                }
            }
            calendar.acknowledgeSharedChange(id, event)
            uploaded = true
        }
        if (uploaded) apply(fetch())
        return SyncResult(
            uploaded = if (uploaded) listOf("calendar") else emptyList(),
            downloaded = listOf("calendar"),
            failed = false
        )
    }

    private suspend fun fetch(): TeamCalendarResponse = client.send(HttpMethod.Get, "calendar", TeamCalendarResponse.serializer())

    private fun apply(response: TeamCalendarResponse) {
        if (calendar.applyServerCalendar(response.events.mapNotNull(TeamCalendarEvent::toModel), response.players.map(TeamCalendarPlayer::nickname), response.teamId)) {
            onCalendarChanged?.invoke()
        }
    }

    private fun body(event: CalendarEvent): kotlinx.serialization.json.JsonElement =
        ApiJson.encodeToJsonElement(TeamCalendarWrite.serializer(), TeamCalendarWrite(event))
}
