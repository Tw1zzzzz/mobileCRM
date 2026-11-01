package com.pulse.android.ui.staff

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.Callout
import com.pulse.android.ui.components.ChipButton
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.components.FractionLabels
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseLineChart
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.RadarChart
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.fit
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.game.Cell
import com.pulse.android.ui.game.CellGrid
import com.pulse.android.ui.game.PeriodCard
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.onboarding.MindsetAdviceCard
import com.pulse.android.ui.onboarding.MindsetProfileCard
import com.pulse.android.ui.state.MatchRow
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.color
import com.pulse.core.feature.DaySeries
import com.pulse.core.feature.StaffMetrics
import com.pulse.core.model.GameMatch
import com.pulse.core.model.GameSummary
import com.pulse.core.staff.StaffPlayer
import com.pulse.core.staff.StaffViews
import com.pulse.core.staff.TeamSnapshot
import com.pulse.core.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The lists of the card are cut to what the staff panel shows. */
private const val MAX_TESTS = 30
private const val MAX_NIGHTS = 10
private const val MAX_MEALS = 30
private const val RECENT_MATCHES = 4
private const val CHART_LABELS = 4

internal val TEST_TYPE_OPTIONS = listOf("Все тесты", "Когнитивные", "Игровые", "Настроение")

/** Faceit has the matches of this app only; pracks and tournaments are not in the app. */
internal val GAME_SOURCE_OPTIONS = listOf("Все", "Faceit")

/** The staff card of a player of a real team, or «Игрок не найден» for an id that is not in it (also a demo id from an earlier session). */
@Composable
fun RealPlayerDetailScreen(id: Uuid) {
    val core = observeCore()
    val router = LocalRouter.current
    val view = core.realTeamView()
    val loaded = view as? RealTeamView.Loaded
    val player = loaded?.team?.players?.firstOrNull { it.id.equals(id.toString(), ignoreCase = true) }
    LaunchedEffect(Unit) { StaffTeamRefresh.startIfOld(core) }
    val refresh = rememberTeamRefresh()
    ScreenScaffold(
        isRefreshing = refresh?.isRefreshing ?: false, onRefresh = refresh?.refresh,
        header = { NavHeader(player?.nickname ?: "Игрок", null, onBack = { router.pop() }) }
    ) {
        when {
            loaded != null && player != null -> {
                StaleNote(loaded)
                RealPlayerCard(loaded.team, player)
            }
            view == RealTeamView.Loading -> TeamLoadingState()
            view == RealTeamView.Failed -> TeamLoadFailedState()
            else -> EmptyStateView("person.crop.circle.badge.questionmark", "Игрок не найден", "")
        }
    }
}

/** What Faceit gave for the player's matches. */
private sealed interface FaceitMatches {
    data object Loading : FaceitMatches
    /** No Faceit link, no key, or Faceit did not answer. */
    data object NoData : FaceitMatches
    data class Loaded(val matches: List<GameMatch>) : FaceitMatches
}

/** The player's Faceit matches by their Faceit player id, loaded once per player (the period is only a filter on them). */
@Composable
private fun rememberFaceitMatches(ready: Boolean, faceitPlayerId: String?): FaceitMatches {
    val core = observeCore()
    val state by produceState<FaceitMatches>(FaceitMatches.Loading, ready, faceitPlayerId) {
        if (!ready) return@produceState
        if (faceitPlayerId == null) {
            value = FaceitMatches.NoData
            return@produceState
        }
        value = FaceitMatches.Loading
        value = withContext(Dispatchers.Default) { core.game.matchesOf(faceitPlayerId) }?.let(FaceitMatches::Loaded) ?: FaceitMatches.NoData
    }
    return state
}

@Composable
private fun RealPlayerCard(team: TeamSnapshot, player: StaffPlayer) {
    var period by remember { mutableIntStateOf(30) }
    var testType by remember { mutableStateOf(TEST_TYPE_OPTIONS.first()) }
    var source by remember { mutableStateOf(GAME_SOURCE_OPTIONS.first()) }
    val day = teamDay()
    val computed by produceState<StaffViews.PlayerCard?>(null, team, player.id, period, day.today) {
        value = withContext(Dispatchers.Default) { StaffViews.playerCard(team, player.id, day.today, day.tz, period) }
    }
    // A new period keeps the previous card on screen until its own is ready (the card names its own period).
    val card = computed
    val faceit = rememberFaceitMatches(card != null, card?.faceitPlayerId)
    val periodMatches = remember(faceit, card) {
        if (card == null || faceit !is FaceitMatches.Loaded) emptyList()
        else faceit.matches.filter { StaffMetrics.dayOf(it.date, day.tz) in card.period.from..card.period.to }
    }
    val correlations by produceState<List<StaffViews.Link>?>(null, team, player.id, period, day.today, faceit) {
        value = if (faceit == FaceitMatches.Loading) null else withContext(Dispatchers.Default) {
            val matches = (faceit as? FaceitMatches.Loaded)?.matches.orEmpty()
            StaffViews.teamCorrelations(team, mapOf(player.id to matches), day.today, day.tz, period, only = player.id).links
        }
    }

    ProfileRow(player)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(7, 30, 90).forEach { days -> ChipButton("$days дней", period == days) { period = days } }
        FilterMenu(testType, TEST_TYPE_OPTIONS) { testType = it }
        FilterMenu("Источник: $source", GAME_SOURCE_OPTIONS) { source = it }
    }
    if (card == null) {
        TeamLoadingState()
        return
    }
    Tiles(card.kpis)
    ProfileSections(card.archetype)
    PulseSection("Тренировочная нагрузка") {
        val load = card.load
        if (load == null) NoDataCard() else LoadCard(load.ratio, load.status, load.acute, load.chronicWeekly)
    }
    PulseSection("Результаты тестов") { TestsSection(card.tests, testType, day.tz) }
    PulseSection("Последние матчи") { RecentMatches(faceit, periodMatches) }
    PulseSection("Игровая статистика · ${card.period.days.size} дней") { GameSection(faceit, periodMatches) }
    PulseSection("Корреляционный анализ") { CorrelationsSection(correlations) }
    PulseSection("Колесо баланса") { WheelSection(card.wheel) }
    PulseSection("Дневник сна") { SleepSection(card.sleep) }
    PulseSection("Дневник питания") { FoodSection(card.food) }
}

@Composable
private fun ProfileRow(player: StaffPlayer) {
    Row(Modifier.pulseCard(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        PlayerAvatar(player.nickname, 68.dp, 24.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(player.nickname, color = Palette.textPrimary, autoSize = fit(22.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Последняя активность: ${player.lastActivity?.let(Formatters::dayMonthTime) ?: NO_DATA}", color = Palette.textTertiary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NoDataCard(text: String = NO_DATA) {
    Text(text, color = Palette.textTertiary, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().pulseCard(16.dp))
}

// ------------------------------------------------------------------------------------------------ KPIs, profile

@Composable
private fun Tiles(kpis: StaffViews.CardKpis) {
    fun high(days: StaffViews.HighDays) = if (days.daysWithData == 0) null else "${days.days} дн."
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryTile("Средний score", kpis.score.value?.roundToInt()?.toString(), Palette.cyan, Modifier.weight(1f))
            SummaryTile("Индекс состояния", kpis.state.value?.roundToInt()?.toString(), Palette.green, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryTile("Фокус high", high(kpis.focusHigh), Palette.purple, Modifier.weight(1f))
            SummaryTile("Усталость high", high(kpis.fatigueHigh), Palette.orange, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProfileSections(archetype: StaffViews.Archetype?) {
    PulseSection("Профиль игрока") {
        if (archetype == null) {
            Text("Игрок ещё не заполнил анкету.", color = Palette.textSecondary, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().pulseCard(16.dp))
        } else {
            val date = archetype.date?.let { " от ${Formatters.dayMonth(it)}" } ?: ""
            MindsetProfileCard(archetype.profile, "Тест «Игровой характер»$date · самооценка игрока")
        }
    }
    if (archetype != null) PulseSection("Как работать с игроком") { MindsetAdviceCard(archetype.profile, forCoach = true) }
}

// ------------------------------------------------------------------------------------------------ tests

/** «2026-11-01» in the phone's zone as the instant its day starts (for the chart's date labels). */
private fun dayStart(day: String, tz: String): Instant = LocalDate.parse(day).atStartOfDayIn(TimeZone.of(tz))

@Composable
private fun TestsSection(tests: StaffViews.TestsView, filter: String, tz: String) {
    TestsChart(tests.scoreByDay, tests.stateByDay, tz)
    val rows = when (filter) {
        "Когнитивные" -> tests.rows.filter { !it.game }
        "Игровые" -> tests.rows.filter { it.game }
        "Настроение" -> emptyList() // the card's tests are cognitive and before/after-training ones
        else -> tests.rows
    }.take(MAX_TESTS)
    if (rows.isEmpty()) {
        NoDataCard()
        return
    }
    Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(row.title, color = Palette.textPrimary, autoSize = fit(16.sp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val state = row.stateIndex?.let { " · состояние $it" } ?: ""
                    Text("${Formatters.dayMonthShort(row.at)}, ${Formatters.time(row.at)}$state", color = Palette.textSecondary, autoSize = fit(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (row.game) {
                    Text("Фокус ${row.focus?.roundToInt() ?: "—"} · Усталость ${row.fatigue?.roundToInt() ?: "—"}", color = Palette.purple, autoSize = fit(14.sp), fontWeight = FontWeight.SemiBold, maxLines = 1)
                } else {
                    Text(row.score?.roundToInt()?.toString() ?: "—", color = Palette.cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (index < rows.size - 1) DividerLine()
        }
    }
}

/** The tests' day score and the day's state index (both 0–100) on one chart; hold to read a day. */
@Composable
private fun TestsChart(scoreByDay: DaySeries, stateByDay: DaySeries, tz: String) {
    val days = scoreByDay.keys.sorted()
    if (days.size < 2) return
    val state = days.map { stateByDay[it] }
    val aligned = state.all { it != null }
    Column(Modifier.pulseCard(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend("Score", Palette.cyan)
            if (aligned) Legend("Индекс состояния", Palette.green)
        }
        PulseLineChart(
            days.map { scoreByDay.getValue(it) },
            calloutFor = { i ->
                Callout(Formatters.dayMonthShort(dayStart(days[i], tz)), listOfNotNull(
                    "Score ${scoreByDay.getValue(days[i]).roundToInt()}" to Palette.cyan,
                    state[i]?.let { "Индекс ${it.roundToInt()}" to Palette.green }
                ))
            },
            height = 160.dp, showsArea = false, lineWidth = 2.5.dp, lower = 0.0, upper = 100.0,
            secondary = if (aligned) state.map { it ?: 0.0 } else null, secondaryColor = Palette.green
        )
        val step = maxOf((days.size + CHART_LABELS - 1) / CHART_LABELS, 1)
        FractionLabels(days.indices.filter { it % step == 0 }.map { i -> i / (days.size - 1f) to Formatters.dayMonthShort(dayStart(days[i], tz)) })
    }
}

// ------------------------------------------------------------------------------------------------ game

@Composable
private fun FaceitProgress() {
    Row(Modifier.fillMaxWidth().pulseCard(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(22.dp), color = Palette.cyan, strokeWidth = 2.dp)
        Text("Загружаем матчи…", color = Palette.textSecondary, fontSize = 15.sp)
    }
}

@Composable
private fun RecentMatches(faceit: FaceitMatches, matches: List<GameMatch>) {
    when {
        faceit == FaceitMatches.Loading -> FaceitProgress()
        matches.isEmpty() -> NoDataCard()
        else -> {
            val shown = matches.take(RECENT_MATCHES)
            Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                shown.forEachIndexed { index, match ->
                    MatchRow(match)
                    if (index < shown.size - 1) DividerLine()
                }
            }
        }
    }
}

@Composable
private fun GameSection(faceit: FaceitMatches, matches: List<GameMatch>) {
    when {
        faceit == FaceitMatches.Loading -> FaceitProgress()
        matches.isEmpty() -> NoDataCard()
        else -> {
            val summary = GameSummary.of(matches)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.pulseCard(16.dp)) {
                    CellGrid(listOf(
                        Cell("Матчей", "${summary.matches}"),
                        Cell("Win-rate", "${summary.winRate.roundToInt()}%", tint = Palette.cyan),
                        Cell("K/D", Formatters.decimal(summary.kd, 2)),
                        Cell("ADR", "${summary.adr.roundToInt()}")
                    ))
                }
                PeriodCard(matches)
            }
        }
    }
}

@Composable
private fun CorrelationsSection(links: List<StaffViews.Link>?) {
    if (links == null) {
        FaceitProgress()
        return
    }
    if (links.isEmpty()) {
        NoDataCard()
        return
    }
    Column(Modifier.fillMaxWidth().pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
        links.forEachIndexed { index, link ->
            val tint = correlationTint(link.r)
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${link.a.label} → ${link.b.label}", color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    val direction = when (link.strength.direction) { "direct" -> " · прямая связь"; "inverse" -> " · обратная связь"; else -> "" }
                    Text("${link.strength.label}$direction", color = Palette.textSecondary, fontSize = 12.sp)
                }
                Text(
                    "${if (link.r >= 0) "+" else "−"}${Formatters.decimal(abs(link.r), 2)}", color = tint, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (index < links.size - 1) DividerLine()
        }
    }
}

private fun correlationTint(r: Double): Color = when {
    abs(r) >= 0.4 -> if (r > 0) Palette.green else Palette.red
    abs(r) >= 0.2 -> if (r > 0) Palette.cyan else Palette.orange
    else -> Palette.textSecondary
}

// ------------------------------------------------------------------------------------------------ wheel, sleep, food

@Composable
private fun WheelSection(wheel: List<StaffViews.WheelDay>) {
    val latest = wheel.firstOrNull { it.values != null }
    val values = latest?.values
    if (latest == null || values == null) {
        NoDataCard()
        return
    }
    Column(Modifier.pulseCard(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RadarChart(values.map { it.category.shortTitle }, values.map { it.value }, height = 290.dp)
        Text("Последняя запись: ${Formatters.dayMonth(dayStart(latest.day, TimeZone.currentSystemDefault().id))}", color = Palette.textTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun SleepSection(sleep: StaffViews.SleepView) {
    if (sleep.rows.isEmpty()) {
        NoDataCard()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sleep.averageHours?.let {
            Text(
                "В среднем ${Formatters.duration((it * MINUTES_PER_HOUR).roundToInt())} · короче 7 ч: ${sleep.shortNights} из ${sleep.nightsWithData}",
                color = Palette.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        Column(Modifier.pulseCard(padding = 4.dp)) {
            val rows = sleep.rows.take(MAX_NIGHTS)
            rows.forEachIndexed { index, night ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(Formatters.dayMonth(night.end), color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("${Formatters.time(night.start)} → ${Formatters.time(night.end)}", color = Palette.textSecondary, fontSize = 15.sp)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(Formatters.duration((night.hours * MINUTES_PER_HOUR).roundToInt()), color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (night.short) "Мало сна" else "Норма", color = if (night.short) Palette.orange else Palette.green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (index < rows.size - 1) DividerLine()
            }
        }
    }
}

@Composable
private fun FoodSection(food: StaffViews.FoodView) {
    if (food.meals.isEmpty()) {
        EmptyStateView("fork.knife", "Нет записей", "Игрок не добавлял еду за период")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        food.averageKcalPerDay?.let {
            Text("В среднем ${Formatters.grouped(it.roundToInt())} ккал в день · записей: ${food.total}", color = Palette.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
        val meals = food.meals.take(MAX_MEALS)
        Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
            meals.forEachIndexed { index, meal ->
                StaffMealRow(meal)
                if (index < meals.size - 1) DividerLine()
            }
        }
    }
}
