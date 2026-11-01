package com.pulse.core.staff

import com.pulse.core.feature.DaySeries
import com.pulse.core.feature.StaffMetrics
import com.pulse.core.feature.StaffMetrics.number
import com.pulse.core.feature.StaffMetrics.parseInstant
import com.pulse.core.feature.StaffMetrics.phoneDayOf
import com.pulse.core.feature.StaffMetrics.records
import com.pulse.core.feature.StaffSeries
import com.pulse.core.feature.StaffStatistics
import com.pulse.core.model.BalanceCategory
import com.pulse.core.model.CognitiveTestKind
import com.pulse.core.model.DailyValue
import com.pulse.core.model.FitnessMath
import com.pulse.core.model.GameMatch
import com.pulse.core.model.GameTestPhase
import com.pulse.core.model.LoadStatus
import com.pulse.core.model.MealSource
import com.pulse.core.model.MindsetProfile
import com.pulse.core.model.MindsetTrait
import com.pulse.core.model.TrainingLoadMath
import com.pulse.core.model.WeekRange
import com.pulse.core.model.WorkoutKind
import com.pulse.core.util.Formatters
import com.pulse.core.util.PulseJson
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Instant

/**
 * What the staff screens draw, from the downloaded team: one pure function per screen area, returning plain data.
 *
 * No formula lives here. Readiness, day rules and the overview come from [StaffMetrics], the per-day series from
 * [StaffSeries], correlations from [StaffStatistics], load from `TrainingLoadMath`, the archetype from `MindsetProfile`.
 * A number with no data is `null`, never a zero; a player without data is still listed. `today` is a `YYYY-MM-DD` day
 * in the zone `tz` (the phone's), the same one the days of the documents are read in.
 */
object StaffViews {
    /** «Требуют внимания»: readiness below the «Под вопросом» line, or sleep below the sleep «норма» line. */
    private val ATTENTION_READINESS = StaffMetrics.PILL_THRESHOLDS.getValue("readiness").mid
    private val ATTENTION_SLEEP_HOURS = StaffMetrics.PILL_THRESHOLDS.getValue("sleep").mid

    /** A player with nothing for this many days (today included) needs attention. */
    const val SILENT_DAYS = 3
    private const val HIGH_SCORE = 8.0
    private const val SHORT_NIGHT_HOURS = 7.0
    private const val WHEEL_DAYS = 7
    private const val WHEEL_DEFAULT = 5.0
    private const val WEEK_DAYS = 7
    private const val MINUTES_PER_HOUR = 60
    private const val MAX_WORKOUT_MINUTES = 1440.0
    private const val RPE_MIN = 1.0
    private const val RPE_MAX = 10.0
    private val GUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    // ------------------------------------------------------------ shared pieces

    /** A value with the same one for the period before it. */
    data class Kpi(val value: Double?, val previous: Double?, val delta: Double?)

    private fun documentsOf(team: TeamSnapshot): List<StaffMetrics.PlayerDocuments> =
        team.players.map { StaffMetrics.PlayerDocuments(it.id, team.documents[it.id].orEmpty()) }

    private fun r2(x: Double?): Double? = x?.let(StaffMetrics::round2)

    private fun compare(current: Double?, previous: Double?): Kpi =
        Kpi(r2(current), r2(previous), if (current != null && previous != null) StaffMetrics.round2(current - previous) else null)

    private fun meanOver(series: DaySeries, days: List<String>): Double? = StaffMetrics.mean(days.mapNotNull { series[it] })

    private fun statusOf(value: Double?): StaffMetrics.ReadinessStatus? = value?.let(StaffMetrics::readinessStatus)

    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun <T> decode(serializer: KSerializer<T>, value: JsonElement?): T? =
        try {
            value?.let { PulseJson.decodeFromJsonElement(serializer, it) }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Players with a reading first, the highest first; the roster order among equals and among those without one. */
    private fun <T> List<T>.byReadiness(readiness: (T) -> Double?): List<T> =
        sortedWith(compareBy<T> { readiness(it) == null }.thenByDescending { readiness(it) ?: 0.0 })

    // ------------------------------------------------------------ «Игроки»: the roster

    data class RosterRow(
        val id: String,
        val nickname: String,
        /** 0..100 today. */
        val readiness: Int?,
        val status: StaffMetrics.ReadinessStatus?,
        /** Last night's sleep (the sessions that end today). */
        val sleepHours: Double?,
        /** Today's mood, the mean of the day's entries. */
        val mood: Double?,
        /** Today's cognitive index, 0..100. */
        val cognitive: Double?,
        val lastActivity: Instant?,
    )

    /** Every player, best readiness first, those without a reading last. */
    fun roster(team: TeamSnapshot, today: String, tz: String): List<RosterRow> =
        team.players.map { p ->
            val inputs = StaffMetrics.dailyInputs(team.documents[p.id].orEmpty(), today, tz)
            val value = StaffMetrics.readiness(inputs)
            RosterRow(p.id, p.nickname, value, statusOf(value?.toDouble()), inputs.sleepHours, inputs.mood, inputs.cog, p.lastActivity)
        }.byReadiness { it.readiness?.toDouble() }

    // ------------------------------------------------------------ «Сегодня»

    enum class AttentionKind { LOW_READINESS, SHORT_SLEEP, NO_DATA }

    data class AttentionReason(val kind: AttentionKind, val text: String)

    data class AttentionItem(
        val playerId: String,
        val nickname: String,
        val readiness: Int?,
        val reasons: List<AttentionReason>,
    ) {
        /** «Готовность 40; Мало сна: 6 ч 0 м». */
        val text: String get() = reasons.joinToString("; ") { it.text }
    }

    data class TodayOverview(
        /** Mean of the known readings, whole number. */
        val teamReadiness: Int?,
        val teamStatus: StaffMetrics.ReadinessStatus?,
        val playersCount: Int,
        /** Lowest readiness first; players without a reading after them. */
        val attention: List<AttentionItem>,
        /** The whole-team overview of the twin: mood, sleep, energy and the week's metrics. */
        val overview: StaffMetrics.OverviewData,
    )

    fun todayOverview(team: TeamSnapshot, today: String, tz: String): TodayOverview {
        val overview = StaffMetrics.teamOverview(documentsOf(team), today, tz, "team")
        val readiness = overview.playerReadiness.associate { it.id to it.readiness }
        val attention = team.players.mapNotNull { p ->
            val index = StaffMetrics.indexDocuments(team.documents[p.id].orEmpty(), tz)
            val sleep = StaffMetrics.inputsFromIndex(index, today).sleepHours
            val reasons = attentionReasons(readiness[p.id], sleep, daysSilent(index, today))
            if (reasons.isEmpty()) null else AttentionItem(p.id, p.nickname, readiness[p.id], reasons)
        }.sortedWith(compareBy<AttentionItem> { it.readiness == null }.thenBy { it.readiness ?: 0 })
        return TodayOverview(overview.teamReadiness, overview.teamReadinessStatus, team.players.size, attention, overview)
    }

    private fun attentionReasons(readiness: Int?, sleepHours: Double?, daysSilent: Int?): List<AttentionReason> = buildList {
        if (readiness != null && readiness < ATTENTION_READINESS) add(AttentionReason(AttentionKind.LOW_READINESS, "Готовность $readiness"))
        if (sleepHours != null && sleepHours < ATTENTION_SLEEP_HOURS) {
            val minutes = StaffMetrics.roundHalfUp(sleepHours * MINUTES_PER_HOUR).toInt()
            add(AttentionReason(AttentionKind.SHORT_SLEEP, "Мало сна: ${Formatters.duration(minutes)}"))
        }
        if (daysSilent == null) add(AttentionReason(AttentionKind.NO_DATA, "Нет данных"))
        else if (daysSilent >= SILENT_DAYS) add(AttentionReason(AttentionKind.NO_DATA, "Нет данных $daysSilent дн."))
    }

    /** Days from the player's latest day with sleep, mood, energy, a test or a meal up to [today]; null when there is none. */
    private fun daysSilent(index: StaffMetrics.DayIndex, today: String): Int? {
        val last = listOf(index.sleepMs.keys, index.mood.keys, index.energy.keys, index.cog.keys, index.kcal.keys)
            .flatten().filter { it <= today }.maxOrNull() ?: return null
        return LocalDate.parse(last).daysUntil(LocalDate.parse(today))
    }

    // ------------------------------------------------------------ the player card

    /** Value of the period's days with data, and (second half − first half) of those days. */
    data class CardKpi(val value: Double?, val change: Double?)

    /** [days] days whose mean reaches 8, of [daysWithData] days that have one. */
    data class HighDays(val days: Int, val daysWithData: Int)

    data class CardKpis(val score: CardKpi, val state: CardKpi, val focusHigh: HighDays, val fatigueHigh: HighDays)

    data class TestRow(
        val at: Instant,
        val day: String,
        /** «Тест Струпа», «До тренировки». */
        val title: String,
        /** A pre/post-training test: no score, the player's own focus and fatigue instead. */
        val game: Boolean,
        val score: Double?,
        val focus: Double?,
        val fatigue: Double?,
        /** The readiness of that day. */
        val stateIndex: Int?,
    )

    data class TestsView(
        /** Every test of the period, newest first. */
        val rows: List<TestRow>,
        /** Day mean of the cognitive scores, and the day's readiness: the two lines of the chart. */
        val scoreByDay: DaySeries,
        val stateByDay: DaySeries,
    )

    data class SleepNight(val day: String, val start: Instant, val end: Instant, val hours: Double, val short: Boolean)

    data class SleepView(
        val averageHours: Double?,
        /** Nights shorter than 7 hours, of [nightsWithData]. */
        val shortNights: Int,
        val nightsWithData: Int,
        /** Newest first. */
        val rows: List<SleepNight>,
    )

    data class LoadView(val acute: Double, val chronicWeekly: Double, val ratio: Double?, val status: LoadStatus)

    data class FoodEntry(
        val playerId: String,
        val nickname: String,
        val mealId: String,
        val at: Instant,
        /** The staff-zone day. */
        val day: String,
        val name: String?,
        val kcal: Double?,
        val protein: Double?,
        val fat: Double?,
        val carbs: Double?,
        /** A photo is stored on the server for this meal (`StaffTeam.photo(playerId, mealId)`). */
        val hasPhoto: Boolean,
        val source: MealSource?,
    )

    data class FoodView(
        val total: Int,
        /** Mean of the daily kilocalorie sums of the days that have meals. */
        val averageKcalPerDay: Double?,
        /** Newest first. */
        val meals: List<FoodEntry>,
    )

    data class WheelValue(val category: BalanceCategory, val value: Double)

    /** One day of the balance wheel; [values] is null when the player filled nothing in that day. */
    data class WheelDay(val day: String, val values: List<WheelValue>?)

    data class Archetype(val profile: MindsetProfile, val date: Instant?)

    data class PlayerCard(
        val player: StaffPlayer,
        /** For the «Игра» section; null when the player has not linked Faceit. */
        val faceitPlayerId: String?,
        val period: StaffSeries.Period,
        val series: StaffSeries.PlayerSeries,
        val kpis: CardKpis,
        val tests: TestsView,
        val sleep: SleepView,
        val load: LoadView?,
        val food: FoodView,
        /** Today first, then the six days before it. */
        val wheel: List<WheelDay>,
        val archetype: Archetype?,
    )

    /** Null when [playerId] is not in the team. */
    fun playerCard(team: TeamSnapshot, playerId: String, today: String, tz: String, periodDays: Int): PlayerCard? {
        val player = team.players.firstOrNull { it.id == playerId } ?: return null
        val docs = team.documents[player.id].orEmpty()
        val period = StaffSeries.periodDays(today, periodDays)
        val series = StaffSeries.playerSeries(StaffMetrics.PlayerDocuments(player.id, docs), period.from, period.to, tz)
        return PlayerCard(
            player = player,
            faceitPlayerId = text((docs["faceit"] as? JsonObject)?.get("playerId"))?.takeIf { it.isNotBlank() },
            period = period,
            series = series,
            kpis = CardKpis(
                cardKpi(period.days, series.cog),
                cardKpi(period.days, series.readiness),
                highDays(period.days, series.focus),
                highDays(period.days, series.fatigue),
            ),
            tests = testsView(docs, period, series, tz),
            sleep = sleepView(docs, period, series, tz),
            load = loadOf(docs, today, tz),
            food = foodView(player, docs, period, tz),
            wheel = wheelDays(docs, today),
            archetype = archetypeOf(docs["player-survey"]),
        )
    }

    private fun cardKpi(days: List<String>, series: DaySeries): CardKpi {
        val values = days.mapNotNull { series[it] }
        val average = StaffMetrics.mean(values) ?: return CardKpi(null, null)
        val half = values.size / 2
        val before = StaffMetrics.mean(values.take(half))
        val after = StaffMetrics.mean(values.drop(half))
        return CardKpi(r2(average), if (before != null && after != null) r2(after - before) else null)
    }

    private fun highDays(days: List<String>, series: DaySeries): HighDays {
        val values = days.mapNotNull { series[it] }
        return HighDays(values.count { it >= HIGH_SCORE }, values.size)
    }

    private fun testsView(docs: Map<String, JsonElement>, period: StaffSeries.Period, series: StaffSeries.PlayerSeries, tz: String): TestsView {
        fun row(at: Instant, title: String, game: Boolean, score: Double?, focus: Double?, fatigue: Double?): TestRow {
            val day = StaffMetrics.dayOf(at, tz)
            return TestRow(at, day, title, game, score, focus, fatigue, series.readiness[day]?.toInt())
        }
        val rows = buildList {
            for (r in records(docs["cognitive"], "results")) {
                val at = parseInstant(r["date"]) ?: continue
                val title = decode(CognitiveTestKind.serializer(), r["kind"])?.title ?: continue
                val score = number(r["score"], 0.0, 100.0) ?: continue
                add(row(at, title, false, score, null, null))
            }
            for (g in records(docs["cognitive"], "gameResults")) {
                val at = parseInstant(g["date"]) ?: continue
                val title = decode(GameTestPhase.serializer(), g["phase"])?.title ?: continue
                add(row(at, title, true, null, number(g["focus"], 1.0, 10.0), number(g["fatigue"], 1.0, 10.0)))
            }
        }.filter { it.day in period.from..period.to }.sortedByDescending { it.at }
        return TestsView(rows, series.cog.filterKeys { it in period.days }, series.readiness.filterKeys { it in period.days })
    }

    private fun sleepView(docs: Map<String, JsonElement>, period: StaffSeries.Period, series: StaffSeries.PlayerSeries, tz: String): SleepView {
        val nights = HashMap<String, Pair<Instant, Instant>>() // the first start and the last end of the day's sessions
        for (s in records(docs["sleep"], "sessions")) {
            val start = parseInstant(s["start"]) ?: continue
            val end = parseInstant(s["end"]) ?: continue
            if (end.toEpochMilliseconds() - start.toEpochMilliseconds() < StaffMetrics.MIN_SLEEP_MS) continue
            nights.merge(StaffMetrics.dayOf(end, tz), start to end) { held, now -> minOf(held.first, now.first) to maxOf(held.second, now.second) }
        }
        val hours = period.days.mapNotNull { series.sleep[it] }
        val rows = period.days.asReversed().mapNotNull { day ->
            val h = series.sleep[day] ?: return@mapNotNull null
            nights[day]?.let { SleepNight(day, it.first, it.second, h, h < SHORT_NIGHT_HOURS) }
        }
        return SleepView(r2(StaffMetrics.mean(hours)), hours.count { it < SHORT_NIGHT_HOURS }, hours.size, rows)
    }

    // -- load

    private class LoggedWorkout(val id: String, val kind: WorkoutKind, val at: Instant, val minutes: Double, val heartRate: Int, val rpe: Int?)

    private fun workoutsOf(fitness: JsonElement?): List<LoggedWorkout> =
        records(fitness, "workouts").mapNotNull { w ->
            val id = text(w["id"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LoggedWorkout(
                id = id,
                kind = decode(WorkoutKind.serializer(), w["kind"]) ?: return@mapNotNull null,
                at = parseInstant(w["date"]) ?: return@mapNotNull null,
                minutes = number(w["minutes"], 0.0, MAX_WORKOUT_MINUTES) ?: return@mapNotNull null,
                heartRate = number(w["averageHeartRate"], 0.0, Double.MAX_VALUE)?.toInt() ?: 0,
                rpe = number(w["rpe"], RPE_MIN, RPE_MAX)?.toInt(),
            )
        }

    /** The ratings the player gave to Health workouts: a flat `[id, value, id, value, …]` array. */
    private fun ratingsOf(fitness: JsonElement?): Map<String, Int> =
        ((fitness as? JsonObject)?.get("ratings") as? JsonArray).orEmpty().chunked(2).mapNotNull { pair ->
            val id = text(pair[0]) ?: return@mapNotNull null
            val value = number(pair.getOrNull(1), RPE_MIN, RPE_MAX) ?: return@mapNotNull null
            id.lowercase() to value.toInt()
        }.toMap()

    /** Effort as the app counts it: the player's rating, else an estimate from pulse or type (body data is never shared: default age). */
    private fun effort(w: LoggedWorkout, ratings: Map<String, Int>): Int =
        w.rpe ?: ratings[w.id.lowercase()]
            ?: TrainingLoadMath.estimatedRPE(w.kind, w.heartRate, FitnessMath.maxHeartRate(FitnessMath.DEFAULT_AGE))

    /** Acute / chronic load of the 28 days ending [endDay]; null when no workout falls in them. */
    private fun loadOf(docs: Map<String, JsonElement>, endDay: String, tz: String): LoadView? {
        val fitness = docs["fitness-state"]
        val ratings = ratingsOf(fitness)
        val perDay = HashMap<String, Double>()
        for (w in workoutsOf(fitness)) perDay.merge(StaffMetrics.dayOf(w.at, tz), effort(w, ratings) * w.minutes, Double::plus)
        val first = StaffMetrics.shiftDay(endDay, -(TrainingLoadMath.CHRONIC_DAYS - 1))
        val zone = TimeZone.of(tz)
        val days = List(TrainingLoadMath.CHRONIC_DAYS) { StaffMetrics.shiftDay(first, it) }
        if (days.none { it in perDay }) return null
        val ratio = TrainingLoadMath.ratio(days.map { DailyValue(LocalDate.parse(it).atStartOfDayIn(zone), perDay[it] ?: 0.0) })
        return LoadView(ratio.acute, ratio.chronicWeekly, ratio.value, ratio.status)
    }

    // -- food, wheel, archetype

    private fun amount(value: JsonElement?): Double? = number(value)?.takeIf { it >= 0 }

    /** The player's meals on the days [from]..[to], newest first. A meal without an id or a readable time is skipped. */
    private fun mealsOf(player: StaffPlayer, docs: Map<String, JsonElement>, from: String, to: String, tz: String): List<FoodEntry> =
        records(docs["nutrition"], "meals").mapNotNull { m ->
            val at = parseInstant(m["date"]) ?: return@mapNotNull null
            val id = text(m["id"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val day = StaffMetrics.dayOf(at, tz)
            if (day < from || day > to) return@mapNotNull null
            FoodEntry(
                playerId = player.id,
                nickname = player.nickname,
                mealId = id,
                at = at,
                day = day,
                name = text(m["name"])?.trim()?.takeIf { it.isNotEmpty() },
                kcal = amount(m["kcal"]),
                protein = amount(m["protein"]),
                fat = amount(m["fat"]),
                carbs = amount(m["carbs"]),
                hasPhoto = (m["photoRef"] as? JsonPrimitive)?.booleanOrNull == true && GUID.matches(id),
                source = decode(MealSource.serializer(), m["source"]),
            )
        }.sortedByDescending { it.at }

    private fun foodView(player: StaffPlayer, docs: Map<String, JsonElement>, period: StaffSeries.Period, tz: String): FoodView {
        val meals = mealsOf(player, docs, period.from, period.to, tz)
        val dailySums = HashMap<String, Double>()
        for (m in meals) m.kcal?.let { dailySums.merge(m.day, it, Double::plus) }
        return FoodView(meals.size, r2(StaffMetrics.mean(dailySums.values.toList())), meals)
    }

    /** The balance wheel of the last 7 days; of several entries on a day the last one in the document wins. */
    private fun wheelDays(docs: Map<String, JsonElement>, today: String): List<WheelDay> {
        val byDay = HashMap<String, List<WheelValue>>()
        for (entry in records(docs["wellbeing"], "balance")) {
            val at = parseInstant(entry["day"]) ?: continue
            val values = entry["values"] as? JsonObject ?: continue
            byDay[phoneDayOf(at)] = BalanceCategory.entries.map { WheelValue(it, number(values[it.key], 0.0, 10.0) ?: WHEEL_DEFAULT) }
        }
        return List(WHEEL_DAYS) { StaffMetrics.shiftDay(today, -it).let { day -> WheelDay(day, byDay[day]) } }
    }

    /** The scores of a run when all six traits have one. */
    private fun finishedScores(scores: JsonElement?): Map<MindsetTrait, Int>? {
        val held = scores as? JsonObject ?: return null
        return MindsetTrait.entries.associateWith { trait ->
            StaffMetrics.roundHalfUp(number(held[trait.name.lowercase()]) ?: return null).toInt()
        }
    }

    /** The latest finished «Игровой характер» run (the later entry on the same instant; an unreadable date is older than any). */
    private fun archetypeOf(survey: JsonElement?): Archetype? {
        class Run(val date: Instant?, val scores: Map<MindsetTrait, Int>)
        val runs = records(survey, "mindset").mapNotNull { run -> finishedScores(run["scores"])?.let { Run(parseInstant(run["date"]), it) } }
        val latest = runs.withIndex().maxWithOrNull(compareBy({ it.value.date?.toEpochMilliseconds() ?: Long.MIN_VALUE }, { it.index }))
        return latest?.let { Archetype(MindsetProfile(it.value.scores), it.value.date) }
    }

    // ------------------------------------------------------------ «Аналитика»: the team

    data class TrendPoint(val day: String, val mean: Double?, val min: Double?, val max: Double?)

    data class PlayerBar(val id: String, val nickname: String, val readiness: Double?, val status: StaffMetrics.ReadinessStatus?)

    data class TableRow(
        val id: String,
        val nickname: String,
        /** Means of the period's days with data. */
        val readiness: Double?,
        val status: StaffMetrics.ReadinessStatus?,
        val score: Double?,
        val sleepHours: Double?,
        val mood: Double?,
        val lastActivity: Instant?,
    )

    data class TeamAnalyticsView(
        val period: StaffSeries.Period,
        /** Each is the mean of the team's daily means over the period, against the period before it. */
        val readiness: Kpi,
        val score: Kpi,
        val sleep: Kpi,
        val mood: Kpi,
        /** Best first, players without data last. */
        val playerReadiness: List<PlayerBar>,
        /** One point per day of the period: mean, lowest and highest readiness among the players. */
        val trend: List<TrendPoint>,
        /** In roster order; the screen sorts. */
        val table: List<TableRow>,
    )

    fun teamAnalytics(team: TeamSnapshot, today: String, tz: String, periodDays: Int): TeamAnalyticsView {
        val period = StaffSeries.periodDays(today, periodDays)
        val previous = StaffSeries.periodDays(StaffMetrics.shiftDay(period.from, -1), periodDays)
        val series = documentsOf(team).map { StaffSeries.playerSeries(it, previous.from, period.to, tz) }

        fun kpi(pick: (StaffSeries.PlayerSeries) -> DaySeries) = compare(
            teamMean(series, period.days, pick),
            teamMean(series, previous.days, pick),
        )

        val rows = team.players.zip(series).map { (p, s) ->
            val readiness = r2(meanOver(s.readiness, period.days))
            TableRow(p.id, p.nickname, readiness, statusOf(readiness), r2(meanOver(s.cog, period.days)),
                r2(meanOver(s.sleep, period.days)), r2(meanOver(s.mood, period.days)), p.lastActivity)
        }
        return TeamAnalyticsView(
            period = period,
            readiness = kpi { it.readiness },
            score = kpi { it.cog },
            sleep = kpi { it.sleep },
            mood = kpi { it.mood },
            playerReadiness = rows.map { PlayerBar(it.id, it.nickname, it.readiness, it.status) }.byReadiness { it.readiness },
            trend = period.days.map { day ->
                val values = series.mapNotNull { it.readiness[day] }
                TrendPoint(day, r2(StaffMetrics.mean(values)), values.minOrNull(), values.maxOrNull())
            },
            table = rows,
        )
    }

    /** Mean over the days of the players' mean that day; days nobody has a value for are left out. */
    private fun teamMean(series: List<StaffSeries.PlayerSeries>, days: List<String>, pick: (StaffSeries.PlayerSeries) -> DaySeries): Double? =
        StaffMetrics.mean(days.mapNotNull { day -> StaffMetrics.mean(series.mapNotNull { pick(it)[day] }) })

    // ------------------------------------------------------------ «Аналитика»: correlations

    /** [group] is `"state"` or `"game"`, as [StaffStatistics.LinkMetric] has it. */
    data class StatMetric(val id: String, val label: String, val group: String)

    /** The state metrics of the statistics page (labels from the board), rows of the matrix. */
    val STATE_FACTORS = listOf(
        StatMetric("mood", "Настроение", "state"),
        StatMetric("energy", "Энергия", "state"),
        StatMetric("sleep", "Сон", "state"),
        StatMetric("screen", "Экранное время", "state"),
        StatMetric("cog", "Индекс когн. формы", "state"),
        StatMetric("balance", "Баланс жизни", "state"),
        StatMetric("kcal", "Калорийность", "state"),
    )

    /** The game metrics, columns of the matrix; ELO comes from the players' snapshots, the rest from Faceit matches. */
    val GAME_METRICS = listOf(
        StatMetric("elo", "Текущий ELO", "game"),
        StatMetric("kd", "K/D", "game"),
        StatMetric("adr", "ADR", "game"),
        StatMetric("wr", "Win Rate", "game"),
    )

    /** [r] is not rounded; [days] is the number of player-days in the sample. */
    data class CorrelationCell(val r: Double?, val strength: StaffStatistics.Strength, val days: Int)

    data class CorrelationRow(val factor: StatMetric, val cells: List<CorrelationCell>)

    /** [a] is the state metric, [b] the game metric; [split] is the «выше обычного» example, null when too few days. */
    data class Link(
        val a: StatMetric,
        val b: StatMetric,
        val r: Double,
        val strength: StaffStatistics.Strength,
        val split: StaffStatistics.MedianSplit?,
    )

    data class TeamCorrelations(
        val period: StaffSeries.Period,
        val factors: List<StatMetric>,
        val games: List<StatMetric>,
        /** One row per factor, one cell per game metric. */
        val rows: List<CorrelationRow>,
        /** The strongest links, strongest first. */
        val links: List<Link>,
    )

    private class MatchDaily(val kd: DaySeries, val adr: DaySeries, val winRate: DaySeries)

    /** Per-day means over the matches of each day (K/D as the app's `GameMatch.kd`, win rate 100 / 0); a day without matches has no key. */
    private fun matchDaily(matches: List<GameMatch>, from: String, to: String, tz: String): MatchDaily {
        val byDay = matches.groupBy { StaffMetrics.dayOf(it.date, tz) }.filterKeys { it in from..to }
        fun series(value: (GameMatch) -> Double): DaySeries =
            byDay.mapNotNull { (day, list) -> StaffMetrics.mean(list.map(value))?.let { day to it } }.sortedBy { it.first }.toMap()
        return MatchDaily(series { it.kd }, series { it.adr }, series { if (it.won) 100.0 else 0.0 })
    }

    private class PlayerInputs(val series: StaffSeries.PlayerSeries, val games: MatchDaily) {
        fun of(id: String): DaySeries = when (id) {
            "mood" -> series.mood
            "energy" -> series.energy
            "sleep" -> series.sleep
            "screen" -> series.screen
            "cog" -> series.cog
            "balance" -> series.balance
            "kcal" -> series.kcal
            "elo" -> series.elo
            "kd" -> games.kd
            "adr" -> games.adr
            else -> games.winRate
        }
    }

    /**
     * State factors against the game metrics, every player's paired days pooled into one sample. [matches] are the
     * players' Faceit matches by player id (the caller loads them); a player without an entry has none. [only] narrows
     * the sample to one player.
     */
    fun teamCorrelations(
        team: TeamSnapshot,
        matches: Map<String, List<GameMatch>>,
        today: String,
        tz: String,
        periodDays: Int,
        only: String? = null,
    ): TeamCorrelations {
        val period = StaffSeries.periodDays(today, periodDays)
        val inputs = team.players.filter { only == null || it.id == only }.map { p ->
            val docs = StaffMetrics.PlayerDocuments(p.id, team.documents[p.id].orEmpty())
            PlayerInputs(StaffSeries.playerSeries(docs, period.from, period.to, tz), matchDaily(matches[p.id].orEmpty(), period.from, period.to, tz))
        }
        fun pairs(a: StatMetric, b: StatMetric) = inputs.map { it.of(a.id) to it.of(b.id) }

        val rows = STATE_FACTORS.map { factor ->
            CorrelationRow(factor, GAME_METRICS.map { game ->
                val pairs = pairs(factor, game)
                val r = StaffStatistics.correlatePooled(pairs)
                CorrelationCell(r, StaffStatistics.strength(r), pairs.sumOf { (x, y) -> StaffStatistics.pairByDay(x, y).xs.size })
            })
        }
        val matrix = rows.associate { row -> row.factor.id to GAME_METRICS.indices.associate { GAME_METRICS[it].id to row.cells[it].r } }
        val all = (STATE_FACTORS + GAME_METRICS).associateBy { it.id }
        val links = StaffStatistics.keyLinks(all.values.map { StaffStatistics.LinkMetric(it.id, it.group) }, matrix).mapNotNull { link ->
            val a = all[link.a] ?: return@mapNotNull null
            val b = all[link.b] ?: return@mapNotNull null
            val paired = pairs(a, b).map { (x, y) -> StaffStatistics.pairByDay(x, y) }
            Link(a, b, link.r, StaffStatistics.strength(link.r), StaffStatistics.medianSplit(paired.flatMap { it.xs }, paired.flatMap { it.ys }))
        }
        return TeamCorrelations(period, STATE_FACTORS, GAME_METRICS, rows, links)
    }

    // ------------------------------------------------------------ «Питание игроков»

    /** The meals of every player on the last [days] days up to [today], newest first. */
    fun teamFood(team: TeamSnapshot, today: String, tz: String, days: Int): List<FoodEntry> {
        val period = StaffSeries.periodDays(today, days)
        return team.players.flatMap { mealsOf(it, team.documents[it.id].orEmpty(), period.from, period.to, tz) }
            .sortedByDescending { it.at }
    }

    // ------------------------------------------------------------ «Итоги недели»

    data class WeeklyRow(
        val id: String,
        val nickname: String,
        /** Means of the week's days with data, and the same for the week before. */
        val readiness: Double?,
        val previousReadiness: Double?,
        val readinessDelta: Double?,
        val sleepHours: Double?,
        val previousSleepHours: Double?,
        val sleepDelta: Double?,
        val mood: Double?,
        /** «готовность 29», «сон 5 ч 30 м», «перегруз (ACWR 4,00)». */
        val risks: List<String>,
    )

    data class WeeklyTeam(
        /** The seven days of the week, oldest first. */
        val days: List<String>,
        /** Best readiness first, players without data last. */
        val rows: List<WeeklyRow>,
        /** Means over the players that have a value. */
        val readiness: Double?,
        val previousReadiness: Double?,
        val readinessDelta: Double?,
        val sleepHours: Double?,
        val mood: Double?,
        /** Players with at least one risk. */
        val atRisk: Int,
    )

    fun weeklyTeam(team: TeamSnapshot, week: WeekRange, tz: String): WeeklyTeam {
        val first = StaffMetrics.dayOf(week.start, tz)
        val days = List(WEEK_DAYS) { StaffMetrics.shiftDay(first, it) }
        val previousDays = List(WEEK_DAYS) { StaffMetrics.shiftDay(first, it - WEEK_DAYS) }
        val rows = team.players.map { weeklyRow(it, team.documents[it.id].orEmpty(), days, previousDays, tz) }.byReadiness { it.readiness }
        val readiness = compare(StaffMetrics.mean(rows.mapNotNull { it.readiness }), StaffMetrics.mean(rows.mapNotNull { it.previousReadiness }))
        return WeeklyTeam(
            days = days,
            rows = rows,
            readiness = readiness.value,
            previousReadiness = readiness.previous,
            readinessDelta = readiness.delta,
            sleepHours = r2(StaffMetrics.mean(rows.mapNotNull { it.sleepHours })),
            mood = r2(StaffMetrics.mean(rows.mapNotNull { it.mood })),
            atRisk = rows.count { it.risks.isNotEmpty() },
        )
    }

    private fun weeklyRow(player: StaffPlayer, docs: Map<String, JsonElement>, days: List<String>, previousDays: List<String>, tz: String): WeeklyRow {
        val series = StaffSeries.playerSeries(StaffMetrics.PlayerDocuments(player.id, docs), previousDays.first(), days.last(), tz)
        val readiness = compare(meanOver(series.readiness, days), meanOver(series.readiness, previousDays))
        val sleep = compare(meanOver(series.sleep, days), meanOver(series.sleep, previousDays))
        return WeeklyRow(
            id = player.id,
            nickname = player.nickname,
            readiness = readiness.value,
            previousReadiness = readiness.previous,
            readinessDelta = readiness.delta,
            sleepHours = sleep.value,
            previousSleepHours = sleep.previous,
            sleepDelta = sleep.delta,
            mood = r2(meanOver(series.mood, days)),
            risks = weeklyRisks(readiness.value, sleep.value, loadOf(docs, days.last(), tz)),
        )
    }

    private fun weeklyRisks(readiness: Double?, sleepHours: Double?, load: LoadView?): List<String> = buildList {
        if (readiness != null && readiness < ATTENTION_READINESS) add("готовность ${StaffMetrics.roundHalfUp(readiness).toInt()}")
        if (sleepHours != null && sleepHours < ATTENTION_SLEEP_HOURS) {
            add("сон ${Formatters.duration(StaffMetrics.roundHalfUp(sleepHours * MINUTES_PER_HOUR).toInt())}")
        }
        if (load?.status == LoadStatus.HIGH) add("перегруз (ACWR ${Formatters.decimal(load.ratio ?: 0.0, 2)})")
    }
}
