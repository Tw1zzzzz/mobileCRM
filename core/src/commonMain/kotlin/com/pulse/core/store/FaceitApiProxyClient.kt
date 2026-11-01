package com.pulse.core.store

import com.pulse.core.api.ApiClient
import com.pulse.core.api.ApiException
import com.pulse.core.model.FaceitMapper
import com.pulse.core.model.FaceitMatchStatsList
import com.pulse.core.model.FaceitPlayer
import com.pulse.core.model.FaceitPlayerStats
import com.pulse.core.model.FaceitProfile
import com.pulse.core.model.FaceitRanking
import com.pulse.core.model.GameMatch
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.time.Clock

/** Uses the authenticated Pulse API; the Faceit secret stays on the server. */
class FaceitApiProxyClient(private val api: ApiClient) : FaceitProvider {
    override suspend fun profile(nickname: String): FaceitProfile = coroutineScope {
        val player = get("faceit/players?nickname=${nickname.encodeURLParameter()}&game=cs2", FaceitPlayer.serializer())
        val cs2 = player.cs2 ?: throw FaceitException(FaceitException.Kind.NO_CS2_PROFILE)
        val id = player.playerId
        val stats = async { runCatching { get("faceit/players/$id/stats", FaceitPlayerStats.serializer()) }.getOrNull() }
        val matchList = async { matches(id) }
        val rank = cs2.region?.let { region ->
            runCatching { get("faceit/rankings/games/cs2/regions/${region.encodeURLParameter()}/players/$id", FaceitRanking.serializer()) }.getOrNull()
        }
        val lifetime = stats.await()
        FaceitProfile(
            playerId = id, nickname = player.nickname, avatar = player.avatar, country = player.country, region = cs2.region,
            elo = cs2.faceitElo ?: 0, skillLevel = cs2.skillLevel, regionRank = rank?.position,
            lifetime = lifetime?.let { FaceitMapper.lifetime(it.lifetime) },
            maps = FaceitMapper.maps(lifetime?.segments ?: emptyList()),
            matches = matchList.await(), fetchedAt = Clock.System.now()
        )
    }

    override suspend fun matches(playerId: String): List<GameMatch> {
        if (!PLAYER_ID.matches(playerId)) throw FaceitException(FaceitException.Kind.PLAYER_NOT_FOUND)
        return get("faceit/players/$playerId/games/cs2/stats", FaceitMatchStatsList.serializer())
            .items.mapNotNull { FaceitMapper.match(it.stats) }.sortedByDescending { it.date }
    }

    private suspend fun <T> get(path: String, serializer: kotlinx.serialization.KSerializer<T>): T = try {
        api.send(HttpMethod.Get, path, serializer)
    } catch (stopped: CancellationException) {
        throw stopped
    } catch (error: ApiException) {
        throw when {
            error.status == 404 || error.code == "not_found" -> FaceitException(FaceitException.Kind.PLAYER_NOT_FOUND)
            error.status == 429 || error.code == "rate_limited" -> FaceitException(FaceitException.Kind.RATE_LIMITED)
            error.kind == ApiException.Kind.OFFLINE || error.kind == ApiException.Kind.NOT_CONFIGURED -> FaceitException(FaceitException.Kind.OFFLINE)
            else -> FaceitException(FaceitException.Kind.SERVER, error.status ?: 0)
        }
    }

    companion object {
        private val PLAYER_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
