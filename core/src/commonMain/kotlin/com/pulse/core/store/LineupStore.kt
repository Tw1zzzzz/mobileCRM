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
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** «База знаний» / «Дневник CS2»: lineups, scripts and notes («lineups.json»). */
class LineupStore(
    private val storage: JsonFileStorage<List<Lineup>>?,
    seed: List<Lineup> = demo()
) : PulseStore<List<Lineup>>(seed.sortedByDescending { it.createdAt }) {

    init {
        storage?.load()?.let { current = addingDemoVariations(it).sortedByDescending { l -> l.createdAt } }
    }

    val lineups get() = current

    fun reload(isDemo: Boolean) {
        current = (storage?.load()?.let(::addingDemoVariations) ?: if (isDemo) demo() else emptyList()).sortedByDescending { it.createdAt }
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
        current = (current.filter { it.id != lineup.id } + lineup).sortedByDescending { it.createdAt }
        storage?.save(current)
    }

    fun delete(id: Uuid) {
        current = current.filter { it.id != id }
        storage?.save(current)
    }

    fun resetToDemo() {
        current = demo()
        storage?.save(current)
    }

    companion object {
        const val FILE = "lineups.json"

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
