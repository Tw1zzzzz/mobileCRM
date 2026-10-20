package com.pulse.android.ui.staff

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.components.ScatterChart
import com.pulse.android.ui.components.ScatterDot
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.statistics.CorrelationTable
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.android.ui.today.WeeklySummaryCard
import com.pulse.core.demo.DemoData
import com.pulse.core.demo.GameData
import com.pulse.core.demo.GameMetric
import com.pulse.core.demo.PlayerSnapshot
import com.pulse.core.demo.TeamAnalytics
import com.pulse.core.demo.TeamFood
import com.pulse.core.demo.WellbeingFactor
import com.pulse.core.model.GameSummary
import com.pulse.core.model.Meal
import com.pulse.core.util.Formatters
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.isSameDay
import com.pulse.core.util.roundToIntSwift
import com.pulse.core.util.startOfDay
import kotlin.math.abs
import kotlin.uuid.Uuid

fun readinessColor(value: Double): Color = when {
    value >= 7 -> Palette.green
    value >= 6 -> Palette.cyan
    else -> Palette.orange
}

/** «±0,35» with a proper minus — correlation coefficients. */
fun signedCoefficient(value: Double): String = "${if (value >= 0) "+" else "−"}${Formatters.decimal(abs(value), 2)}"

private enum class SortBy(val title: String) { READINESS("Готовность"), SCORE("Score"), SLEEP("Сон"), ELO("ELO") }

/** Team dashboard: KPIs, readiness per player, dynamics, ELO vs K/D and a sortable table. */
@Composable
fun TeamAnalyticsContent() {
    val router = LocalRouter.current
    var period by remember { mutableIntStateOf(7) }
    var sort by remember { mutableStateOf(SortBy.READINESS) }
    val snapshots = TeamAnalytics.snapshots(period)
    val summary = GameSummary.of(DemoData.players.flatMap { GameData.matches(it, period) })
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        WeeklySummaryCard()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30).forEach { days -> ChipButton("$days дней", period == days) { period = days } }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Kpi("Готовность команды", "${Formatters.decimal(StatisticsMath.mean(snapshots.map { it.readiness }) ?: 0.0)} / 10", Palette.green, Modifier.weight(1f))
                Kpi("Средний score", "${(StatisticsMath.mean(snapshots.map { it.averageScore.toDouble() }) ?: 0.0).toInt()}", Palette.cyan, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Kpi("Средний сон", Formatters.duration((StatisticsMath.mean(snapshots.map { it.sleepMinutes.toDouble() }) ?: 0.0).toInt()), Palette.purple, Modifier.weight(1f))
                Kpi("Win-rate команды", "${summary.winRate.roundToIntSwift()}% · ${summary.matches} игр", Palette.orange, Modifier.weight(1f))
            }
        }
        PulseSection("Готовность по игрокам") { ReadinessBars(snapshots) }
        PulseSection("Динамика готовности") { TrendCard(period) }
        PulseSection("ELO и K/D") { EloChart(period) }
        PulseSection("Таблица") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortBy.entries.forEach { item -> ChipButton(item.title, sort == item) { sort = item } }
                }
                val sorted = when (sort) {
                    SortBy.READINESS -> snapshots.sortedByDescending { it.readiness }
                    SortBy.SCORE -> snapshots.sortedByDescending { it.averageScore }
                    SortBy.SLEEP -> snapshots.sortedByDescending { it.sleepMinutes }
                    SortBy.ELO -> snapshots.sortedByDescending { it.player.elo }
                }
                Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 14.dp)) {
                    Row(Modifier.padding(vertical = 12.dp)) {
                        TableHeader("Игрок", Modifier.weight(1f), TextAlign.Start)
                        TableHeader("Готов.", Modifier.width(54.dp))
                        TableHeader("Score", Modifier.width(50.dp))
                        TableHeader("Сон", Modifier.width(66.dp))
                    }
                    sorted.forEach { snapshot ->
                        DividerLine()
                        TableRow(snapshot) { router.open(Route.Player(snapshot.player.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Kpi(title: String, value: String, tint: Color, modifier: Modifier) {
    Column(modifier.pulseCard(14.dp, Metrics.tileRadius), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 1)
        Text(value, color = tint, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ReadinessBars(snapshots: List<PlayerSnapshot>) {
    val router = LocalRouter.current
    Column(Modifier.pulseCard(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        snapshots.sortedByDescending { it.readiness }.forEach { snapshot ->
            val color = readinessColor(snapshot.readiness)
            Row(Modifier.pressable { router.open(Route.Player(snapshot.player.id)) }, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(snapshot.player.nickname, color = Palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.width(58.dp))
                // Bar with the 6,0 attention threshold marked in orange.
                BoxWithConstraints(Modifier.weight(1f).height(18.dp), contentAlignment = Alignment.CenterStart) {
                    val full = maxWidth
                    Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(Palette.track))
                    Box(Modifier.width(full * minOf(snapshot.readiness / 10, 1.0).toFloat()).height(12.dp).clip(CircleShape).background(color))
                    Box(Modifier.offset(x = full * 0.6f - 1.dp).size(2.dp, 18.dp).background(Palette.orange))
                }
                Text(Formatters.decimal(snapshot.readiness), color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(30.dp))
            }
        }
        Text("Оранжевая отметка — порог 6,0: ниже него игрок требует внимания. Нажми на игрока, чтобы открыть карточку.", color = Palette.textTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun TrendCard(period: Int) {
    val trend = TeamAnalytics.teamReadinessTrend(maxOf(period, 7))
    Column(Modifier.pulseCard(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (trend.size >= 2) {
            PulseLineChart(
                trend.map { it.average },
                calloutFor = { i ->
                    Callout(Formatters.dayMonthShort(trend[i].date), listOf(
                        "Среднее ${Formatters.decimal(trend[i].average)}" to Palette.cyan,
                        "${Formatters.decimal(trend[i].low)} – ${Formatters.decimal(trend[i].high)}" to Palette.textSecondary
                    ))
                },
                height = 160.dp, showsArea = false, lineWidth = 2.5.dp, lower = 4.0, upper = 9.0,
                band = trend.map { it.low } to trend.map { it.high }
            )
            val step = maxOf((trend.size + 3) / 4, 1)
            FractionLabels(trend.indices.filter { it % step == 0 }.map { i -> i / (trend.size - 1f) to Formatters.dayMonthShort(trend[i].date) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("Среднее по команде", Palette.cyan)
            Legend("Разброс игроков", Palette.cyan.copy(alpha = 0.3f))
        }
    }
}

@Composable
fun Legend(title: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp, 8.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(title, color = Palette.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun EloChart(period: Int) {
    val points = DemoData.players.map { player -> Triple(player.nickname, GameSummary.of(GameData.matches(player, period)).kd, player.elo) }
    Column(Modifier.pulseCard(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScatterChart(
            points.map { ScatterDot(it.second, it.third.toDouble(), Palette.cyan, it.first) },
            calloutFor = { i ->
                Callout(points[i].first, listOf("ELO ${points[i].third}" to Palette.cyan, "K/D ${Formatters.decimal(points[i].second, 2)}" to Palette.green))
            },
            height = 200.dp, xFormat = { Formatters.decimal(it, 2) }, yFormat = { Formatters.grouped(it.roundToIntSwift()) }, dotRadius = 6.dp
        )
        Text("По горизонтали K/D за $period дней, по вертикали текущий ELO", color = Palette.textTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun TableHeader(text: String, modifier: Modifier, align: TextAlign = TextAlign.End) {
    Text(text, color = Palette.textTertiary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = align, modifier = modifier)
}

@Composable
private fun TableRow(snapshot: PlayerSnapshot, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(snapshot.player.nickname, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (snapshot.needsAttention) Icon(sfIcon("exclamationmark.triangle.fill"), "Требует внимания", tint = Palette.orange, modifier = Modifier.size(12.dp))
            }
            Text("${snapshot.player.gameRole} · ELO ${snapshot.player.elo}", color = Palette.textSecondary, fontSize = 12.sp, maxLines = 1)
        }
        Text(Formatters.decimal(snapshot.readiness), color = readinessColor(snapshot.readiness), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(54.dp))
        Text("${snapshot.averageScore}", color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(50.dp))
        Text(Formatters.duration(snapshot.sleepMinutes), color = if (snapshot.sleepMinutes < 380) Palette.red else Palette.textPrimary, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(66.dp))
    }
}

/** Heat map of factors × game metrics, «сон → K/D» scatter and the strongest relations. */
@Composable
fun TeamCorrelationsContent() {
    var selected by remember { mutableStateOf<Uuid?>(null) }
    val players = selected?.let { id -> DemoData.players.filter { it.id == id } } ?: DemoData.players
    val points = TeamAnalytics.sleepVersusKD(players)
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipButton("Вся команда", selected == null) { selected = null }
            DemoData.players.forEach { player -> ChipButton(player.nickname, selected == player.id) { selected = player.id } }
        }
        PulseSection("Карта связей") { Heatmap(TeamAnalytics.matrix(players)) }
        PulseSection("Сон → K/D") {
            Column(Modifier.pulseCard(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScatterChart(
                    points.map { ScatterDot(it.sleepHours, it.kd, if (it.won) Palette.green.copy(alpha = 0.8f) else Palette.red.copy(alpha = 0.7f)) },
                    calloutFor = { i ->
                        val point = points[i]
                        Callout("${point.player} · ${if (point.won) "победа" else "поражение"}", listOf(
                            "Сон ${Formatters.duration((point.sleepHours * 60).roundToIntSwift())}" to Palette.purple,
                            "K/D ${Formatters.decimal(point.kd, 2)}" to (if (point.won) Palette.green else Palette.red)
                        ))
                    },
                    height = 210.dp, xFormat = { "${Formatters.decimal(it)} ч" }, yFormat = { Formatters.decimal(it, 1) },
                    trend = TeamAnalytics.trendLine(points), dotRadius = 4.dp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot("Победа", Palette.green)
                    Box(Modifier.width(14.dp))
                    Dot("Поражение", Palette.red)
                    val r = StatisticsMath.pearson(points.map { it.sleepHours }, points.map { it.kd })
                    Text("r = ${r?.let(::signedCoefficient) ?: "—"} · ${points.size} матчей", color = Palette.textSecondary, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
            }
        }
        PulseSection("Главные связи") { CorrelationTable(TeamAnalytics.correlationRows(players).take(5)) }
        Text("Коэффициент Пирсона от −1 до +1 по дням с матчами. Положительное значение — показатели растут вместе, отрицательное — один растёт, другой падает. Связь не означает причину.",
            color = Palette.textTertiary, fontSize = 13.sp)
    }
}

@Composable
private fun Dot(title: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(title, color = Palette.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun Heatmap(matrix: List<List<Double?>>) {
    Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(96.dp))
            GameMetric.entries.forEach { Text(it.title, color = Palette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        WellbeingFactor.entries.forEachIndexed { row, factor ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(factor.title, color = Palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.width(96.dp))
                GameMetric.entries.indices.forEach { column ->
                    val value = matrix.getOrNull(row)?.getOrNull(column)
                    Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(8.dp)).background(heatColor(value)), contentAlignment = Alignment.Center) {
                        Text(value?.let(::signedCoefficient) ?: "—", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("−1", color = Palette.textTertiary, fontSize = 11.sp)
            Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(Palette.red, Palette.track, Palette.green))))
            Text("+1", color = Palette.textTertiary, fontSize = 11.sp)
        }
    }
}

private fun heatColor(value: Double?): Color {
    if (value == null) return Palette.track
    val strength = minOf(abs(value), 1.0)
    if (strength < 0.1) return Palette.track
    return (if (value >= 0) Palette.green else Palette.red).copy(alpha = (0.25 + strength * 0.65).toFloat())
}

/** «Питание игроков»: what the players eat — dish photos, calories and macros for the week. */
@Composable
fun TeamFoodContent() {
    val core = observeCore()
    var player by remember { mutableStateOf<String?>(null) }
    var onlyPhotos by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<TeamFood.Entry?>(null) }
    val all = TeamFood.entries(core.nutrition.meals)
    val entries = all.filter { player == null || it.player == player }.filter { !onlyPhotos || it.meal.photo != null }
    val days = entries.map { it.meal.date.startOfDay() }.distinct().sortedDescending()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipButton("Все", player == null) { player = null }
            DemoData.players.forEach { item -> ChipButton(item.nickname, player == item.nickname) { player = item.nickname } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipButton("Только с фото", onlyPhotos) { onlyPhotos = !onlyPhotos }
            Text("${entries.size} записей · ${entries.count { it.meal.photo != null }} с фото", color = Palette.textSecondary, fontSize = 13.sp,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        if (all.none { it.meal.photo != null }) {
            Row(Modifier.pulseCard(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(sfIcon("camera"), null, tint = Palette.cyan, modifier = Modifier.size(22.dp))
                Text("Фото появятся здесь, когда игроки сфотографируют еду во вкладке «Питание». Ниже — записи без фото.", color = Palette.textSecondary, fontSize = 14.sp)
            }
        }
        if (entries.isEmpty()) EmptyStateView("camera", "Фото пока нет", "Когда игрок сфотографирует еду в приложении, снимок появится здесь")
        days.forEach { day ->
            PulseSection(Formatters.dayMonthWeekday(day)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    entries.filter { it.meal.date.isSameDay(day) }.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { entry -> Box(Modifier.weight(1f).pressable { opened = entry }) { FoodCard(entry) } }
                            if (row.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    opened?.let { entry -> PulseSheet({ opened = null }) { FoodEntrySheet(entry) { opened = null } } }
}

@Composable
private fun FoodCard(entry: TeamFood.Entry) {
    val shape = RoundedCornerShape(Metrics.tileRadius)
    Column(Modifier.fillMaxWidth().clip(shape).background(Palette.tile).border(1.dp, Palette.stroke, shape)) {
        Box(Modifier.fillMaxWidth().height(118.dp)) {
            MealPhoto(entry.meal)
            Text(entry.player, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.meal.name, color = Palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${entry.meal.kcal} ккал · ${Formatters.time(entry.meal.date)}", color = Palette.textSecondary, fontSize = 12.sp)
        }
    }
}

/** Real photo when the player took one, otherwise a neutral placeholder. */
@Composable
private fun MealPhoto(meal: Meal) {
    val bitmap = remember(meal.id) { meal.photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), meal.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Column(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Palette.track, Palette.card))),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Icon(sfIcon("fork.knife"), null, tint = Palette.textTertiary, modifier = Modifier.size(26.dp))
            Text("без фото", color = Palette.textTertiary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun FoodEntrySheet(entry: TeamFood.Entry, onClose: () -> Unit) {
    SheetScaffold(entry.player, onClose) {
        Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(Metrics.cardRadius))) { MealPhoto(entry.meal) }
        Column(Modifier.pulseCard(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entry.meal.name, color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${Formatters.dayMonthTime(entry.meal.date)} · ${entry.meal.source.title}", color = Palette.textSecondary, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Triple("Ккал", entry.meal.kcal, Palette.textPrimary), Triple("Белки", entry.meal.protein, Palette.cyan),
                    Triple("Жиры", entry.meal.fat, Palette.orange), Triple("Углев.", entry.meal.carbs, Palette.purple)).forEach { (title, value, tint) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("$value", color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(title, color = Palette.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Standalone screens (routes from other places). */
@Composable
fun TeamAnalyticsScreen() {
    val router = LocalRouter.current
    ScreenScaffold(spacing = 22.dp, header = { NavHeader("Аналитика игроков", "Дашборд команды", onBack = { router.pop() }) }) { TeamAnalyticsContent() }
}

@Composable
fun TeamCorrelationsScreen() {
    val router = LocalRouter.current
    ScreenScaffold(spacing = 22.dp, header = { NavHeader("Корреляции", "Игровые и неигровые признаки · 30 дней", onBack = { router.pop() }) }) { TeamCorrelationsContent() }
}

@Composable
fun TeamFoodScreen() {
    val router = LocalRouter.current
    ScreenScaffold(spacing = 18.dp, header = { NavHeader("Питание игроков", "Фото еды за 7 дней", onBack = { router.pop() }) }) { TeamFoodContent() }
}
