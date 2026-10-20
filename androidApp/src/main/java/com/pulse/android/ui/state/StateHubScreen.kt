package com.pulse.android.ui.state

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseScrollTabs
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.Sparkline
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.android.ui.today.FaceitPromptCard
import com.pulse.android.ui.today.GameSummaryCard
import com.pulse.core.model.DailyValue
import com.pulse.core.model.DaySlot
import com.pulse.core.model.GameMatch
import com.pulse.core.util.Formatters
import com.pulse.core.util.addingDays
import com.pulse.core.util.roundToIntSwift
import com.pulse.core.util.startOfDay
import kotlin.time.Clock

/** «Состояние»: разделы Здоровье · Статистика · Тесты · Игра · Аналитика — на экране только один. */
enum class StateSection(val title: String) {
    BODY("Здоровье"), STATISTICS("Статистика"), TESTS("Тесты"), GAME("Игра"), ANALYTICS("Аналитика");

    companion object {
        private const val KEY = "state.section"

        fun load(context: Context): StateSection =
            entries.firstOrNull { it.name == context.getSharedPreferences("pulse.settings", Context.MODE_PRIVATE).getString(KEY, null) } ?: BODY

        fun save(context: Context, section: StateSection) {
            context.getSharedPreferences("pulse.settings", Context.MODE_PRIVATE).edit().putString(KEY, section.name).apply()
        }
    }
}

@Composable
fun StateHubScreen() {
    val core = observeCore()
    val context = LocalContext.current
    var section by remember { mutableStateOf(StateSection.load(context)) }
    val user = core.session.user.value
    // Each section starts at the top, not at the previous section's scroll offset.
    val scroll = rememberScrollState()
    LaunchedEffect(section) { scroll.scrollTo(0) }
    ScreenScaffold(spacing = 20.dp, scrollState = scroll, header = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NavHeader("Состояние", listOfNotNull(user?.role?.title, user?.team).joinToString(" · "))
            PulseScrollTabs(StateSection.entries, section, { it.title }) {
                section = it
                StateSection.save(context, it)
            }
        }
    }) {
        when (section) {
            StateSection.BODY -> BodySection()
            StateSection.TESTS -> TestsSection()
            StateSection.GAME -> GameSection()
            StateSection.STATISTICS -> com.pulse.android.ui.statistics.StatisticsContent()
            StateSection.ANALYTICS -> com.pulse.android.ui.statistics.AnalyticsSection {
                section = StateSection.STATISTICS
                StateSection.save(context, StateSection.STATISTICS)
            }
        }
    }
}

/** Card with a key value and a 7-day sparkline; opens the detailed screen. */
@Composable
fun StateMetricCard(icon: String, tint: Color, title: String, value: String, caption: String, route: Route, trailing: @Composable RowScope.() -> Unit = {}) {
    val router = LocalRouter.current
    Row(Modifier.pressable { router.open(route) }.pulseCard(padding = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconBadge(sfIcon(icon), tint, 34.dp, 15.dp)
                Text(title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            }
            Text(value, color = Palette.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 2)
        }
        trailing()
        Icon(sfIcon("chevron.right"), null, tint = Palette.textTertiary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun RowScope.Spark(values: List<DailyValue>, color: Color) {
    Sparkline(values, Modifier.width(76.dp).height(40.dp), color)
}

@Composable
private fun BodySection() {
    val core = observeCore()
    val now = Clock.System.now()
    val night = core.sleep.displayNight(now)
    val totals = core.nutrition.totals(now)
    val sleepWeek = (6 downTo 0).mapNotNull { offset ->
        val day = now.startOfDay().addingDays(-offset)
        core.sleep.minutes(day)?.let { DailyValue(day, it / 60.0) }
    }
    val screen = core.screenTime
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StateMetricCard("moon", Palette.purple, "Сон",
            if (core.sleep.isSleeping) "Сон активен" else night?.let { Formatters.duration(it.totalMinutes) } ?: "Нет данных",
            "Среднее за неделю ${Formatters.duration(core.sleep.averageMinutes(7, now) ?: 0)}", Route.Sleep) { Spark(sleepWeek, Palette.purple) }
        StateMetricCard("figure.run", Palette.orange, "Фитнес",
            "${core.fitness.activeMinutesToday(now)} / ${core.fitness.goals.activeMinutes} мин",
            "Активные минуты · ${Formatters.grouped(core.fitness.steps)} шагов", Route.Fitness) { Spark(core.fitness.weeklyActiveMinutes(now), Palette.orange) }
        StateMetricCard("iphone", Palette.cyan, "Экранное время", Formatters.duration(screen.todayMinutes(now)),
            "Лимит ${screen.state.value.dailyLimitMinutes / 60} ч · ${(screen.limitProgress(now) * 100).roundToIntSwift()}%", Route.ScreenTime) {
            Spark(screen.week(now).map { DailyValue(it.date, it.minutes / 60.0) }, Palette.cyan)
        }
        StateMetricCard("fork.knife", Palette.green, "Питание",
            "${Formatters.grouped(totals.kcal)} / ${Formatters.grouped(core.nutrition.goals.kcal)} ккал",
            "Б ${totals.protein} · Ж ${totals.fat} · У ${totals.carbs} г · вода ${Formatters.liters(core.nutrition.water(now))} л", Route.Nutrition) {
            Spark(core.nutrition.dailyCalories(7, now), Palette.green)
        }
    }
}

@Composable
private fun TestsSection() {
    val core = observeCore()
    val now = Clock.System.now()
    val scores = core.cognitive.dailyScores(7, now)
    val filled = core.wellbeing.filledSlots(now)
    val latestGame = core.cognitive.gameResults.firstOrNull()
    val balance = core.wellbeing.latestBalance
    PulseSection("Ежедневно") {
        StateMetricCard("brain.head.profile", Palette.green, "Когнитивные тесты", scores.lastOrNull()?.let { "${it.value.toInt()} баллов" } ?: "Нет данных",
            if (core.cognitive.completedToday(now)) "Батарея пройдена сегодня" else "Пройди батарею сегодня", Route.Cognitive) { Spark(scores, Palette.green) }
        StateMetricCard("face.smiling", Palette.yellow, "Настроение и энергия",
            core.wellbeing.averageMood(now)?.let { "${Formatters.decimal(it.mood)} · ${Formatters.decimal(it.energy)}" } ?: "—",
            "Заполнено ${filled.size} из 3", Route.Mood) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DaySlot.entries.forEach { slot ->
                    Box(Modifier.width(8.dp).height(26.dp).clip(CircleShape).background(if (slot in filled) Palette.cyan else Palette.track))
                }
            }
        }
        StateMetricCard("gamecontroller", Palette.cyan, "Игровые тесты", latestGame?.let { "Готовность ${it.readiness}/10" } ?: "Нет данных",
            latestGame?.phase?.title ?: "До и после тренировки", Route.GameTests)
    }
    PulseSection("Еженедельно") {
        StateMetricCard("circle.hexagongrid", Palette.pink, "Колесо баланса", balance?.let { "${Formatters.decimal(it.average)} из 10" } ?: "Не заполнено",
            balance?.let { "Последнее: ${Formatters.dayMonth(it.day)}" } ?: "8 сфер жизни", Route.Balance)
        StateMetricCard("checklist", Palette.textSecondary, "Еженедельные тесты", "Скоро", "Анкеты из веб-версии — с подключением API", Route.Tests)
    }
}

@Composable
private fun GameSection() {
    val core = observeCore()
    val router = LocalRouter.current
    val recent = core.game.recentMatches(core.session.user.value, 3)
    if (!core.game.isConnected) FaceitPromptCard()
    if (core.game.hasData) {
        GameSummaryCard { router.open(Route.GameStats) }
        PulseSection("Последние матчи", "Все", { router.open(Route.GameStats) }) {
            Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                recent.forEachIndexed { index, match ->
                    MatchRow(match)
                    if (index < recent.size - 1) DividerLine()
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
fun MatchRow(match: GameMatch) {
    val tint = if (match.won) Palette.green else Palette.red
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 4.dp, height = 38.dp).clip(CircleShape).background(tint))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${match.details?.mapName?.removePrefix("de_")?.replaceFirstChar { it.uppercase() } ?: match.map.title} · ${match.score}",
                color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("${Formatters.dayMonthShort(match.date)}, ${Formatters.time(match.date)} · ${match.kills}/${match.deaths}/${match.assists} · ADR ${match.adr.roundToIntSwift()}",
                color = Palette.textSecondary, fontSize = 13.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("K/D ${Formatters.decimal(match.kd, 2)}", color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (match.eloDelta != 0) Text("${Formatters.signed(match.eloDelta)} ELO", color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
