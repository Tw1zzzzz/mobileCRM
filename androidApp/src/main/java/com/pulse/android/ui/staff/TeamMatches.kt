package com.pulse.android.ui.staff

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.pulse.android.ui.observeCore
import com.pulse.core.model.GameMatch
import com.pulse.core.staff.TeamSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** At most this many Faceit requests run at the same time. */
private const val FACEIT_PARALLEL = 3

/**
 * The Faceit matches of the team's players, for the team analytics screens. A player's matches are downloaded once
 * (by [load], with at most [parallel] requests at a time) and kept for as long as this object lives — the screen's life;
 * a player whose matches could not be had is simply missing from the result, and is asked again next time.
 */
class TeamMatches(private val load: suspend (faceitPlayerId: String) -> List<GameMatch>?, private val parallel: Int = FACEIT_PARALLEL) {
    private val cache = HashMap<String, List<GameMatch>>()

    /** Matches by team player id, for the players of [links] (team player id → Faceit player id) that Faceit answered for. */
    suspend fun of(links: Map<String, String>): Map<String, List<GameMatch>> = coroutineScope {
        val gate = Semaphore(parallel)
        links.map { (playerId, faceitId) ->
            async {
                val matches = synchronized(cache) { cache[faceitId] }
                    ?: gate.withPermit { load(faceitId) }?.also { synchronized(cache) { cache[faceitId] = it } }
                matches?.let { playerId to it }
            }
        }.awaitAll().filterNotNull().toMap()
    }
}

/** Team player id → Faceit player id, for the players who linked Faceit (`faceit.playerId` of their documents). */
fun faceitLinks(team: TeamSnapshot): Map<String, String> = buildMap {
    for (player in team.players) {
        val id = ((team.documents[player.id]?.get("faceit") as? JsonObject)?.get("playerId") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        if (id != null) put(player.id, id)
    }
}

/** The team's Faceit matches holder of a screen: lives as long as the screen, asks the app's own Faceit client. */
@Composable
fun rememberTeamMatches(): TeamMatches {
    val core = observeCore()
    return remember(core) { TeamMatches(core.game::matchesOf) }
}

/** Matches by team player id; null until the first answer (the previous answer stays while a newer one is awaited). Off the main thread. */
@Composable
fun rememberTeamMatchesState(matches: TeamMatches, team: TeamSnapshot): State<Map<String, List<GameMatch>>?> {
    val links = remember(team) { faceitLinks(team) }
    return produceState<Map<String, List<GameMatch>>?>(null, matches, links) {
        value = withContext(Dispatchers.Default) { matches.of(links) }
    }
}
