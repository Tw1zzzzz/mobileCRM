package com.pulse.core.demo

import com.pulse.core.model.AppCategory
import com.pulse.core.model.AppUsage
import com.pulse.core.model.BalanceCategory
import com.pulse.core.model.BalanceEntry
import com.pulse.core.model.BodyMetric
import com.pulse.core.model.CalendarEvent
import com.pulse.core.model.CognitiveResult
import com.pulse.core.model.CognitiveTestKind
import com.pulse.core.model.DailyValue
import com.pulse.core.model.DaySlot
import com.pulse.core.model.EventTag
import com.pulse.core.model.GameStats
import com.pulse.core.model.GameTestPhase
import com.pulse.core.model.GameTestResult
import com.pulse.core.model.HeartRateZone
import com.pulse.core.model.Meal
import com.pulse.core.model.MealSource
import com.pulse.core.model.MoodEntry
import com.pulse.core.model.PlayerProfile
import com.pulse.core.model.ScreenTimeDay
import com.pulse.core.model.SleepNight
import com.pulse.core.model.SleepSession
import com.pulse.core.model.SleepStage
import com.pulse.core.model.SleepStageSegment
import com.pulse.core.model.SleepWeekDay
import com.pulse.core.model.Workout
import com.pulse.core.model.WorkoutKind
import com.pulse.core.util.SeededGenerator
import com.pulse.core.util.Tint
import com.pulse.core.util.addingDays
import com.pulse.core.util.addingMinutes
import com.pulse.core.util.setting
import com.pulse.core.util.startOfDay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Deterministic demo content — a line-by-line port of iOS `DemoData` (same seeds, same order of random calls),
 * so the Android demo shows exactly the same numbers as the iPhone.
 */
object DemoData {
    private fun now() = Clock.System.now()

    // Sleep

    fun lastNight(now: Instant = now()): SleepNight {
        val start = now.startOfDay().addingDays(-1).setting(23, 12)
        val plan = listOf(
            SleepStage.AWAKE to 8, SleepStage.LIGHT to 30, SleepStage.DEEP to 57, SleepStage.LIGHT to 10, SleepStage.REM to 20,
            SleepStage.LIGHT to 15, SleepStage.DEEP to 46, SleepStage.LIGHT to 4, SleepStage.REM to 48, SleepStage.LIGHT to 32,
            SleepStage.AWAKE to 2, SleepStage.LIGHT to 26, SleepStage.REM to 40, SleepStage.LIGHT to 40, SleepStage.REM to 14,
            SleepStage.LIGHT to 56, SleepStage.AWAKE to 14
        )
        var cursor = start
        val segments = plan.map { (stage, minutes) ->
            SleepStageSegment(stage, cursor, cursor.addingMinutes(minutes)).also { cursor = it.end }
        }
        return SleepNight(
            start = start, end = cursor, score = 84, baselineDelta = 2, segments = segments, cycles = 5, efficiency = 92,
            latencyMinutes = 14, awakenings = 2,
            heartRate = listOf(62, 59, 56, 55, 53, 52, 54, 56, 57, 58, 59, 60, 58, 57, 56, 55, 54, 55, 57, 58),
            restingHeartRate = 56, minHeartRate = 51, heartRateBaselineDelta = -3, respiratoryRate = 14, hrv = 58, spo2 = 97
        )
    }

    fun sleepHistory(now: Instant = now()): List<SleepSession> {
        val rng = SeededGenerator(42uL)
        val night = lastNight(now)
        val previous = (1 until 30).map { offset ->
            val bed = now.startOfDay().addingDays(-offset - 1).setting(22, 40).addingMinutes(rng.int(0..70))
            SleepSession(Uuid.random(), bed, bed.addingMinutes(rng.int(370..500)))
        }
        return listOf(SleepSession(Uuid.random(), night.start, night.end)) + previous
    }

    /** The first demo player shares the device's demo history, so staff see the same nights as Nova. */
    fun sleepSessions(player: PlayerProfile, now: Instant = now()): List<SleepSession> {
        if (player.id == players[0].id) return sleepHistory(now)
        val rng = SeededGenerator(player.seed + 11uL)
        return (0 until 30).map { offset ->
            val bed = now.startOfDay().addingDays(-offset - 1).setting(22, 30).addingMinutes(rng.int(0..100))
            SleepSession(Uuid.random(), bed, bed.addingMinutes(rng.int(330..510)))
        }
    }

    private data class Dish(val name: String, val kcal: IntRange, val protein: Int, val fat: Int, val carbs: Int)

    /** Demo meals of a team player (no photos — photos come from players using the app). */
    fun playerMeals(player: PlayerProfile, now: Instant = now()): List<Meal> {
        val rng = SeededGenerator(player.seed + 13uL)
        val lunches = listOf(
            Dish("Курица с рисом", 480..620, 42, 12, 60), Dish("Паста болоньезе", 550..700, 30, 20, 75),
            Dish("Боул с лососем", 520..650, 34, 20, 58), Dish("Суп и сэндвич", 450..580, 24, 16, 55)
        )
        val dinners = listOf(
            Dish("Стейк с овощами", 520..680, 45, 28, 20), Dish("Салат Цезарь", 380..480, 26, 24, 18),
            Dish("Рыба с картофелем", 450..600, 36, 14, 48), Dish("Омлет с тостом", 350..450, 24, 20, 28)
        )
        return (0 until 10).map { index ->
            val isLunch = index % 2 == 0
            val menu = if (isLunch) lunches else dinners
            val dish = menu[rng.int(0..menu.size - 1)]
            val date = now.startOfDay().addingDays(-(index / 2)).setting(if (isLunch) 13 else 19, rng.int(0..50))
            Meal(Uuid.random(), date, dish.name, rng.int(dish.kcal), dish.protein, dish.fat, dish.carbs,
                photoBase64 = null, source = MealSource.MANUAL, author = player.nickname)
        }.filter { it.date <= now }
    }

    fun sleepWeek(now: Instant = now()): List<SleepWeekDay> = listOf(
        Triple(82, 70, 268), Triple(95, 100, 298), Triple(90, 92, 255), Triple(60, 75, 250),
        Triple(78, 95, 262), Triple(60, 80, 266), Triple(103, 122, 237)
    ).mapIndexed { index, (deep, rem, light) -> SleepWeekDay(now.startOfDay().addingDays(index - 6), deep, rem, light) }

    // Fitness

    val seededWorkoutIDs: Set<Uuid> = setOf(
        Uuid.parse("11111111-1111-1111-1111-111111111111"),
        Uuid.parse("22222222-2222-2222-2222-222222222222")
    )

    fun workouts(now: Instant = now()): List<Workout> {
        val ids = seededWorkoutIDs.sortedBy { it.toString() }
        return listOf(
            Workout(ids[0], WorkoutKind.STRENGTH, "верх", now.setting(7, 20), 52, 280, 132),
            Workout(ids[1], WorkoutKind.HIIT, "табата", now.setting(12, 40), 18, 132, 156)
        )
    }

    /** Device activity outside logged workouts while Health Connect is not connected. */
    const val BASELINE_ACTIVE_MINUTES = 48
    const val BASELINE_ACTIVE_KCAL = 412
    const val BASELINE_STEPS = 6840
    const val BASELINE_DISTANCE_KM = 4.2
    const val AVERAGE_HEART_RATE = 118

    /** Four weeks of rated workouts so «Нагрузка» has a chronic base before real data arrives. */
    fun loadHistory(now: Instant = now()): List<Workout> {
        val rng = SeededGenerator(2026uL)
        val kinds = listOf(WorkoutKind.STRENGTH, WorkoutKind.CARDIO, WorkoutKind.HIIT, WorkoutKind.STRENGTH, WorkoutKind.YOGA)
        return (1 until 28).mapNotNull { offset ->
            if (rng.int(0..9) >= 6) return@mapNotNull null
            val kind = kinds[rng.int(0..kinds.size - 1)]
            // The last week is a bit heavier, so the demo shows a rising load.
            val extra = if (offset < 7) 10 else 0
            Workout(
                Uuid.random(), kind, "", now.startOfDay().addingDays(-offset).setting(18, 0),
                minutes = rng.int(30..60) + extra, kcal = rng.int(180..420),
                averageHeartRate = rng.int(115..160), rpe = rng.int(4..8)
            )
        }
    }

    fun weeklyActiveMinutes(now: Instant = now()): List<DailyValue> = listOf(42, 64, 38, 51, 46, 60, 48)
        .mapIndexed { index, value -> DailyValue(now.startOfDay().addingDays(index - 6), value.toDouble()) }

    val heartRateZones = listOf(
        HeartRateZone("Покой", "< 60%", 35, Tint.REST_ZONE),
        HeartRateZone("Жиросжигание", "60–70%", 25, Tint.CYAN),
        HeartRateZone("Кардио", "70–80%", 22, Tint.GREEN),
        HeartRateZone("Пик", "80–90%", 14, Tint.ORANGE),
        HeartRateZone("Максимум", "> 90%", 4, Tint.RED)
    )

    val bodyMetrics = listOf(
        BodyMetric("Вес", 74.2, "кг", -0.3),
        BodyMetric("Жир", 16.4, "%", -0.2),
        BodyMetric("Мышцы", 34.8, "кг", 0.1)
    )

    // Screen time

    val screenTimeHourly = listOf(7, 6, 4, 3, 4, 5, 12, 18, 26, 18, 13, 18, 24, 27, 28, 42, 56, 27, 52, 54, 40, 24, 14, 10)

    val screenTimeApps = listOf(
        AppUsage("YouTube", AppCategory.VIDEO, 78, "play.rectangle.fill", Tint.RED),
        AppUsage("Telegram", AppCategory.SOCIAL, 64, "paperplane.fill", Tint.CYAN),
        AppUsage("Instagram", AppCategory.SOCIAL, 42, "camera", Tint.PINK),
        AppUsage("Safari", AppCategory.BROWSER, 38, "safari", Tint.BLUE),
        AppUsage("Pulse", AppCategory.HEALTH, 16, "waveform.path.ecg", Tint.GREEN),
        AppUsage("Slack", AppCategory.WORK, 24, "number", Tint.PURPLE)
    )

    fun screenTimeWeek(now: Instant = now()): List<ScreenTimeDay> =
        listOf(252, 321, 278, 245, 296, 251, 263).mapIndexed { index, value ->
            ScreenTimeDay(
                now.startOfDay().addingDays(index - 6), value,
                mapOf(
                    AppCategory.VIDEO to value * 30 / 100, AppCategory.SOCIAL to value * 40 / 100,
                    AppCategory.BROWSER to value * 14 / 100, AppCategory.WORK to value * 9 / 100,
                    AppCategory.OTHER to value * 7 / 100
                )
            )
        }

    fun screenTimeSeries(days: Int, now: Instant = now()): List<DailyValue> = (days - 1 downTo 0).map { offset ->
        val day = now.startOfDay().addingDays(-offset)
        val rng = SeededGenerator(abs((day.toEpochMilliseconds() / 1000.0 / 86_400).toLong()).toULong() + 7uL)
        DailyValue(day, rng.int(190..340).toDouble())
    }

    // Nutrition

    fun mealHistory(now: Instant = now()): List<Meal> {
        val rng = SeededGenerator(7uL)
        data class Item(val name: String, val hour: Int, val kcal: Int, val protein: Int, val fat: Int, val carbs: Int)
        val menu = listOf(
            Item("Овсянка с бананом", 8, 380, 12, 9, 62),
            Item("Курица с гречкой", 13, 610, 48, 16, 64),
            Item("Творог с ягодами", 16, 260, 24, 6, 22),
            Item("Лосось с рисом", 19, 680, 38, 24, 70)
        )
        return (1..21).flatMap { offset ->
            val day = now.startOfDay().addingDays(-offset)
            menu.mapNotNull { item ->
                if (rng.int(0..9) <= 1) return@mapNotNull null
                val factor = rng.double(0.85..1.2)
                Meal(Uuid.random(), day.setting(item.hour, rng.int(0..50)), item.name, (item.kcal * factor).toInt(),
                    item.protein, item.fat, item.carbs, photoBase64 = null, source = MealSource.PHOTO)
            }
        }
    }

    // Mood, balance, cognitive

    fun moodHistory(now: Instant = now()): List<MoodEntry> {
        val rng = SeededGenerator(11uL)
        return (1..45).flatMap { offset ->
            val day = now.startOfDay().addingDays(-offset)
            val base = rng.int(5..8)
            DaySlot.entries.mapNotNull { slot ->
                if (rng.int(0..9) <= 2) return@mapNotNull null
                val mood = min(10, max(1, base + rng.int(-1..2)))
                val energy = min(10, max(1, base + rng.int(-2..1)))
                MoodEntry(Uuid.random(), day, slot, mood, energy)
            }
        } + MoodEntry(Uuid.random(), now.startOfDay(), DaySlot.MORNING, 7, 6)
    }

    fun balanceHistory(now: Instant = now()): List<BalanceEntry> {
        val rng = SeededGenerator(23uL)
        return (1..10).map { week ->
            val values = BalanceCategory.entries.associate { it.key to rng.int(4..9).toDouble() }
            BalanceEntry(Uuid.random(), now.startOfDay().addingDays(-week * 7 + 3), values)
        }
    }

    fun cognitiveHistory(now: Instant = now()): List<CognitiveResult> {
        val rng = SeededGenerator(31uL)
        return (1..30).flatMap { offset ->
            val date = now.startOfDay().addingDays(-offset).setting(10, 15)
            if (rng.int(0..9) <= 1) return@flatMap emptyList()
            CognitiveTestKind.entries.map { kind ->
                val score = rng.int(55..92)
                CognitiveResult(Uuid.random(), kind, date, score, demoMetric(kind, score))
            }
        }
    }

    private fun demoMetric(kind: CognitiveTestKind, score: Int): String = when (kind) {
        CognitiveTestKind.REACTION -> "${500 - score * 3} мс"
        CognitiveTestKind.GO_NO_GO, CognitiveTestKind.STROOP -> "$score% точность"
        CognitiveTestKind.MEMORY -> "${3 + score * 6 / 100} цифр"
        CognitiveTestKind.AIM -> "${900 - score * 5} мс"
    }

    fun gameTestHistory(now: Instant = now()): List<GameTestResult> {
        val rng = SeededGenerator(5uL)
        return (0 until 6).flatMap { offset ->
            val day = now.startOfDay().addingDays(-offset)
            listOf(
                GameTestResult(Uuid.random(), day.setting(11, 50), GameTestPhase.BEFORE_TRAINING,
                    focus = rng.int(6..9), fatigue = rng.int(2..4), readiness = rng.int(6..9)),
                GameTestResult(Uuid.random(), day.setting(14, 10), GameTestPhase.AFTER_TRAINING,
                    focus = rng.int(4..8), fatigue = rng.int(4..8), readiness = rng.int(4..7))
            )
        }
    }

    // Calendar

    fun calendarEvents(now: Instant = now()): List<CalendarEvent> {
        val today = now.startOfDay()
        fun event(title: String, details: String, day: Int, h: Int, m: Int, minutes: Int, tag: EventTag, links: List<String> = emptyList()): CalendarEvent {
            val start = today.addingDays(day).setting(h, m)
            return CalendarEvent(Uuid.random(), title, details, start, start.addingMinutes(minutes), links, tag, null)
        }
        return listOf(
            event("Тренировка", "Командная практика: дефолты на Mirage", 0, 12, 0, 120, EventTag.Team, listOf("https://faceit.com")),
            event("Разбор игры", "Демо вчерашнего матча, раунды 12–18", 0, 18, 0, 60, EventTag.Team),
            event("Пробежка", "Лёгкий темп, 5 км", 0, 8, 0, 40, EventTag.Personal),
            event("Матч ESL", "Групповой этап, BO3", 1, 19, 0, 180, EventTag.Team, listOf("https://www.twitch.tv")),
            event("Индивидуальная AWP-практика", "Aim botz, 45 минут", 1, 10, 0, 60, EventTag.Player("Nova")),
            event("Психолог", "Сессия по стресс-менеджменту", 2, 15, 0, 50, EventTag.Personal),
            event("Буткемп", "Выезд команды", 4, 9, 0, 600, EventTag.Team),
            event("Стрим", "Благотворительный стрим", -1, 20, 0, 120, EventTag.Player("Blitz"))
        )
    }

    // Players (staff)

    val players: List<PlayerProfile> by lazy {
        listOf(
            makePlayer("Nova", "Алексей Морозов", "AWPer", 101uL, 2840, 1),
            makePlayer("Blitz", "Иван Котов", "Entry", 202uL, 2710, 3),
            makePlayer("Echo", "Дмитрий Лебедев", "IGL", 303uL, 2590, 20, 40..68),
            makePlayer("Frost", "Максим Орлов", "Support", 404uL, 2655, 6),
            makePlayer("Ghost", "Никита Волков", "Lurker", 505uL, 2760, 48)
        )
    }

    private fun makePlayer(
        nick: String, name: String, role: String, seed: ULong, elo: Int, hoursAgo: Int, readiness: IntRange = 50..85
    ): PlayerProfile {
        val rng = SeededGenerator(seed)
        val matches = rng.int(38..60)
        val wins = rng.int(matches / 3..matches * 2 / 3)
        val draws = rng.int(0..3)
        val rounds = matches * rng.int(21..25)
        val pistol = matches * 2
        val stats = GameStats(
            kills = rng.int(650..980), deaths = rng.int(600..820), assists = rng.int(150..300),
            adr = rng.double(68.0..92.0), kpr = rng.double(0.62..0.86), deathsPerRound = rng.double(0.58..0.72),
            avgKR = rng.double(0.65..0.85), avgKD = rng.double(0.95..1.35), kast = rng.double(66.0..78.0),
            firstKills = rng.int(60..140), firstDeaths = rng.int(50..120), udr = rng.double(4.0..12.0),
            openDuelDiff = rng.int(-15..30), avgMultikills = rng.double(1.2..2.4), clutchesWon = rng.int(6..24),
            avgFlashTime = rng.double(2.1..4.2), matches = matches, wins = wins, losses = matches - wins - draws, draws = draws,
            rounds = rounds, roundsWon = rounds / 2 + rng.int(-40..60), pistolRounds = pistol,
            pistolWon = rng.int(pistol / 3..pistol * 2 / 3)
        )
        return PlayerProfile(
            Uuid.random(), nick, name, role, Clock.System.now() - hoursAgo.hours, stats, elo, seed, readiness
        )
    }

    fun playerSeries(player: PlayerProfile, days: Int, range: IntRange, salt: ULong, now: Instant = now()): List<DailyValue> {
        val rng = SeededGenerator(player.seed + salt)
        return (days - 1 downTo 0).map { offset -> DailyValue(now.startOfDay().addingDays(-offset), rng.int(range).toDouble()) }
    }
}
