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
class TeamLineupSync(private val client: ApiClient, private val lineups: LineupStore) {
    var log: (String) -> Unit = {}

    suspend fun sync(): SyncResult {
        var uploaded = false
        val remote = fetch()
        lineups.applyServerTeamLineups(remote.lineups)
        val existing = remote.lineups.map { it.id.toString() }.toSet()
        for ((id, lineup) in lineups.pendingTeamChanges()) {
            if (lineup == null) {
                try {
                    client.sendEmpty(HttpMethod.Delete, "lineups/$id")
                } catch (error: ApiException) {
                    if (error.status != 404) throw error
                }
            } else {
                val method = if (id in existing) HttpMethod.Put else HttpMethod.Post
                val path = if (method == HttpMethod.Put) "lineups/$id" else "lineups"
                client.sendEmpty(method, path, ApiJson.encodeToJsonElement(Lineup.serializer(), lineup))
            }
            lineups.acknowledgeTeamChange(id, lineup)
            uploaded = true
        }
        if (uploaded) lineups.applyServerTeamLineups(fetch().lineups)
        return SyncResult(
            uploaded = if (uploaded) listOf("team-lineups") else emptyList(),
            downloaded = listOf("team-lineups"),
            failed = false
        )
    }

    private suspend fun fetch(): TeamLineupsResponse = client.send(HttpMethod.Get, "lineups", TeamLineupsResponse.serializer())
}
