package com.pulse.android.ui.staff

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.pulse.android.ui.observeCore
import com.pulse.core.model.GameMatch
import com.pulse.core.staff.TeamSnapshot
import com.pulse.core.util.Formatters
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

/** What Faceit gave for a team: [matches] by team player id, and the [failed] players (team player ids) it did not answer for. */
class TeamMatchesResult(val matches: Map<String, List<GameMatch>>, val failed: Set<String>)

/** «Faceit не ответил для 2 игроков»: shown wherever team figures are built from matches; null when nobody failed. */
fun faceitFailedText(failed: Int): String? =
    if (failed <= 0) null else "Faceit не ответил для ${Formatters.plural(failed, "игрока", "игроков", "игроков")}"

/**
 * The Faceit matches of the team's players, for the team analytics screens. A player's matches are downloaded once
 * (by [load], with at most [parallel] requests at a time) and kept for as long as this object lives — the screen's life.
 * A player whose matches could not be had is not remembered: the result names them in [TeamMatchesResult.failed] (the
 * team figures are then built without them) and the next [of] asks Faceit for them again.
 */
class TeamMatches(private val load: suspend (faceitPlayerId: String) -> List<GameMatch>?, private val parallel: Int = FACEIT_PARALLEL) {
    private val cache = HashMap<String, List<GameMatch>>()

    /** Bumped by [refresh]; screens that show the matches start over when it changes. */
    var generation by mutableIntStateOf(0)
        private set

    /** The screen was pulled to refresh: whoever Faceit failed for is asked again (see [generation]). */
    fun refresh() {
        generation++
    }

    /** Matches for the players of [links] (team player id → Faceit player id), and the players Faceit did not answer for. */
    suspend fun of(links: Map<String, String>): TeamMatchesResult = coroutineScope {
        val gate = Semaphore(parallel)
        val answers = links.map { (playerId, faceitId) ->
            async {
                val matches = synchronized(cache) { cache[faceitId] }
                    ?: gate.withPermit { load(faceitId) }?.also { synchronized(cache) { cache[faceitId] = it } }
                playerId to matches
            }
        }.awaitAll()
        TeamMatchesResult(
            answers.mapNotNull { (playerId, matches) -> matches?.let { playerId to it } }.toMap(),
            answers.filter { it.second == null }.map { it.first }.toSet()
        )
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

/**
 * The matches and who Faceit failed for; null until the first answer (the previous answer stays while a newer one is
 * awaited). Off the main thread. Asked again when the links change and when [TeamMatches.refresh] is called.
 */
@Composable
fun rememberTeamMatchesState(matches: TeamMatches, team: TeamSnapshot): State<TeamMatchesResult?> {
    val links = remember(team) { faceitLinks(team) }
    return produceState<TeamMatchesResult?>(null, matches, links, matches.generation) {
        value = withContext(Dispatchers.Default) { matches.of(links) }
    }
}
