package com.pulse.core.sync

import com.pulse.core.api.ApiClient
import com.pulse.core.api.ApiException
import com.pulse.core.api.ApiJson
import com.pulse.core.model.Lineup
import com.pulse.core.store.LineupStore
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable

@Serializable
private data class TeamLineupsResponse(val lineups: List<Lineup>)

/** Separate team feed: shared entries never overwrite a player's personal lineups document. */
class TeamLineupSync(
    private val client: ApiClient,
    private val lineups: LineupStore,
    private val notices: TeamSyncNotices? = null
) {
    var log: (String) -> Unit = {}

    /**
     * Pulls the team's lineups, then sends the queued changes one by one. A change the server will never accept (4xx
     * other than 401/408/429) leaves the queue with a notice and the server's version of the entry; anything else
     * (offline, 5xx, 401, 408, 429) keeps it queued and ends the round with the error.
     */
    suspend fun sync(): SyncResult {
        var changed = false
        var uploaded = false
        val remote = fetch()
        lineups.applyServerTeamLineups(remote.lineups)
        val existing = remote.lineups.map { it.id.toString().lowercase() }.toSet()
        val titles = remote.lineups.associate { it.id.toString().lowercase() to it.title }
        for ((id, lineup) in lineups.pendingTeamChanges()) {
            try {
                if (lineup == null) {
                    client.sendEmpty(HttpMethod.Delete, "lineups/$id")
                } else {
                    val method = if (id.lowercase() in existing) HttpMethod.Put else HttpMethod.Post
                    val path = if (method == HttpMethod.Put) "lineups/$id" else "lineups"
                    client.sendEmpty(method, path, ApiJson.encodeToJsonElement(Lineup.serializer(), lineup))
                }
                uploaded = true
            } catch (error: ApiException) {
                when {
                    // Already gone: another editor removed it first.
                    lineup == null && error.status == 404 -> uploaded = true
                    TeamSyncRefusal.isPermanent(error) -> {
                        refuse(id, lineup?.title ?: titles[id.lowercase()] ?: "запись", error)
                        changed = true
                    }
                    else -> throw error
                }
            }
            lineups.acknowledgeTeamChange(id, lineup)
            changed = true
        }
        if (changed) lineups.applyServerTeamLineups(fetch().lineups)
        return SyncResult(
            uploaded = if (uploaded) listOf("team-lineups") else emptyList(),
            downloaded = listOf("team-lineups"),
            failed = false
        )
    }

    private fun refuse(id: String, title: String, error: ApiException) {
        log(TeamSyncRefusal.logLine("team-lineups", error))
        notices?.add(id, TeamSyncRefusal.text(title, error.status))
    }

    private suspend fun fetch(): TeamLineupsResponse = client.send(HttpMethod.Get, "lineups", TeamLineupsResponse.serializer())
}
