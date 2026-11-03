package com.pulse.android.ui.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.Callout
import com.pulse.android.ui.components.ChipButton
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.FractionLabels
import com.pulse.android.ui.components.PulseLineChart
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.ScatterChart
import com.pulse.android.ui.components.ScatterDot
import com.pulse.android.ui.components.fit
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.android.ui.today.WeeklySummaryCard
import com.pulse.core.feature.StaffMetrics
import com.pulse.core.feature.StaffSeries
import com.pulse.core.model.GameMatch
import com.pulse.core.model.GameSummary
import com.pulse.core.staff.StaffViews
import com.pulse.core.staff.TeamSnapshot
import com.pulse.core.util.Formatters
import com.pulse.core.util.roundToIntSwift
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.math.roundToInt

private const val READINESS_MAX = 100.0
private const val ELO_LOOKBACK_DAYS = 365
private const val LINKS_SHOWN = 5
private const val CORRELATION_DAYS = 30
private const val CORRELATION_LABEL_LINES = 2

/** The loaded team with players for a team screen of a real account; progress, the failure or «Пока нет данных» in place of it. */
@Composable
fun RealTeamScreenContent(content: @Composable (RealTeamView.Loaded) -> Unit) {
    when (val view = observeCore().realTeamView()) {
        is RealTeamView.Loaded -> if (view.team.players.isEmpty()) TeamNoDataState() else {
            StaleNote(view)
            content(view)
        }
        RealTeamView.Loading -> TeamLoadingState()
        RealTeamView.Failed -> TeamLoadFailedState()
        RealTeamView.NoAccess -> TeamNoAccessState()
        RealTeamView.NoServerTeam -> TeamNoDataState()
    }
}

private enum class TableSort(val title: String) { READINESS("Готовность"), SCORE("Score"), SLEEP("Сон"), ELO("ELO") }

/** The current ELO of a player: the newest `faceit.snapshots[]` entry of their documents. */
private fun currentElo(team: TeamSnapshot, today: String, tz: String): Map<String, Double> = buildMap {
    val from = StaffMetrics.shiftDay(today, -ELO_LOOKBACK_DAYS)
    for (player in team.players) {
        val series = StaffSeries.playerSeries(StaffMetrics.PlayerDocuments(player.id, team.documents[player.id].orEmpty()), from, today, tz)
        series.elo.maxByOrNull { it.key }?.let { put(player.id, it.value) }
    }
}

private class EloDot(val nickname: String, val kd: Double, val elo: Double)

/** Everything the dashboard draws, worked out together off the main thread. */
private class TeamDashboard(val view: StaffViews.TeamAnalyticsView, val elo: Map<String, Double>, val dots: List<EloDot>)

private fun dashboard(team: TeamSnapshot, today: String, tz: String, period: Int, matches: Map<String, List<GameMatch>>): TeamDashboard {
    val view = StaffViews.teamAnalytics(team, today, tz, period, matches)
    val elo = currentElo(team, today, tz)
    val dots = team.players.mapNotNull { player ->
        val played = matches[player.id].orEmpty().filter { StaffMetrics.dayOf(it.date, tz) in view.period.from..view.period.to }
        val current = elo[player.id]
        if (played.isEmpty() || current == null) null else EloDot(player.nickname, GameSummary.of(played).kd, current)
    }
    return TeamDashboard(view, elo, dots)
}

// ------------------------------------------------------------------------------------------------ «Команда»

/** Team dashboard of a real account: KPIs, readiness per player, dynamics, ELO vs K/D and a sortable table. */
@Composable
fun RealTeamAnalytics(matches: TeamMatches) = RealTeamScreenContent { RealDashboard(it.team, matches) }

@Composable
private fun RealDashboard(team: TeamSnapshot, matches: TeamMatches) {
    var period by remember { mutableIntStateOf(7) }
    var sort by remember { mutableStateOf(TableSort.READINESS) }
    val day = teamDay()
    val played by rememberTeamMatchesState(matches, team)
    val computed by produceState<TeamDashboard?>(null, team, period, day.today, played) {
        value = withContext(Dispatchers.Default) { dashboard(team, day.today, day.tz, period, played?.matches.orEmpty()) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        WeeklySummaryCard()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30).forEach { days -> ChipButton("$days дней", period == days) { period = days } }
        }
        val board = computed
        if (board == null) {
            TeamLoadingState()
            return@Column
        }
        val failed = played?.failed?.size ?: 0
        Kpis(board.view, played != null, failed)
        PulseSection("Готовность по игрокам") { ReadinessBars(board.view.playerReadiness) }
        PulseSection("Динамика готовности") { TrendCard(board.view.trend) }
        PulseSection("ELO и K/D") { EloChart(board.dots, board.view.period.days.size, failed) }
        PulseSection("Таблица") { PlayersTable(board, sort) { sort = it } }
    }
}

@Composable
private fun Kpis(view: StaffViews.TeamAnalyticsView, gamesKnown: Boolean, faceitFailed: Int) {
    val winRate = view.winRate.value
    val winRateText = when {
        !gamesKnown -> "…"
        winRate == null -> null
        else -> "${winRate.roundToIntSwift()}% · ${Formatters.plural(view.matches, "игра", "игры", "игр")}"
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Kpi("Готовность команды", view.readiness.value?.let { "${it.roundToInt()} / 100" }, view.readiness.delta, { Formatters.signed(it.roundToInt()) }, Palette.green, Modifier.weight(1f))
            Kpi("Средний score", view.score.value?.let { "${it.roundToInt()}" }, view.score.delta, { Formatters.signed(it.roundToInt()) }, Palette.cyan, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Kpi("Средний сон", view.sleep.value?.let { Formatters.duration((it * MINUTES_PER_HOUR).roundToInt()) },
                view.sleep.delta, { "${Formatters.signed((it * MINUTES_PER_HOUR).roundToInt())} мин" }, Palette.purple, Modifier.weight(1f))
            Kpi("Win-rate команды", winRateText, view.winRate.delta.takeIf { gamesKnown }, { "${Formatters.signed(it.roundToInt())}%" }, Palette.orange, Modifier.weight(1f), faceitFailedText(faceitFailed))
        }
    }
}

/** [value] null is «нет данных»; [delta] is the change against the period before it. */
@Composable
private fun Kpi(title: String, value: String?, delta: Double?, format: (Double) -> String, tint: Color, modifier: Modifier, note: String? = null) {
    Column(modifier.pulseCard(14.dp, Metrics.tileRadius), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Palette.textSecondary, autoSize = fit(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value ?: NO_DATA, color = if (value == null) Palette.textTertiary else tint, autoSize = fit(19.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(delta?.let(format) ?: " ", color = if ((delta ?: 0.0) >= 0) Palette.green else Palette.orange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (note != null) Text(note, color = Palette.orange, fontSize = 11.sp)
    }
}

/** «Faceit не ответил для N игроков»: the figures above it are built without those players. */
@Composable
private fun FaceitFailedNote(failed: Int) {
    val text = faceitFailedText(failed) ?: return
    Text(text, color = Palette.orange, fontSize = 12.sp)
}

@Composable
private fun ReadinessBars(bars: List<StaffViews.PlayerBar>) {
    val router = LocalRouter.current
    val attention = StaffMetrics.PILL_THRESHOLDS.getValue("readiness").mid
    Column(Modifier.pulseCard(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        bars.forEach { bar ->
            val color = readinessTint(bar.status)
            val route = playerRoute(bar.id)
            val open = if (route != null) Modifier.pressable { router.open(route) } else Modifier
            Row(open, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(bar.nickname, color = Palette.textPrimary, autoSize = fit(14.sp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(58.dp))
                val readiness = bar.readiness
                if (readiness == null) {
                    Text(NO_DATA, color = Palette.textTertiary, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                } else {
                    // Bar with the attention threshold marked in orange.
                    BoxWithConstraints(Modifier.weight(1f).height(18.dp), contentAlignment = Alignment.CenterStart) {
                        val full = maxWidth
                        Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(Palette.track))
                        Box(Modifier.width(full * minOf(readiness / READINESS_MAX, 1.0).toFloat()).height(12.dp).clip(CircleShape).background(color))
                        Box(Modifier.offset(x = full * (attention / READINESS_MAX).toFloat() - 1.dp).size(2.dp, 18.dp).background(Palette.orange))
                    }
                    Text("${readiness.roundToInt()}", color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(34.dp))
                }
            }
        }
        Text("Оранжевая отметка — порог ${attention.roundToInt()}: ниже него игрок в зоне риска. Нажми на игрока, чтобы открыть карточку.", color = Palette.textTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun TrendCard(trend: List<StaffViews.TrendPoint>) {
    // A day nobody has a readiness for is a gap, not a zero: the line runs over the days that have one.
    val points = trend.filter { it.mean != null && it.min != null && it.max != null }
    Column(Modifier.pulseCard(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (points.size >= 2) {
            PulseLineChart(
                points.map { requireNotNull(it.mean) },
                calloutFor = { i ->
                    Callout(shortDay(points[i].day), listOf(
                        "Среднее ${requireNotNull(points[i].mean).roundToInt()}" to Palette.cyan,
                        "${requireNotNull(points[i].min).roundToInt()} – ${requireNotNull(points[i].max).roundToInt()}" to Palette.textSecondary
                    ))
                },
                height = 160.dp, showsArea = false, lineWidth = 2.5.dp,
                band = points.map { requireNotNull(it.min) } to points.map { requireNotNull(it.max) }
            )
            val step = maxOf((points.size + 3) / 4, 1)
            FractionLabels(points.indices.filter { it % step == 0 }.map { i -> i / (points.size - 1f) to shortDay(points[i].day) })
        } else {
            Text(NO_DATA, color = Palette.textTertiary, fontSize = 15.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("Среднее по команде", Palette.cyan)
            Legend("Разброс игроков", Palette.cyan.copy(alpha = 0.3f))
        }
    }
}

/** «14 окт» for a `YYYY-MM-DD` day. */
private fun shortDay(day: String): String = Formatters.dayMonthShort(LocalDate.parse(day).atStartOfDayIn(TimeZone.currentSystemDefault()))

@Composable
private fun EloChart(dots: List<EloDot>, days: Int, faceitFailed: Int) {
    Column(Modifier.pulseCard(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (dots.isEmpty()) {
            Text(NO_DATA, color = Palette.textTertiary, fontSize = 15.sp)
        } else {
            ScatterChart(
                dots.map { ScatterDot(it.kd, it.elo, Palette.cyan, it.nickname) },
                calloutFor = { i ->
                    Callout(dots[i].nickname, listOf("ELO ${Formatters.grouped(dots[i].elo.roundToIntSwift())}" to Palette.cyan, "K/D ${Formatters.decimal(dots[i].kd, 2)}" to Palette.green))
                },
                height = 200.dp, xFormat = { Formatters.decimal(it, 2) }, yFormat = { Formatters.grouped(it.roundToIntSwift()) }, dotRadius = 6.dp
            )
        }
        Text("По горизонтали K/D за $days дней, по вертикали текущий ELO", color = Palette.textTertiary, fontSize = 12.sp)
        FaceitFailedNote(faceitFailed)
    }
}

@Composable
private fun PlayersTable(board: TeamDashboard, sort: TableSort, onSort: (TableSort) -> Unit) {
    val router = LocalRouter.current
    val rows = board.view.table
    val sorted = when (sort) {
        TableSort.READINESS -> rows.sortedByDescending { it.readiness ?: -1.0 }
        TableSort.SCORE -> rows.sortedByDescending { it.score ?: -1.0 }
        TableSort.SLEEP -> rows.sortedByDescending { it.sleepHours ?: -1.0 }
        TableSort.ELO -> rows.sortedByDescending { board.elo[it.id] ?: -1.0 }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TableSort.entries.forEach { item -> ChipButton(item.title, sort == item) { onSort(item) } }
        }
        Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 14.dp)) {
            Row(Modifier.padding(vertical = 12.dp)) {
                TableHeader("Игрок", Modifier.weight(1f), TextAlign.Start)
                TableHeader("Готов.", Modifier.width(COLUMN_WIDTH))
                TableHeader("Score", Modifier.width(COLUMN_WIDTH))
                TableHeader("Сон", Modifier.width(COLUMN_WIDTH))
            }
            sorted.forEach { row ->
                DividerLine()
                val route = playerRoute(row.id)
                TableRow(row, board.elo[row.id], if (route != null) Modifier.pressable { router.open(route) } else Modifier)
            }
        }
    }
}

private val COLUMN_WIDTH = 60.dp

@Composable
private fun TableHeader(text: String, modifier: Modifier, align: TextAlign = TextAlign.End) {
    Text(text, color = Palette.textTertiary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = align, modifier = modifier)
}

@Composable
private fun TableRow(row: StaffViews.TableRow, elo: Double?, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.nickname, color = Palette.textPrimary, autoSize = fit(16.sp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (row.status == StaffMetrics.ReadinessStatus.RISK) Icon(sfIcon("exclamationmark.triangle.fill"), "Риск", tint = Palette.orange, modifier = Modifier.size(12.dp))
            }
            Text(elo?.let { "ELO ${Formatters.grouped(it.roundToIntSwift())}" } ?: "ELO: $NO_DATA", color = Palette.textSecondary, autoSize = fit(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Cell(row.readiness?.let { "${it.roundToInt()}" }, readinessTint(row.status))
        Cell(row.score?.let { "${it.roundToInt()}" }, Palette.textPrimary)
        Cell(row.sleepHours?.let { Formatters.duration((it * MINUTES_PER_HOUR).roundToInt()) }, Palette.textPrimary)
    }
}

/** A table value; «нет данных» wraps to two short lines in the narrow column. */
@Composable
private fun Cell(value: String?, tint: Color) {
    if (value == null) Text(NO_DATA, color = Palette.textTertiary, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(COLUMN_WIDTH))
    else Text(value, color = tint, autoSize = fit(15.sp), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(COLUMN_WIDTH))
}

// ------------------------------------------------------------------------------------------------ «Корреляции»

/** Heat map of factors × game metrics, «сон → K/D» scatter and the strongest relations of a real team. */
@Composable
fun RealTeamCorrelations(matches: TeamMatches) = RealTeamScreenContent { RealCorrelations(it.team, matches) }

@Composable
private fun RealCorrelations(team: TeamSnapshot, matches: TeamMatches) {
    var selected by remember { mutableStateOf<String?>(null) }
    val day = teamDay()
    val played by rememberTeamMatchesState(matches, team)
    val games = played
    val computed by produceState<StaffViews.TeamCorrelations?>(null, team, selected, day.today, games) {
        value = games?.let { withContext(Dispatchers.Default) { StaffViews.teamCorrelations(team, it.matches, day.today, day.tz, CORRELATION_DAYS, selected) } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipButton("Вся команда", selected == null) { selected = null }
            team.players.forEach { player -> ChipButton(player.nickname, selected == player.id) { selected = player.id } }
        }
        val result = computed
        if (result == null) {
            TeamLoadingState()
            return@Column
        }
        val failed = games?.failed?.size ?: 0
        PulseSection("Карта связей") { RealHeatmap(result) }
        PulseSection("Сон → K/D") { SleepKdCard(result.sleepKd, failed) }
        PulseSection("Главные связи") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result.links.isEmpty()) NoDataCard() else CorrelationsSection(result.links.take(LINKS_SHOWN))
                FaceitFailedNote(failed)
            }
        }
        Text("Коэффициент Пирсона от −1 до +1 по дням с матчами. Положительное значение — показатели растут вместе, отрицательное — один растёт, другой падает. Связь не означает причину.",
            color = Palette.textTertiary, fontSize = 13.sp)
    }
}

@Composable
private fun NoDataCard() {
    Text(NO_DATA, color = Palette.textTertiary, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().pulseCard(16.dp))
}

@Composable
private fun RealHeatmap(result: StaffViews.TeamCorrelations) {
    if (result.rows.all { row -> row.cells.all { it.r == null } }) {
        NoDataCard()
        return
    }
    Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(FACTOR_WIDTH))
            result.games.forEach {
                Text(it.label, color = Palette.textSecondary, autoSize = fit(12.sp), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    maxLines = CORRELATION_LABEL_LINES, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        result.rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.factor.label, color = Palette.textPrimary, autoSize = fit(13.sp), fontWeight = FontWeight.Medium,
                    maxLines = CORRELATION_LABEL_LINES, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(FACTOR_WIDTH))
                row.cells.forEach { cell ->
                    Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(8.dp)).background(heatColor(cell.r)), contentAlignment = Alignment.Center) {
                        Text(cell.r?.let(::signedCoefficient) ?: "—", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("−1", color = Palette.textTertiary, fontSize = 11.sp)
            Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(Palette.red, Palette.track, Palette.green))))
            Text("+1", color = Palette.textTertiary, fontSize = 11.sp)
        }
        Text("«—»: мало данных для расчёта", color = Palette.textTertiary, fontSize = 12.sp)
    }
}

private val FACTOR_WIDTH = 96.dp

@Composable
private fun SleepKdCard(scatter: StaffViews.SleepKdScatter, faceitFailed: Int) {
    val points = scatter.points
    Column(Modifier.pulseCard(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (points.isEmpty()) {
            Text(NO_DATA, color = Palette.textTertiary, fontSize = 15.sp)
        } else {
            ScatterChart(
                points.map { ScatterDot(it.sleepHours, it.kd, if (it.won) Palette.green.copy(alpha = 0.8f) else Palette.red.copy(alpha = 0.7f)) },
                calloutFor = { i ->
                    val point = points[i]
                    Callout("${point.nickname} · ${if (point.won) "победа" else "поражение"}", listOf(
                        "Сон ${Formatters.duration((point.sleepHours * MINUTES_PER_HOUR).roundToIntSwift())}" to Palette.purple,
                        "K/D ${Formatters.decimal(point.kd, 2)}" to (if (point.won) Palette.green else Palette.red)
                    ))
                },
                height = 210.dp, xFormat = { "${Formatters.decimal(it)} ч" }, yFormat = { Formatters.decimal(it, 1) }, dotRadius = 4.dp
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot("Победа", Palette.green)
            Box(Modifier.width(14.dp))
            Dot("Поражение", Palette.red)
            Text("r = ${scatter.r?.let(::signedCoefficient) ?: NO_DATA} · ${Formatters.plural(points.size, "матч", "матча", "матчей")}",
                color = Palette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        FaceitFailedNote(faceitFailed)
    }
}
