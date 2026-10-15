package com.pulse.core.model

import com.pulse.core.util.roundToIntSwift
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Faceit Data API v4 DTOs (https://open.faceit.com/data/v4/docs/swagger.json)

/** `GET /players?nickname=&game=cs2` */
@Serializable
data class FaceitPlayer(
    @SerialName("player_id") val playerId: String,
    val nickname: String,
    val avatar: String? = null,
    val country: String? = null,
    @SerialName("faceit_url") val faceitUrl: String? = null,
    val games: Map<String, GameDetail>? = null
) {
    @Serializable
    data class GameDetail(
        @SerialName("faceit_elo") val faceitElo: Int? = null,
        @SerialName("skill_level") val skillLevel: Int? = null,
        val region: String? = null,
        @SerialName("game_player_name") val gamePlayerName: String? = null
    )

    val cs2: GameDetail? get() = games?.get("cs2")
}

/** Stat values in Faceit responses are loosely typed: numbers often arrive as strings ("1.25", "52"). */
typealias FaceitStats = Map<String, JsonElement>

fun JsonElement.faceitDouble(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    if (!primitive.isString) return primitive.doubleOrNull
    return primitive.content.replace(',', '.').trim().toDoubleOrNull()
}

fun JsonElement.faceitString(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return primitive.content
    val number = primitive.doubleOrNull ?: return null
    return if (number == kotlin.math.round(number)) number.toLong().toString() else number.toString()
}

fun JsonElement.faceitList(): List<String> =
    (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

fun FaceitStats.double(vararg keys: String): Double? = keys.firstNotNullOfOrNull { this[it]?.faceitDouble() }

fun FaceitStats.int(vararg keys: String): Int? = double(*keys)?.roundToIntSwift()

fun FaceitStats.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { this[it]?.faceitString() }

/** `GET /players/{id}/stats/cs2` — lifetime numbers and a segment per map. */
@Serializable
data class FaceitPlayerStats(val lifetime: FaceitStats, val segments: List<Segment>? = null) {
    @Serializable
    data class Segment(val label: String, val mode: String? = null, val type: String? = null, val stats: FaceitStats)
}

/** `GET /players/{id}/games/cs2/stats` — per-match stats of the player, newest first. */
@Serializable
data class FaceitMatchStatsList(val items: List<Item>) {
    @Serializable
    data class Item(val stats: FaceitStats)
}

/** `GET /rankings/games/cs2/regions/{region}/players/{id}` */
@Serializable
data class FaceitRanking(val position: Int? = null)

// App-side model

/** Everything we keep from Faceit for the signed-in player. */
data class FaceitProfile(
    val playerId: String,
    val nickname: String,
    val avatar: String?,
    val country: String?,
    val region: String?,
    val elo: Int,
    val skillLevel: Int?,
    val regionRank: Int?,
    val lifetime: Lifetime?,
    val maps: List<MapStats>,
    val matches: List<GameMatch>,
    val fetchedAt: Instant
) {
    data class MapStats(val map: String, val matches: Int, val winRate: Double, val kd: Double, val adr: Double?, val headshots: Double?)

    data class Lifetime(
        val matches: Int,
        val winRate: Double,
        val kd: Double,
        val headshots: Double,
        val adr: Double?,
        val longestWinStreak: Int,
        val currentWinStreak: Int,
        val recentResults: List<Boolean>,
        val entrySuccessRate: Double?,
        val utilityDamagePerRound: Double?,
        val clutch1v1WinRate: Double?
    )
}

object FaceitMapper {
    /** `https://www.faceit.com/ru/players/s1mple` → `s1mple` (a bare nickname is accepted too). */
    fun nickname(profileLink: String): String? {
        val trimmed = profileLink.trim()
        if (trimmed.isEmpty()) return null
        val host = WebLinks.host(trimmed)
        if (host == null || !host.contains("faceit.com")) {
            return if (trimmed.contains('/') || trimmed.contains(' ')) null else trimmed
        }
        val path = trimmed.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val parts = path.split('/').filter { it.isNotEmpty() }
        val index = parts.indexOf("players")
        return if (index >= 0 && index + 1 < parts.size) parts[index + 1] else null
    }

    fun lifetime(stats: FaceitStats) = FaceitProfile.Lifetime(
        matches = stats.int("Matches") ?: 0,
        winRate = stats.double("Win Rate %") ?: 0.0,
        kd = stats.double("Average K/D Ratio", "K/D Ratio") ?: 0.0,
        headshots = stats.double("Average Headshots %") ?: 0.0,
        adr = stats.double("ADR"),
        longestWinStreak = stats.int("Longest Win Streak") ?: 0,
        currentWinStreak = stats.int("Current Win Streak") ?: 0,
        recentResults = (stats["Recent Results"]?.faceitList() ?: emptyList()).map { it == "1" },
        entrySuccessRate = stats.double("Entry Success Rate")?.let { if (it <= 1) it * 100 else it },
        utilityDamagePerRound = stats.double("Utility Damage per Round"),
        clutch1v1WinRate = stats.double("1v1 Win Rate")?.let { if (it <= 1) it * 100 else it }
    )

    fun maps(segments: List<FaceitPlayerStats.Segment>): List<FaceitProfile.MapStats> = segments
        .filter { (it.type ?: "Map") == "Map" }
        .map { segment ->
            FaceitProfile.MapStats(
                map = segment.label,
                matches = segment.stats.int("Matches") ?: 0,
                winRate = segment.stats.double("Win Rate %") ?: 0.0,
                kd = segment.stats.double("Average K/D Ratio", "K/D Ratio") ?: 0.0,
                adr = segment.stats.double("ADR"),
                headshots = segment.stats.double("Average Headshots %")
            )
        }
        .filter { it.matches > 0 }
        .sortedByDescending { it.matches }

    /** Per-match stats → app match. ELO change is not part of the Data API (it stays 0). */
    fun match(stats: FaceitStats, now: Instant = Clock.System.now()): GameMatch? {
        val kills = stats.int("Kills") ?: return null
        val deaths = stats.int("Deaths") ?: return null
        val finished = stats.double("Match Finished At", "Updated At", "Created At")
        val date = finished?.let { Instant.fromEpochMilliseconds(((if (it > 10_000_000_000) it / 1000 else it) * 1000).toLong()) } ?: now
        val score = (stats.string("Score") ?: "").split('/').mapNotNull { it.trim().toIntOrNull() }
        val won = stats.string("Result") == "1"
        val ownRounds = if (score.size == 2) (if (won) score.max() else score.min()) else 0
        val otherRounds = if (score.size == 2) (if (won) score.min() else score.max()) else 0
        val rounds = max(stats.int("Rounds") ?: (ownRounds + otherRounds), 1)
        val matchId = stats.string("Match Id")
        return GameMatch(
            id = matchId?.let { uuidOrNull(it.removePrefix("1-")) } ?: Uuid.random(),
            date = date,
            map = CSMap.fromFaceit(stats.string("Map") ?: ""),
            won = won,
            roundsWon = ownRounds,
            roundsLost = otherRounds,
            kills = kills,
            deaths = deaths,
            assists = stats.int("Assists") ?: 0,
            adr = stats.double("ADR") ?: 0.0,
            eloDelta = 0,
            details = GameMatch.Details(
                faceitMatchId = matchId,
                mapName = stats.string("Map"),
                headshotsPercent = stats.double("Headshots %"),
                mvps = stats.int("MVPs"),
                firstKills = stats.int("First Kills"),
                entryCount = stats.int("Entry Count") ?: 0,
                entryWins = stats.int("Entry Wins") ?: 0,
                clutchesWon = (stats.int("1v1Wins") ?: 0) + (stats.int("1v2Wins") ?: 0),
                multiKills = (stats.int("Double Kills") ?: 0) + (stats.int("Triple Kills") ?: 0) +
                    (stats.int("Quadro Kills") ?: 0) + (stats.int("Penta Kills") ?: 0),
                utilityDamage = stats.int("Utility Damage"),
                enemiesFlashed = stats.int("Enemies Flashed"),
                sniperKills = stats.int("Sniper Kills"),
                rounds = rounds
            )
        )
    }

    private fun uuidOrNull(raw: String): Uuid? = runCatching { Uuid.parse(raw.lowercase()) }.getOrNull()
}

/** "de_mirage" / "Mirage" → MIRAGE; unknown maps fall back to Mirage for the icon only (the name is kept in details). */
fun CSMap.Companion.fromFaceit(raw: String): CSMap {
    val key = raw.lowercase().replace("de_", "")
    return CSMap.entries.firstOrNull { key.contains(it.name.lowercase()) } ?: if (key.contains("dust")) CSMap.DUST2 else CSMap.MIRAGE
}
