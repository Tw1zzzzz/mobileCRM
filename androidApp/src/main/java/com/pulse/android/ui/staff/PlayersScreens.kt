package com.pulse.android.ui.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.components.SearchField
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
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.components.pulseTile
import com.pulse.android.ui.game.PlayerGameStats
import com.pulse.android.ui.health.MealRow
import com.pulse.android.ui.health.SleepHistoryList
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.onboarding.MindsetAdviceCard
import com.pulse.android.ui.onboarding.MindsetProfileCard
import com.pulse.android.ui.onboarding.SurveySummaryCard
import com.pulse.android.ui.state.MatchRow
import com.pulse.android.ui.statistics.CorrelationTable
import com.pulse.android.ui.theme.Gradients
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.color
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.demo.DemoData
import com.pulse.core.demo.GameData
import com.pulse.core.demo.PlayerSurveyDemo
import com.pulse.core.demo.TeamAnalytics
import com.pulse.core.demo.TeamFood
import com.pulse.core.model.BalanceCategory
import com.pulse.core.model.MindsetProfile
import com.pulse.core.model.PlayerProfile
import com.pulse.core.model.TrainingLoadMath
import com.pulse.core.util.Formatters
import com.pulse.core.util.SeededGenerator
import com.pulse.core.util.roundToIntSwift
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Initials in a gradient circle. */
@Composable
fun PlayerAvatar(nickname: String, size: Dp, fontSize: TextUnit) {
    Box(Modifier.size(size).clip(CircleShape).background(Gradients.accentDiagonal), contentAlignment = Alignment.Center) {
        Text(nickname.take(2).uppercase(), color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

/** Staff: the team roster with search. */
@Composable
fun PlayersListContent() {
    val router = LocalRouter.current
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim().lowercase()
    val players = if (trimmed.isEmpty()) DemoData.players
    else DemoData.players.filter { trimmed in it.nickname.lowercase() || trimmed in it.realName.lowercase() }
    val now = Clock.System.now()
    Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
        SearchField(query, "Поиск игрока", padding = 14.dp) { query = it }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (players.isEmpty()) EmptyStateView("person.3", "Никого не нашли", "Проверь ник или имя")
            players.forEach { player ->
                val snapshot = TeamAnalytics.snapshot(player)
                val archetype = PlayerSurveyDemo.archetype(player.nickname)
                Row(Modifier.pressable { router.open(Route.Player(player.id)) }.pulseCard(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerAvatar(player.nickname, 52.dp, 17.sp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(player.nickname, color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            if (snapshot.needsAttention) Icon(sfIcon("exclamationmark.triangle.fill"), "Требует внимания", tint = Palette.orange, modifier = Modifier.size(12.dp))
                        }
                        Text("${player.gameRole} · ${Formatters.relative(player.lastActive, now)}", color = Palette.textSecondary, autoSize = fit(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        archetype?.let {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(sfIcon(it.icon), null, tint = it.tint.color, modifier = Modifier.size(13.dp))
                                Text("${it.caption}: ${it.title.lowercase()}", color = it.tint.color, autoSize = fit(13.sp), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text("Готовность ${Formatters.decimal(snapshot.readiness)} · сон ${Formatters.duration(snapshot.sleepMinutes)}",
                            color = if (snapshot.needsAttention) Palette.orange else Palette.green, autoSize = fit(13.sp), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${player.elo}", color = Palette.cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("ELO", color = Palette.textTertiary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Standalone «Игроки» (route from other places). */
@Composable
fun PlayersScreen() {
    val router = LocalRouter.current
    ScreenScaffold(header = { NavHeader("Игроки", "Состав команды", onBack = { router.pop() }) }) { PlayersListContent() }
}

/** Staff view of one player: everything the coach needs on one screen. */
@Composable
fun PlayerDetailScreen(id: Uuid) {
    val core = observeCore()
    val router = LocalRouter.current
    val player = DemoData.players.firstOrNull { it.id == id }
    var period by remember { mutableIntStateOf(30) }
    var testType by remember { mutableStateOf("Все тесты") }
    var source by remember { mutableStateOf("Все") }
    ScreenScaffold(header = {
        NavHeader(player?.nickname ?: "Игрок", player?.let { "${it.realName} · ${it.gameRole}" }, onBack = { router.pop() })
    }) {
        if (player == null) {
            EmptyStateView("person.crop.circle.badge.questionmark", "Игрок не найден", "")
            return@ScreenScaffold
        }
        Row(Modifier.pulseCard(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerAvatar(player.nickname, 68.dp, 24.sp)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(player.nickname, color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Роль: ${player.gameRole}", color = Palette.textSecondary, fontSize = 15.sp)
                Text("Последняя активность: ${Formatters.dayMonthTime(player.lastActive)}", color = Palette.textTertiary, fontSize = 13.sp)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30, 90).forEach { days -> ChipButton("$days дней", period == days) { period = days } }
            FilterMenu(testType, listOf("Все тесты", "Когнитивные", "Игровые", "Настроение")) { testType = it }
            FilterMenu("Источник: $source", listOf("Все", "Приложение", "Уведомления", "Веб")) { source = it }
        }
        val snapshot = TeamAnalytics.snapshot(player, period)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryTile("Средний score", "${snapshot.averageScore}", Palette.cyan, Modifier.weight(1f))
                SummaryTile("Индекс состояния", Formatters.decimal(snapshot.readiness), Palette.green, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryTile("Фокус high", "${snapshot.focusHighDays} дн.", Palette.purple, Modifier.weight(1f))
                SummaryTile("Усталость high", "${snapshot.fatigueHighDays} дн.", Palette.orange, Modifier.weight(1f))
            }
        }
        StaffPlayerPortraitSection(player.nickname)
        PulseSection("Тренировочная нагрузка") { LoadCard(player) }
        PulseSection("Результаты тестов") { TestsCard(player, period) }
        PulseSection("Последние матчи") {
            val matches = GameData.matches(player).take(4)
            Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                matches.forEachIndexed { index, match ->
                    MatchRow(match)
                    if (index < matches.size - 1) DividerLine()
                }
            }
        }
        PulseSection("Игровая статистика · $period дней") { PlayerGameStats(GameData.stats(player, period)) }
        PulseSection("Корреляционный анализ") { CorrelationTable(TeamAnalytics.correlations(player)) }
        PulseSection("Колесо баланса") {
            val rng = remember(player.id) { SeededGenerator(player.seed + 7uL) }
            val values = remember(player.id) { BalanceCategory.entries.map { rng.int(4..9).toDouble() } }
            Box(Modifier.pulseCard(12.dp)) { RadarChart(BalanceCategory.entries.map { it.shortTitle }, values, height = 290.dp) }
        }
        PulseSection("Дневник сна") { SleepHistoryList(DemoData.sleepSessions(player).sortedByDescending { it.start }.take(minOf(period, 7))) }
        PulseSection("Дневник питания") {
            // Same source as «Питание игроков»: real logged meals with photos, demo meals otherwise.
            val meals = TeamFood.entries(core.nutrition.meals, period).filter { it.player == player.nickname }.map { it.meal }.take(6)
            if (meals.isEmpty()) {
                EmptyStateView("fork.knife", "Нет записей", "Игрок не добавлял еду за период")
            } else {
                Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                    meals.forEachIndexed { index, meal ->
                        MealRow(meal)
                        if (index < meals.size - 1) DividerLine()
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterMenu(label: String, items: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(CircleShape).background(Palette.tile).border(1.dp, Palette.stroke, CircleShape).pressable { open = true }.padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Palette.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Icon(sfIcon("chevron.down"), null, tint = Palette.textSecondary, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            items.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { open = false; onSelect(item) }) }
        }
    }
}

@Composable
private fun SummaryTile(title: String, value: String, tint: Color, modifier: Modifier) {
    Column(modifier.pulseCard(14.dp, Metrics.tileRadius), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Palette.textSecondary, autoSize = fit(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = tint, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LoadCard(player: PlayerProfile) {
    val ratio = TrainingLoadMath.ratio(TeamAnalytics.dailyLoad(player))
    val color = ratio.status.tint.color
    Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ratio.value?.let { Formatters.decimal(it, 2) } ?: "—", color = color, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("ACWR", color = Palette.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(ratio.status.title, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 5.dp))
        }
        Text("За 7 дней ${Formatters.grouped(ratio.acute.toInt())} ед. · обычно ${Formatters.grouped(ratio.chronicWeekly.toInt())} ед. в неделю", color = Palette.textSecondary, fontSize = 13.sp)
        Text(ratio.status.advice, color = Palette.textSecondary, fontSize = 14.sp)
    }
}

/** Test score and the state index (×10) on one chart; hold to read a day. */
@Composable
private fun TestsCard(player: PlayerProfile, period: Int) {
    val scores = TeamAnalytics.scores(player, period)
    val readiness = TeamAnalytics.readiness(player, period)
    Column(Modifier.pulseCard(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend("Score", Palette.cyan)
            Legend("Индекс состояния ×10", Palette.green)
        }
        if (scores.size >= 2) {
            val aligned = readiness.size == scores.size
            PulseLineChart(
                scores.map { it.value },
                calloutFor = { i ->
                    Callout(Formatters.dayMonthShort(scores[i].date), listOfNotNull(
                        "Score ${scores[i].value.roundToIntSwift()}" to Palette.cyan,
                        readiness.getOrNull(i)?.let { "Индекс ${Formatters.decimal(it.value)}" to Palette.green }
                    ))
                },
                height = 160.dp, showsArea = false, lineWidth = 2.5.dp, lower = 0.0, upper = 100.0,
                secondary = if (aligned) readiness.map { it.value * 10 } else null, secondaryColor = Palette.green
            )
            val step = maxOf((scores.size + 3) / 4, 1)
            FractionLabels(scores.indices.filter { it % step == 0 }.map { i -> i / (scores.size - 1f) to Formatters.dayMonthShort(scores[i].date) })
        }
    }
}

/** Staff → player card: questionnaire, mindset radar and how to work with the player. */
@Composable
private fun StaffPlayerPortraitSection(nickname: String) {
    val state = PlayerSurveyDemo.state(nickname)
    val survey = state.survey
    val result = state.mindset.lastOrNull()
    if (survey != null && result != null) {
        val profile = MindsetProfile(result.scores)
        PulseSection("Профиль игрока") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SurveySummaryCard(survey)
                MindsetProfileCard(profile, "Тест «Игровой характер» от ${Formatters.dayMonth(result.date)} · самооценка игрока")
            }
        }
        PulseSection("Как работать с игроком") { MindsetAdviceCard(profile, forCoach = true) }
    } else {
        PulseSection("Профиль игрока") {
            Text("Игрок ещё не заполнил анкету.", color = Palette.textSecondary, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().pulseCard(16.dp))
        }
    }
}
