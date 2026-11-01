package com.pulse.core.store

import com.pulse.core.model.CSMap
import com.pulse.core.model.DiaryScope
import com.pulse.core.model.Lineup
import com.pulse.core.model.LineupSide
import com.pulse.core.model.LineupType
import com.pulse.core.model.LineupVariation
import com.pulse.core.model.LineupVisibility
import com.pulse.core.model.ThrowTechnique
import com.pulse.core.model.User
import com.pulse.core.model.UserRole
import com.pulse.core.storage.JsonFileStorage
import com.pulse.core.util.addingDays
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** «База знаний» / «Дневник CS2»: lineups, scripts and notes («lineups.json»). */
class LineupStore(
    private val storage: JsonFileStorage<List<Lineup>>?,
    seed: List<Lineup> = demo(),
    private val teamStorage: JsonFileStorage<List<Lineup>>? = null,
    private val pendingStorage: JsonFileStorage<PendingTeamLineupChanges>? = null
) : PulseStore<List<Lineup>>(seed.sortedByDescending { it.createdAt }) {

    private var localEntries: List<Lineup> = seed.sortedByDescending { it.createdAt }
    private var teamEntries: List<Lineup> = emptyList()
    private var teamSyncEnabled = false
    var onTeamChange: (() -> Unit)? = null

    init {
        localEntries = storage?.load()?.let(::addingDemoVariations)?.sortedByDescending { it.createdAt } ?: localEntries
        teamEntries = teamStorage?.load().orEmpty().sortedByDescending { it.createdAt }
        publish()
    }

    val lineups get() = current

    fun reload(isDemo: Boolean, isTeamMember: Boolean = false) {
        teamSyncEnabled = !isDemo && isTeamMember
        localEntries = (storage?.load()?.let(::addingDemoVariations) ?: if (isDemo) demo() else emptyList()).sortedByDescending { it.createdAt }
        teamEntries = if (teamSyncEnabled) teamStorage?.load().orEmpty().sortedByDescending { it.createdAt } else emptyList()
        if (!teamSyncEnabled) pendingStorage?.clear()
        else localEntries.filter { it.visibility == LineupVisibility.TEAM && it.type != LineupType.NOTE }.forEach(::queueUpsert)
        publish()
    }

    fun filtered(map: CSMap?, type: LineupType?): List<Lineup> = current.filter { (map == null || it.map == map) && (type == null || it.type == type) }

    fun diary(user: User?, scope: DiaryScope = DiaryScope.ALL, map: CSMap? = null, type: LineupType? = null, query: String = ""): List<Lineup> {
        val text = query.trim().lowercase()
        return visible(current, user)
            .filter { entry ->
                when (scope) {
                    DiaryScope.ALL -> true
                    DiaryScope.MINE -> entry.author == user?.nickname
                    DiaryScope.TEAM -> entry.visibility == LineupVisibility.TEAM
                    DiaryScope.FAVORITES -> entry.isFavorite
                }
            }
            .filter { (map == null || it.map == map) && (type == null || it.type == type) }
            .filter { text.isEmpty() || it.title.lowercase().contains(text) || it.details.lowercase().contains(text) }
    }

    fun toggleFavorite(id: Uuid) {
        lineup(id)?.let { upsert(it.toggledFavorite()) }
    }

    fun lineup(id: Uuid): Lineup? = current.firstOrNull { it.id == id }

    fun upsert(lineup: Lineup) {
        if (teamSyncEnabled && (teamEntries.any { it.id == lineup.id } || (lineup.visibility == LineupVisibility.TEAM && lineup.type != LineupType.NOTE))) {
            val shared = lineup.copy(visibility = LineupVisibility.TEAM)
            teamEntries = (teamEntries.filterNot { it.id == shared.id } + shared).sortedByDescending { it.createdAt }
            teamStorage?.save(teamEntries)
            queueUpsert(shared)
            publish()
            onTeamChange?.invoke()
        } else {
            localEntries = (localEntries.filterNot { it.id == lineup.id } + lineup).sortedByDescending { it.createdAt }
            storage?.save(localEntries)
            publish()
        }
    }

    fun delete(id: Uuid) {
        if (teamSyncEnabled && teamEntries.any { it.id == id }) {
            teamEntries = teamEntries.filterNot { it.id == id }
            teamStorage?.save(teamEntries)
            localEntries = localEntries.filterNot { it.id == id }
            storage?.save(localEntries)
            queueDelete(id.toString())
            publish()
            onTeamChange?.invoke()
        } else {
            localEntries = localEntries.filterNot { it.id == id }
            storage?.save(localEntries)
            publish()
        }
    }

    fun resetToDemo() {
        localEntries = demo()
        storage?.save(localEntries)
        publish()
    }

    /** Receives canonical team entries while retaining changes queued offline on this device. */
    fun applyServerTeamLineups(entries: List<Lineup>) {
        if (!teamSyncEnabled) return
        val pending = pendingTeamChanges()
        val removed = pending.filterValues { it == null }.keys.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }.toSet()
        val remoteIds = entries.map { it.id }.toSet()
        val cleanedLocal = localEntries.filterNot { it.id in remoteIds || it.id in removed }
        if (cleanedLocal != localEntries) {
            localEntries = cleanedLocal
            storage?.save(localEntries)
        }
        val combined = (entries.filter { it.visibility == LineupVisibility.TEAM } + pending.values.filterNotNull()).associateBy { it.id }
        teamEntries = combined.values.filterNot { it.id in removed }.sortedByDescending { it.createdAt }
        teamStorage?.save(teamEntries)
        publish()
    }

    fun pendingTeamChanges(): Map<String, Lineup?> = pendingStorage?.load()?.let { it.upserts + it.deletes.associateWith { null } }.orEmpty()

    fun acknowledgeTeamChange(id: String, sent: Lineup?) {
        val pending = pendingStorage?.load() ?: return
        val next = if (sent == null && id in pending.deletes) pending.copy(deletes = pending.deletes - id)
            else if (sent != null && pending.upserts[id] == sent) pending.copy(upserts = pending.upserts - id)
            else pending
        if (next != pending) pendingStorage.save(next)
    }

    private fun queueUpsert(lineup: Lineup) {
        val storage = pendingStorage ?: return
        val pending = storage.load() ?: PendingTeamLineupChanges()
        storage.save(pending.copy(upserts = pending.upserts + (lineup.id.toString() to lineup), deletes = pending.deletes - lineup.id.toString()))
    }

    private fun queueDelete(id: String) {
        val storage = pendingStorage ?: return
        val pending = storage.load() ?: PendingTeamLineupChanges()
        storage.save(pending.copy(upserts = pending.upserts - id, deletes = pending.deletes + id))
    }

    private fun publish() {
        val teamIds = teamEntries.map { it.id }.toSet()
        current = (localEntries.filterNot { it.id in teamIds } + teamEntries).distinctBy { it.id }.sortedByDescending { it.createdAt }
    }

    companion object {
        const val FILE = "lineups.json"
        const val TEAM_FILE = "team-lineups.json"
        const val PENDING_TEAM_FILE = "team-lineups-pending.json"

        /** Privacy: staff see team entries only; team players see team + their own; solo players have a personal diary. */
        fun visible(items: List<Lineup>, user: User?): List<Lineup> {
            user ?: return emptyList()
            return when (user.role) {
                UserRole.STAFF -> items.filter { it.visibility == LineupVisibility.TEAM }
                UserRole.TEAM_PLAYER -> items.filter { it.visibility == LineupVisibility.TEAM || it.author == user.nickname }
                UserRole.SOLO_PLAYER -> items.filter { it.author == user.nickname }
            }
        }

        /** Staff edit everything; players edit only their own entries. */
        fun canEdit(lineup: Lineup, user: User?): Boolean = user != null && (user.role.isStaff || lineup.author == user.nickname)

        /** Diaries saved before variations existed get the demo variations for matching demo entries. */
        fun addingDemoVariations(items: List<Lineup>): List<Lineup> = items.map { entry ->
            val demo = demoVariations[entry.title]
            if (entry.variations.isEmpty() && demo != null) entry.copy(variations = demo) else entry
        }

        private data class Seed(
            val title: String, val map: CSMap, val side: LineupSide, val type: LineupType, val details: String,
            val author: String, val visibility: LineupVisibility, val hasVideo: Boolean, val favorite: Boolean
        )

        fun demo(now: Instant = Clock.System.now()): List<Lineup> = listOf(
            Seed("Смок в окно с T-спавна", CSMap.MIRAGE, LineupSide.T, LineupType.SMOKE, "Встать в угол у ящика, прицел на антенну, jump-throw.", "Coach", LineupVisibility.TEAM, true, true),
            Seed("Флешка на выход B-апартаментов", CSMap.MIRAGE, LineupSide.T, LineupType.FLASH, "Попфлеш из апартаментов, бросок левой кнопкой от стены.", "Nova", LineupVisibility.TEAM, false, false),
            Seed("Молотов в дарк на банане", CSMap.INFERNO, LineupSide.CT, LineupType.MOLOTOV, "С CT-спавна по прямой, бросок с прыжком.", "Coach", LineupVisibility.TEAM, true, false),
            Seed("Дефолт B-сайт Inferno", CSMap.INFERNO, LineupSide.T, LineupType.SCRIPT, "1. Смок CT + коффины\n2. Молотов в дарк\n3. Две флешки над стеной\n4. Заход через 3 секунды после флешек", "Coach", LineupVisibility.TEAM, false, false),
            Seed("Смок на outside", CSMap.NUKE, LineupSide.T, LineupType.SMOKE, "С улицы у гаража, прицел на верх трубы, обычный бросок.", "Blitz", LineupVisibility.TEAM, true, false),
            Seed("Ретейк A Ancient", CSMap.ANCIENT, LineupSide.CT, LineupType.SCRIPT, "Сбор на CT, смок в донат, флеш от Frost, заход парами.", "Coach", LineupVisibility.TEAM, false, false),
            Seed("Разбор: проигрыш на Train", CSMap.TRAIN, LineupSide.CT, LineupType.NOTE, "Плохо держал B после пистолетки. Цель: не пикать первым без флешки, больше инфы от тиммейтов.", "Nova", LineupVisibility.PERSONAL, false, true),
            Seed("Тренировка AWP", CSMap.MIRAGE, LineupSide.CT, LineupType.NOTE, "30 минут aim_botz перед матчем — стабильнее флики. Повторять ежедневно.", "Nova", LineupVisibility.PERSONAL, false, false),
            Seed("Мой смок в CT на Mirage", CSMap.MIRAGE, LineupSide.T, LineupType.SMOKE, "С T-рампы, прицел на край антенны, run-throw.", "Raven", LineupVisibility.PERSONAL, true, true),
            Seed("Заметка после рейтинговой", CSMap.ANUBIS, LineupSide.T, LineupType.NOTE, "Проиграл 3 дуэли подряд на мид — менять позицию после первого килла.", "Raven", LineupVisibility.PERSONAL, false, false),
            Seed("Флешка за угол на Inferno", CSMap.INFERNO, LineupSide.T, LineupType.FLASH, "Из библиотеки, прыжок + левая кнопка.", "Raven", LineupVisibility.PERSONAL, false, false)
        ).mapIndexed { index, item ->
            Lineup(
                id = Uuid.random(), title = item.title, map = item.map, side = item.side, type = item.type, details = item.details,
                videoURL = if (item.hasVideo) "https://www.youtube.com" else null, author = item.author,
                createdAt = now.addingDays(-index), visibility = item.visibility, isFavorite = item.favorite,
                variations = demoVariations[item.title] ?: emptyList()
            )
        }

        /** Demo: the same grenade from different spots / techniques. */
        private val demoVariations: Map<String, List<LineupVariation>> = mapOf(
            "Смок в окно с T-спавна" to listOf(
                LineupVariation(position = "T-спавн, угол у ящика", technique = ThrowTechnique.JUMP, aim = "Прицел на левый край антенны"),
                LineupVariation(position = "Верх T-рампы", technique = ThrowTechnique.STANDARD, aim = "Прицел в стык крыши и дерева"),
                LineupVariation(position = "Палас, у двери", technique = ThrowTechnique.RUN_JUMP, aim = "Центр прицела на кончик трубы", videoURL = "https://www.youtube.com")
            ),
            "Молотов в дарк на банане" to listOf(
                LineupVariation(position = "CT-спавн, у машины", technique = ThrowTechnique.JUMP, aim = "Прицел на верх второго окна"),
                LineupVariation(position = "Арка, ближний угол", technique = ThrowTechnique.STANDARD, aim = "Прицел в нижний край вывески")
            ),
            "Смок на outside" to listOf(
                LineupVariation(position = "Улица у гаража", technique = ThrowTechnique.STANDARD, aim = "Прицел на верх трубы"),
                LineupVariation(position = "Крыша, у лестницы", technique = ThrowTechnique.RUN, aim = "Прицел на левый угол антенны")
            ),
            "Мой смок в CT на Mirage" to listOf(
                LineupVariation(position = "T-рампа", technique = ThrowTechnique.RUN, aim = "Край антенны"),
                LineupVariation(position = "Верх рампы, у стены", technique = ThrowTechnique.JUMP, aim = "Нижний угол окна")
            )
        )
    }
}

@Serializable
data class PendingTeamLineupChanges(
    val upserts: Map<String, Lineup> = emptyMap(),
    val deletes: Set<String> = emptySet()
)
