package com.pulse.android.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.DateRangePickerState
import androidx.compose.runtime.Composable
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.Callout
import com.pulse.android.ui.components.ChipButton
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.components.FractionLabels
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.HubRow
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseLineChart
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.PulseSegmented
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.components.RadarChart
import com.pulse.android.ui.components.RussianLocale
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.Sparkline
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.state.StateMetricCard
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.demo.GameMetric
import com.pulse.core.feature.StatisticsData
import com.pulse.core.feature.StatsPeriod
import com.pulse.core.model.BalanceCategory
import com.pulse.core.model.CorrelationRow
import com.pulse.core.model.DailyValue
import com.pulse.core.util.CorrelationStrength
import com.pulse.core.util.Formatters
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.roundToIntSwift
import com.pulse.core.util.startInstant
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.time.Clock

/** «Статистика» as its own screen (from «Фитнес» and «Аналитика»). */
@Composable
fun StatisticsScreen() {
    val router = LocalRouter.current
    ScreenScaffold(header = { NavHeader("Статистика", "Агрегированные показатели", onBack = { router.pop() }) }) {
        StatisticsContent()
    }
}

/**
 * Period filter, mood/energy, cognitive form, balance wheel, recovery, non-game analysis, correlations and game
 * analysis. Also the «Состояние → Статистика» section.
 */
@Composable
fun StatisticsContent() {
    val core = observeCore()
    var period by remember { mutableStateOf<StatsPeriod>(StatsPeriod.Days(30)) }
    var customSheet by remember { mutableStateOf(false) }
    val now = Clock.System.now()
    val data = StatisticsData.build(period, core.sleep, core.nutrition, core.wellbeing, core.cognitive, core.screenTime, now)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionSpacing)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsPeriod.presets.forEach { preset -> ChipButton(preset.title, period == preset) { period = preset } }
            val custom = period as? StatsPeriod.Custom
            ChipButton(custom?.let { "${Formatters.dayMonthShort(it.start)} – ${Formatters.dayMonthShort(it.end)}" } ?: "Произвольный", custom != null) { customSheet = true }
        }
        if (data.isEmpty) {
            EmptyStateView("chart.xyaxis.line", "Нет данных за период", "Заполни тесты, сон и настроение, чтобы увидеть статистику.")
        } else {
            PulseSection("Настроение и энергия") { MoodEnergyCard(data) }
            PulseSection("Когнитивная форма") {
                Column(Modifier.pulseCard(padding = 20.dp)) {
                    MetricLineChart(data.cognitive, Palette.green, lower = 0.0, upper = 100.0) { "${it.roundToIntSwift()} баллов" }
                }
            }
            PulseSection("Колесо баланса") {
                val entry = core.wellbeing.latestBalance
                if (entry == null) {
                    EmptyStateView("circle.hexagongrid", "Нет данных", "Заполни колесо баланса")
                } else {
                    Column(Modifier.pulseCard(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        RadarChart(BalanceCategory.entries.map { it.shortTitle }, entry.orderedValues, height = 300.dp)
                        Text("Последнее заполнение: ${Formatters.dayMonth(entry.day)} · средний балл ${Formatters.decimal(entry.average)}", color = Palette.textSecondary, fontSize = 14.sp)
                    }
                }
            }
            PulseSection("Восстановление и образ жизни") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecoveryStatCard("moon", Palette.purple, "Сон", data.averageSleep?.let { Formatters.duration(it) } ?: "—",
                        "средняя продолжительность · ${data.sleepCount} записей")
                    val change = data.screenTimeChange
                    RecoveryStatCard("iphone", Palette.cyan, "Экранное время", Formatters.duration(data.averageScreenTime),
                        change?.let { "${if (it > 0) "+" else ""}${it.roundToIntSwift()}% к прошлому периоду" } ?: "нет данных для сравнения",
                        if ((change ?: 0.0) <= 0) Palette.green else Palette.red)
                    RecoveryStatCard("fork.knife", Palette.green, "Питание", data.averageKcal?.let { "${Formatters.grouped(it)} ккал" } ?: "—",
                        "в среднем в день · ${data.mealCount} записей")
                }
            }
            PulseSection("Неигровой анализ") { NonGameAnalysis(data) }
            PulseSection("Корреляции") { CorrelationTable(data.correlations) }
            PulseSection("Игровой анализ") { GameAnalysis(minOf(period.dayCount(now), 90)) }
        }
    }
    if (customSheet) PulseSheet({ customSheet = false }) {
        CustomPeriodSheet(onClose = { customSheet = false }) { period = it }
    }
}

@Composable
private fun MoodEnergyCard(data: StatisticsData) {
    var showMood by remember { mutableStateOf(true) }
    var showEnergy by remember { mutableStateOf(true) }
    Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipButton("Настроение", showMood, tint = Palette.yellow) { showMood = !showMood }
            ChipButton("Энергия", showEnergy) { showEnergy = !showEnergy }
        }
        // Mood and energy come from the same entries, so both series share their days.
        val primary = if (showMood) data.mood else data.energy
        val primaryColor = if (showMood) Palette.yellow else Palette.cyan
        val secondary = if (showMood && showEnergy) data.energy else null
        if (!showMood && !showEnergy || primary.isEmpty()) {
            NoData()
        } else {
            PulseLineChart(
                primary.map { it.value },
                calloutFor = { i ->
                    Callout(Formatters.dayMonthShort(primary[i].date), listOfNotNull(
                        data.mood.getOrNull(i)?.takeIf { showMood }?.let { "Настроение ${Formatters.decimal(it.value)}" to Palette.yellow },
                        data.energy.getOrNull(i)?.takeIf { showEnergy }?.let { "Энергия ${Formatters.decimal(it.value)}" to Palette.cyan }
                    ))
                },
                height = 170.dp, color = primaryColor, showsArea = false, lineWidth = 2.5.dp, lower = 0.0, upper = 10.0,
                secondary = secondary?.map { it.value }, secondaryColor = Palette.cyan
            )
            DateAxis(primary)
        }
    }
}

/** Daily line chart in the app style with date labels; hold to read a day (iOS `MetricLineChart`). */
@Composable
fun MetricLineChart(
    series: List<DailyValue>, color: Color, lower: Double? = null, upper: Double? = null, height: Dp = 170.dp, format: (Double) -> String = { Formatters.decimal(it) }
) {
    if (series.isEmpty()) return NoData()
    PulseLineChart(
        series.map { it.value },
        calloutFor = { i -> Callout(Formatters.dayMonthShort(series[i].date), format(series[i].value), color) },
        height = height, color = color, lineWidth = 2.5.dp, lower = lower, upper = upper
    )
    DateAxis(series)
}

@Composable
private fun DateAxis(series: List<DailyValue>) {
    if (series.size < 2) return
    val step = maxOf((series.size + 3) / 4, 1)
    FractionLabels(series.indices.filter { it % step == 0 || it == series.size - 1 }.distinctBy { it / step }.map { i ->
        i / (series.size - 1f) to Formatters.dayMonthShort(series[i].date)
    })
}

@Composable
private fun NoData() {
    Text("Нет данных за период", color = Palette.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(vertical = 30.dp))
}

@Composable
private fun RecoveryStatCard(icon: String, tint: Color, title: String, value: String, caption: String, captionTint: Color = Palette.textSecondary) {
    Row(Modifier.fillMaxWidth().pulseCard(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(sfIcon(icon), tint, 50.dp, 22.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Palette.textSecondary, fontSize = 15.sp)
            Text(value, color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(caption, color = captionTint, fontSize = 13.sp)
        }
    }
}

private enum class NonGameMetric(val title: String) {
    COGNITIVE("Когнитивная форма"), SLEEP("Сон"), SCREEN("Экранное время"), MOOD("Настроение и энергия"), FOOD("Еда")
}

/** «Неигровой анализ»: pick metrics, each gets its own chart. */
@Composable
private fun NonGameAnalysis(data: StatisticsData) {
    var selected by remember { mutableStateOf(setOf(NonGameMetric.SLEEP, NonGameMetric.COGNITIVE)) }
    val hours: (Double) -> String = { Formatters.duration((it * 60).roundToIntSwift()) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NonGameMetric.entries.forEach { metric ->
                ChipButton(metric.title, metric in selected) {
                    // Keep at least one chart open so the section never collapses to nothing.
                    val next = if (metric in selected) selected - metric else selected + metric
                    if (next.isNotEmpty()) selected = next
                }
            }
        }
        NonGameMetric.entries.filter { it in selected }.forEach { metric ->
            Column(Modifier.pulseCard(padding = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(metric.title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                when (metric) {
                    NonGameMetric.COGNITIVE -> MetricLineChart(data.cognitive, Palette.green, 0.0, 100.0, 140.dp) { "${it.roundToIntSwift()} баллов" }
                    NonGameMetric.SLEEP -> MetricLineChart(data.sleepHours, Palette.purple, height = 140.dp, format = hours)
                    NonGameMetric.SCREEN -> MetricLineChart(data.screenHours, Palette.cyan, height = 140.dp, format = hours)
                    NonGameMetric.MOOD -> MetricLineChart(data.mood, Palette.yellow, 0.0, 10.0, 140.dp) { "${Formatters.decimal(it)} / 10" }
                    NonGameMetric.FOOD -> MetricLineChart(data.kcal, Palette.orange, height = 140.dp) { "${Formatters.grouped(it.toInt())} ккал" }
                }
            }
        }
    }
}

/** Correlations with color-coded strength. */
@Composable
fun CorrelationTable(rows: List<CorrelationRow>) {
    Column(Modifier.fillMaxWidth().pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
        if (rows.isEmpty()) {
            Text("Недостаточно данных для расчёта (нужно ≥ 3 совпадающих дня)", color = Palette.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(vertical = 16.dp))
        }
        rows.forEachIndexed { index, row ->
            val color = correlationColor(row.value)
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${row.factor} → ${row.metric}", color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("${CorrelationStrength.of(row.value).title.replaceFirstChar { it.uppercase() }} ${if (row.value >= 0) "прямая" else "обратная"} связь",
                        color = Palette.textSecondary, fontSize = 12.sp)
                }
                Text(
                    "${if (row.value >= 0) "+" else "−"}${Formatters.decimal(abs(row.value), 2)}", color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            if (index < rows.size - 1) DividerLine()
        }
    }
}

private fun correlationColor(value: Double): Color = when (CorrelationStrength.of(value)) {
    CorrelationStrength.STRONG -> if (value > 0) Palette.green else Palette.red
    CorrelationStrength.MODERATE -> if (value > 0) Palette.cyan else Palette.orange
    CorrelationStrength.WEAK -> Palette.textSecondary
}

private enum class GameChartMetric(val title: String, val color: Color) {
    ELO("ELO", Palette.cyan), KD("K/D", Palette.green), ADR("ADR", Palette.orange);

    fun format(value: Double): String = when (this) {
        ELO -> Formatters.grouped(value.roundToIntSwift())
        KD -> Formatters.decimal(value, 2)
        ADR -> Formatters.decimal(value)
    }
}

/** «Игровой анализ»: ELO, K/D and ADR of the current player — one chart with a switch. */
@Composable
private fun GameAnalysis(days: Int) {
    val core = observeCore()
    val user = core.session.user.value
    var metric by remember { mutableStateOf(GameChartMetric.ELO) }
    val span = maxOf(days, 7)
    val values = when (metric) {
        GameChartMetric.ELO -> core.game.eloHistory(user, span)
        GameChartMetric.KD -> core.game.daily(GameMetric.KD, user, span)
        GameChartMetric.ADR -> core.game.daily(GameMetric.ADR, user, span)
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PulseSegmented(GameChartMetric.entries, metric, { it.title }, height = 36.dp) { metric = it }
        Column(Modifier.pulseCard(padding = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(metric.title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                values.lastOrNull()?.let { Text("сейчас ${metric.format(it.value)}", color = metric.color, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
            MetricLineChart(values, metric.color, height = 160.dp, format = metric::format)
        }
    }
}

/** Custom range with the system date range picker; future days are disabled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomPeriodSheet(onClose: () -> Unit, onApply: (StatsPeriod) -> Unit) {
    val today = System.currentTimeMillis()
    val state = remember {
        DateRangePickerState(
            locale = Locale.forLanguageTag("ru"),
            initialSelectedStartDateMillis = today - 14L * 86_400_000, initialSelectedEndDateMillis = today,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today
            }
        )
    }
    SheetScaffold("Произвольный период", onClose) {
        RussianLocale {
        DateRangePicker(
            state, modifier = Modifier.height(460.dp), title = null, showModeToggle = false,
            colors = DatePickerDefaults.colors(containerColor = Palette.background, dayContentColor = Palette.textPrimary,
                selectedDayContainerColor = Palette.blue, selectedDayContentColor = Color.White, dayInSelectionRangeContainerColor = Palette.blue.copy(alpha = 0.3f),
                todayDateBorderColor = Palette.cyan, todayContentColor = Palette.cyan, weekdayContentColor = Palette.textSecondary,
                subheadContentColor = Palette.textSecondary, headlineContentColor = Palette.textPrimary, disabledDayContentColor = Palette.textTertiary)
        )
        }
        val start = state.selectedStartDateMillis
        val end = state.selectedEndDateMillis
        GradientButton("Применить", enabled = start != null && end != null) {
            if (start == null || end == null) return@GradientButton
            // Picker values are UTC midnights: read them as calendar dates, then start the day in the user's zone.
            fun day(millis: Long) = LocalDate.fromEpochDays((millis / 86_400_000).toInt()).startInstant()
            onApply(StatsPeriod.Custom(day(start), day(end)))
            onClose()
        }
    }
}

/** «Состояние → Аналитика»: mood and cognitive form for 30 days and the strongest relations. */
@Composable
fun AnalyticsSection(onOpenStatistics: () -> Unit) {
    val core = observeCore()
    val data = StatisticsData.build(StatsPeriod.Days(30), core.sleep, core.nutrition, core.wellbeing, core.cognitive, core.screenTime, Clock.System.now())
    val top = data.correlations.sortedByDescending { abs(it.value) }.take(3)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StateMetricCard("face.smiling", Palette.yellow, "Настроение",
            StatisticsMath.mean(data.mood.map { it.value })?.let { "${Formatters.decimal(it)} из 10" } ?: "—",
            "Энергия ${StatisticsMath.mean(data.energy.map { it.value })?.let { Formatters.decimal(it) } ?: "—"} · за 30 дней", Route.Statistics) {
            Sparkline(data.mood.takeLast(14), Modifier.width(76.dp).height(40.dp), Palette.yellow)
        }
        StateMetricCard("brain.head.profile", Palette.green, "Когнитивная форма",
            StatisticsMath.mean(data.cognitive.map { it.value })?.let { "${it.toInt()} баллов" } ?: "—",
            "Среднее за 30 дней", Route.Statistics) {
            Sparkline(data.cognitive.takeLast(14), Modifier.width(76.dp).height(40.dp), Palette.green)
        }
        PulseSection("Главные связи · 30 дней") { CorrelationTable(top) }
        HubRow("chart.xyaxis.line", Palette.blue, "Вся статистика", "Периоды, графики, неигровой и игровой анализ", onOpenStatistics)
    }
}

