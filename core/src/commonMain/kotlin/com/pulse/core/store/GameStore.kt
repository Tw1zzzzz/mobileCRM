@file:UseSerializers(IsoInstantSerializer::class)

package com.pulse.core.store

import com.pulse.core.demo.GameData
import com.pulse.core.demo.GameMetric
import com.pulse.core.model.DailyValue
import com.pulse.core.model.FaceitMapper
import com.pulse.core.model.FaceitMatchStatsList
import com.pulse.core.model.FaceitPlayer
import com.pulse.core.model.FaceitPlayerStats
import com.pulse.core.model.FaceitProfile
import com.pulse.core.model.FaceitRanking
import com.pulse.core.model.GameMatch
import com.pulse.core.model.PlayerProfile
import com.pulse.core.model.User
import com.pulse.core.platform.SecretStore
import com.pulse.core.storage.JsonFileStorage
import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.PulseJson
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.addingDays
import com.pulse.core.util.isSameDay
import com.pulse.core.util.startOfDay
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.time.Clock
import kotlin.time.Instant

/** Loads a player's CS2 data from Faceit (direct today, a server proxy later — screens don't change). */
interface FaceitProvider {
    suspend fun profile(nickname: String): FaceitProfile
}

class FaceitException(val kind: Kind, val status: Int = 0) : Exception() {
    enum class Kind { INVALID_KEY, PLAYER_NOT_FOUND, NO_CS2_PROFILE, RATE_LIMITED, OFFLINE, SERVER, UNREADABLE }

    val description: String
        get() = when (kind) {
            Kind.INVALID_KEY -> "Faceit не принял API-ключ. Проверь ключ в настройках Faceit."
            Kind.PLAYER_NOT_FOUND -> "Игрок с таким ником не найден на Faceit. Ник чувствителен к регистру."
            Kind.NO_CS2_PROFILE -> "У этого игрока нет профиля CS2 на Faceit."
            Kind.RATE_LIMITED -> "Слишком много запросов к Faceit. Попробуй через минуту."
            Kind.OFFLINE -> "Не удалось установить соединение с сервером"
            Kind.SERVER -> "Faceit временно недоступен (код $status)."
            Kind.UNREADABLE -> "Не удалось разобрать ответ Faceit."
        }
}

/**
 * Direct client for the Faceit Data API v4. Only public data is read; the key is supplied by the user and kept
 * in the secure store (never in the source). ELO history is not exposed by the Data API.
 */
class FaceitClient(private val apiKey: String, private val http: HttpClient) : FaceitProvider {
    override suspend fun profile(nickname: String): FaceitProfile = coroutineScope {
        val player = get("players", FaceitPlayer.serializer(), "nickname" to nickname, "game" to GAME)
        val cs2 = player.cs2 ?: throw FaceitException(FaceitException.Kind.NO_CS2_PROFILE)
        val id = player.playerId
        val stats = async { runCatching { get("players/$id/stats/$GAME", FaceitPlayerStats.serializer()) }.getOrNull() }
        val matchList = async { get("players/$id/games/$GAME/stats", FaceitMatchStatsList.serializer(), "limit" to MATCH_LIMIT.toString()) }
        val rank = cs2.region?.let { region ->
            runCatching { get("rankings/games/$GAME/regions/$region/players/$id", FaceitRanking.serializer(), "limit" to "1") }.getOrNull()
        }
        val lifetime = stats.await()
        val matches = matchList.await().items.mapNotNull { FaceitMapper.match(it.stats) }.sortedByDescending { it.date }
        FaceitProfile(
            playerId = id, nickname = player.nickname, avatar = player.avatar, country = player.country, region = cs2.region,
            elo = cs2.faceitElo ?: 0, skillLevel = cs2.skillLevel, regionRank = rank?.position,
            lifetime = lifetime?.let { FaceitMapper.lifetime(it.lifetime) },
            maps = FaceitMapper.maps(lifetime?.segments ?: emptyList()),
            matches = matches, fetchedAt = Clock.System.now()
        )
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>, vararg query: Pair<String, String>): T {
        val response = try {
            http.get("$BASE_URL/$path") {
                query.forEach { (name, value) -> parameter(name, value) }
                header("Authorization", "Bearer $apiKey")
                header("Accept", "application/json")
            }
        } catch (error: Exception) {
            throw FaceitException(FaceitException.Kind.OFFLINE)
        }
        when (val status = response.status.value) {
            in 200..299 -> Unit
            401, 403 -> throw FaceitException(FaceitException.Kind.INVALID_KEY)
            404 -> throw FaceitException(FaceitException.Kind.PLAYER_NOT_FOUND)
            429 -> throw FaceitException(FaceitException.Kind.RATE_LIMITED)
            else -> throw FaceitException(FaceitException.Kind.SERVER, status)
        }
        return runCatching { PulseJson.decodeFromString(serializer, response.bodyAsText()) }
            .getOrElse { throw FaceitException(FaceitException.Kind.UNREADABLE) }
    }

    companion object {
        const val BASE_URL = "https://open.faceit.com/data/v4"
        const val GAME = "cs2"
        const val MATCH_LIMIT = 100
    }
}

/** Player-side CS2 data for every game screen: real Faceit data when connected, demo matches otherwise («faceit.json»). */
class GameStore(
    private val storage: JsonFileStorage<Saved>?,
    private val secrets: SecretStore,
    private val makeClient: (String) -> FaceitProvider
) : PulseStore<GameStore.State>(State()) {

    @Serializable
    data class EloSnapshot(val date: Instant, val elo: Int)

    @Serializable
    data class Saved(val nickname: String? = null, val playerId: String? = null, val snapshots: List<EloSnapshot> = emptyList())

    data class State(
        val faceit: FaceitProfile? = null,
        val isSyncing: Boolean = false,
        val error: String? = null,
        val nickname: String? = null,
        /** One ELO value per day, collected on every refresh — the Data API has no ELO history. */
        val snapshots: List<EloSnapshot> = emptyList(),
        /** Demo accounts fall back to demo matches; real accounts show nothing until Faceit is connected. */
        val isDemo: Boolean = true
    )

    init {
        storage?.load()?.let { current = current.copy(nickname = it.nickname, snapshots = it.snapshots) }
    }

    /** Something to show on game screens: real Faceit data, or the demo for demo accounts. */
    val hasData: Boolean get() = current.faceit != null || current.isDemo
    val hasApiKey: Boolean get() = !secrets.read(API_KEY_ACCOUNT).isNullOrEmpty()
    val isConnected: Boolean get() = current.nickname != null && hasApiKey
    val usesRealData: Boolean get() = current.faceit != null

    fun reload(isDemo: Boolean) {
        val saved = storage?.load()
        current = State(nickname = saved?.nickname, snapshots = saved?.snapshots ?: emptyList(), isDemo = isDemo)
    }

    suspend fun connect(nickname: String, apiKey: String?) {
        val name = FaceitMapper.nickname(nickname) ?: nickname.trim()
        if (name.isEmpty()) {
            current = current.copy(error = "Укажи ник или ссылку на профиль Faceit")
            return
        }
        apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { secrets.write(API_KEY_ACCOUNT, it) }
        if (!hasApiKey) {
            current = current.copy(error = "Вставь API-ключ Faceit")
            return
        }
        current = current.copy(nickname = name, snapshots = if (name != current.nickname) emptyList() else current.snapshots)
        persist(null)
        refresh()
    }

    suspend fun refresh(now: Instant = Clock.System.now()) {
        val nickname = current.nickname ?: return
        val key = secrets.read(API_KEY_ACCOUNT)?.takeIf { it.isNotEmpty() } ?: return
        if (current.isSyncing) return
        current = current.copy(isSyncing = true)
        try {
            val profile = makeClient(key).profile(nickname)
            current = current.copy(faceit = profile, error = null, snapshots = recording(profile.elo, now, current.snapshots))
            persist(profile.playerId)
        } catch (error: FaceitException) {
            current = current.copy(error = error.description)
        } catch (error: Exception) {
            current = current.copy(error = "Не удалось обновить данные Faceit")
        } finally {
            current = current.copy(isSyncing = false)
        }
    }

    fun disconnect(forgetKey: Boolean = false) {
        current = current.copy(faceit = null, nickname = null, error = null, snapshots = emptyList())
        if (forgetKey) secrets.write(API_KEY_ACCOUNT, null)
        storage?.save(Saved())
    }

    private fun persist(playerId: String?) {
        storage?.save(Saved(current.nickname, playerId, current.snapshots))
    }

    // Data for screens (Faceit → demo fallback)

    fun profile(user: User?): PlayerProfile {
        val demo = GameData.profile(user)
        val faceit = current.faceit
        if (faceit == null) {
            if (!current.isDemo) {
                return PlayerProfile(demo.id, user?.nickname ?: "", "", "", Clock.System.now(), demo.stats, 0, demo.seed)
            }
            return demo
        }
        return PlayerProfile(demo.id, faceit.nickname, demo.realName, demo.gameRole, faceit.matches.firstOrNull()?.date ?: demo.lastActive,
            demo.stats, faceit.elo, demo.seed, demo.readinessRange)
    }

    /** Newest first. */
    fun matches(user: User?, days: Int = 30, now: Instant = Clock.System.now()): List<GameMatch> {
        val faceit = current.faceit ?: return if (current.isDemo) GameData.matches(GameData.profile(user), days, now) else emptyList()
        val from = now.startOfDay().addingDays(-(days - 1))
        return faceit.matches.filter { it.date >= from }
    }

    /** Recent matches regardless of date (goals and summaries use «last N matches»). */
    fun recentMatches(user: User?, count: Int): List<GameMatch> {
        val faceit = current.faceit ?: return if (current.isDemo) GameData.matches(GameData.profile(user)).take(count) else emptyList()
        return faceit.matches.take(count)
    }

    fun eloHistory(user: User?, days: Int, now: Instant = Clock.System.now()): List<DailyValue> {
        if (current.faceit == null) return if (current.isDemo) GameData.eloHistory(GameData.profile(user), days, now) else emptyList()
        val from = now.startOfDay().addingDays(-(days - 1))
        return current.snapshots.filter { it.date >= from }.map { DailyValue(it.date.startOfDay(), it.elo.toDouble()) }
    }

    fun daily(metric: GameMetric, user: User?, days: Int, now: Instant = Clock.System.now()): List<DailyValue> {
        if (current.faceit == null) return if (current.isDemo) GameData.daily(metric, GameData.profile(user), days, now) else emptyList()
        val list = matches(user, days, now)
        return (days - 1 downTo 0).mapNotNull { offset ->
            val day = now.startOfDay().addingDays(-offset)
            StatisticsMath.mean(list.filter { it.date.isSameDay(day) }.map { metric.value(it) })?.let { DailyValue(day, it) }
        }
    }

    companion object {
        const val FILE = "faceit.json"
        const val API_KEY_ACCOUNT = "faceit.apiKey"

        /** Keeps one snapshot per day (the latest value of that day). */
        fun recording(elo: Int, date: Instant, list: List<EloSnapshot>): List<EloSnapshot> =
            (list.filter { !it.date.isSameDay(date) } + EloSnapshot(date, elo)).sortedBy { it.date }.takeLast(400)
    }
}
