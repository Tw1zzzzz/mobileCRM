package com.pulse.android.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.GradientBar
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.HeaderIconButton
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.ScoreRing
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.Sparkline
import com.pulse.android.ui.components.fit
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.AppTab
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Gradients
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.demo.demo
import com.pulse.core.model.FormIndex
import com.pulse.core.model.TeamWeeklyBuilder
import com.pulse.core.model.GameInsight
import com.pulse.core.model.GameSummary
import com.pulse.core.model.GoalMath
import com.pulse.core.model.GoalProgress
import com.pulse.core.model.UserRole
import com.pulse.core.model.WeekRange
import com.pulse.core.model.WeeklyReportBuilder
import com.pulse.core.util.Formatters
import com.pulse.core.util.addingDays
import com.pulse.core.util.localDateTime
import com.pulse.core.util.roundToIntSwift
import com.pulse.core.util.startOfDay
import kotlin.time.Clock

/** «Сегодня» — дашборд игрока: форма дня → игра → восстановление → вывод → неделя → план на сегодня. */
@Composable
fun TodayScreen(onNotifications: () -> Unit) {
    val core = observeCore()
    val router = LocalRouter.current
    val now = Clock.System.now()
    val user = core.session.user.value
    val night = core.sleep.displayNight(now)
    ScreenScaffold(spacing = 22.dp, header = { TopPanel(onNotifications) }) {
        Greeting(user?.nickname ?: "игрок")
        if (!core.survey.isComplete) SurveyReminderCard()
        formIndex()?.let { FormDayCard(it) { router.open(Route.Statistics, AppTab.TODAY) } }
        PulseSection("Игра") {
            if (core.game.hasData) GameSummaryCard { router.open(Route.GameStats, AppTab.TODAY) } else FaceitPromptCard()
        }
        PulseSection("Восстановление") {
            val fitness = core.fitness
            val nutrition = core.nutrition
            val screen = core.screenTime
            val kcal = nutrition.totals(now).kcal
            TileGrid(
                listOf(
                    Tile("Сон", "moon", Palette.purple,
                        night?.let { Formatters.duration(it.totalMinutes) } ?: "—",
                        if (core.sleep.isSleeping) "Сон активен" else night?.let { "Оценка ${it.score}" } ?: "Нет данных",
                        (night?.score ?: 0) / 100.0) { router.open(Route.Sleep, AppTab.TODAY) },
                    Tile("Активность", "flame", Palette.orange, "${fitness.activeMinutesToday(now)} мин", "цель ${fitness.goals.activeMinutes} мин",
                        fitness.activeMinutesToday(now).toDouble() / fitness.goals.activeMinutes) { router.open(Route.Fitness, AppTab.TODAY) },
                    Tile("Экран", "iphone", Palette.cyan, Formatters.duration(screen.todayMinutes(now)),
                        "лимит ${screen.state.value.dailyLimitMinutes / 60} ч", screen.limitProgress(now)) { router.open(Route.ScreenTime, AppTab.TODAY) },
                    Tile("Питание", "fork.knife", Palette.green, "$kcal ккал", "из ${nutrition.goals.kcal}",
                        kcal.toDouble() / nutrition.goals.kcal) { router.show(listOf(Route.Nutrition), AppTab.STATE) }
                )
            )
        }
        val matches = core.game.matches(user)
        GameInsight.sleepVersusKD(matches) { day -> core.sleep.minutes(day) }?.let { insight ->
            InsightCard(insight) { router.open(Route.Statistics, AppTab.TODAY) }
        }
        PulseSection("Неделя") { WeeklySummaryCard() }
        PulseSection("План на сегодня") { PlanCard() }
    }
}

@Composable
private fun formIndex(): FormIndex.Result? {
    val core = observeCore()
    val today = Clock.System.now()
    val mood = core.wellbeing.averageMood(today) ?: core.wellbeing.averageMood(today.addingDays(-1))
    val cognitive = core.cognitive.averageScore(today) ?: core.cognitive.dailyScores(7, today).lastOrNull()?.value
    return FormIndex.compute(
        sleepMinutes = core.sleep.minutes(today) ?: core.sleep.displayNight(today)?.totalMinutes,
        energy = mood?.energy,
        mood = mood?.mood,
        cognitive = cognitive,
        fatigue = core.cognitive.gameResults.firstOrNull()?.fatigue?.toDouble()
    )
}

@Composable
private fun Greeting(name: String) {
    val hour = Clock.System.now().localDateTime().hour
    val word = when (hour) {
        in 5..11 -> "Доброе утро"
        in 12..17 -> "Добрый день"
        else -> "Добрый вечер"
    }
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Formatters.dayMonthWeekday(Clock.System.now()).uppercase(), color = Palette.cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
        Text("$word, $name", color = Palette.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Hero card: composite «Форма дня» with its three main contributors. */
@Composable
fun FormDayCard(result: FormIndex.Result, onClick: () -> Unit) {
    Row(Modifier.pressable(onClick = onClick).pulseCard(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        ScoreRing(result.score / 100.0, "${result.score}", "ФОРМА ДНЯ", size = 112.dp, lineWidth = 11.dp, valueSize = 38.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(result.label, color = Palette.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            result.components.take(3).forEach { component ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(component.title, color = Palette.textSecondary, autoSize = fit(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${component.value.roundToIntSwift()}", color = Palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    GradientBar(component.value / 100, height = 5.dp)
                }
            }
        }
    }
}

@Composable
fun GameSummaryCard(onClick: () -> Unit) {
    val core = observeCore()
    val user = core.session.user.value
    val player = core.game.profile(user)
    val history = core.game.eloHistory(user, 14)
    val weekStart = Clock.System.now().startOfDay().addingDays(-7)
    val weekAgo = history.firstOrNull { it.date >= weekStart }?.value ?: player.elo.toDouble()
    val delta = player.elo - weekAgo.toInt()
    val summary = GameSummary.of(core.game.recentMatches(user, 10))
    Column(Modifier.pressable(onClick = onClick).pulseCard(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ELO FACEIT", color = Palette.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
                Text(Formatters.grouped(player.elo), color = Palette.textPrimary, autoSize = fit(36.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                DeltaLabel(delta, "за 7 дн.")
            }
            Sparkline(history, Modifier.width(120.dp).height(52.dp), if (delta >= 0) Palette.cyan else Palette.orange)
        }
        GoalMath.progress(core.gameGoals.goals, player.elo, summary).firstOrNull { it.metric == GoalProgress.Metric.ELO }?.let { goal ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(sfIcon("target"), null, tint = Palette.cyan, modifier = Modifier.size(14.dp))
                    Text(" Цель ${goal.target}", color = Palette.cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(goal.note, color = if (goal.isReached) Palette.green else Palette.textSecondary, fontSize = 13.sp)
                }
                GradientBar(goal.progress, height = 6.dp)
            }
        }
        DividerLine()
        Text("Последние 10 матчей", color = Palette.textTertiary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Stat("K/D", Formatters.decimal(summary.kd, 2))
            Stat("ADR", Formatters.decimal(summary.adr))
            Stat("Win-rate", "${summary.winRate.roundToIntSwift()}%")
            Stat("Матчи", "${summary.matches}")
        }
    }
}

@Composable
private fun Stat(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(title, color = Palette.textSecondary, fontSize = 12.sp)
    }
}

@Composable
fun DeltaLabel(value: Int, suffix: String) {
    val color = when {
        value > 0 -> Palette.green
        value < 0 -> Palette.orange
        else -> Palette.textSecondary
    }
    Text("${if (value > 0) "▲" else if (value < 0) "▼" else "•"} ${Formatters.signed(value)} $suffix", color = color, autoSize = fit(13.sp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun FaceitPromptCard() {
    val core = observeCore()
    val router = LocalRouter.current
    Column(Modifier.pulseCard(padding = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFFF5500)), contentAlignment = Alignment.Center) {
                Icon(sfIcon("flame.fill"), null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Подключи Faceit", color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (core.game.state.value.isDemo) "ELO, уровень, матчи, K/D, ADR, хедшоты, клатчи и статистика по картам. Сейчас показаны демо-данные."
                    else "ELO, уровень, матчи, K/D, ADR, хедшоты, клатчи и статистика по картам подтянутся автоматически.",
                    color = Palette.textSecondary, fontSize = 14.sp
                )
            }
        }
        GradientButton("Подключить", icon = sfIcon("link")) { router.open(Route.GameStats, AppTab.TODAY) }
    }
}

private data class Tile(val title: String, val icon: String, val tint: Color, val value: String, val caption: String, val progress: Double, val onClick: () -> Unit)

@Composable
private fun TileGrid(tiles: List<Tile>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile ->
                    Column(
                        Modifier.weight(1f).pressable(onClick = tile.onClick).pulseCard(16.dp, Metrics.tileRadius),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(sfIcon(tile.icon), null, tint = tile.tint, modifier = Modifier.size(18.dp))
                            Text(" ${tile.title}", color = Palette.textSecondary, autoSize = fit(15.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(sfIcon("chevron.right"), null, tint = Palette.textTertiary, modifier = Modifier.size(16.dp))
                        }
                        Text(tile.value, color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        GradientBar(tile.progress, height = 6.dp)
                        Text(tile.caption, color = Palette.textTertiary, autoSize = fit(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** One-sentence insight linking recovery to game results. */
@Composable
private fun InsightCard(insight: GameInsight.Result, onClick: () -> Unit) {
    Row(Modifier.pressable(onClick = onClick).pulseCard(padding = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(sfIcon("sparkles"), if (insight.isPositive) Palette.cyan else Palette.orange, size = 42.dp, iconSize = 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("СОН И ИГРА", color = Palette.section, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
            Text(insight.text, color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Icon(sfIcon("chevron.right"), null, tint = Palette.textTertiary, modifier = Modifier.size(16.dp).padding(top = 4.dp))
    }
}

/** «Итоги недели» card (players) and «Сводка команды» (staff). */
@Composable
fun WeeklySummaryCard() {
    val core = observeCore()
    val router = LocalRouter.current
    val week = WeekRange.containing(Clock.System.now())
    val isStaff = core.session.isStaff
    val subtitle = if (isStaff) {
        val report = TeamWeeklyBuilder.demo(week)
        "${week.title} · ${report.readiness?.let { "готовность ${Formatters.decimal(it)}" } ?: "нет данных"} · рисков: ${report.risks.size}"
    } else {
        val report = WeeklyReportBuilder.build(core.weeklyInputs(), week)
        report.score?.let { score ->
            "${week.title} · неделя $score${report.previousScore?.let { " (${Formatters.signed(score - it)})" } ?: ""}"
        } ?: "${week.title} · сравнение с прошлой неделей"
    }
    Row(Modifier.pressable { router.open(Route.WeeklyReport) }.pulseCard(padding = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(sfIcon("calendar.badge.checkmark"), Palette.cyan, size = 46.dp, iconSize = 20.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (isStaff) "Сводка команды за неделю" else "Итоги недели", color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Palette.textSecondary, autoSize = fit(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(sfIcon("chevron.right"), null, tint = Palette.textTertiary, modifier = Modifier.size(16.dp))
    }
}

/** «Заполни анкету» / «Пройди тест» — opens the questionnaire full-screen. */
@Composable
fun SurveyReminderCard() {
    val core = observeCore()
    val router = LocalRouter.current
    val needsSurvey = core.survey.survey == null
    Row(Modifier.pressable { router.showsSurvey = true }.pulseCard(padding = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(sfIcon(if (needsSurvey) "person.text.rectangle" else "brain.head.profile"), if (needsSurvey) Palette.cyan else Palette.purple, 46.dp, 20.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (needsSurvey) "Заполни анкету игрока" else "Пройди тест «Игровой характер»", color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(if (needsSurvey) "Цели, роль и игровой характер — 5 минут" else "24 утверждения, около 3 минут", color = Palette.textSecondary, fontSize = 13.sp)
        }
        Icon(sfIcon("chevron.right"), null, tint = Palette.textTertiary, modifier = Modifier.size(16.dp))
    }
}

/** Day checklist: cognitive battery, mood slots and the next event. */
@Composable
private fun PlanCard() {
    val core = observeCore()
    val router = LocalRouter.current
    val now = Clock.System.now()
    val filled = core.wellbeing.filledSlots(now).size
    val batteryDone = core.cognitive.completedToday(now)
    val next = core.calendar.events(now, null).firstOrNull { it.end > now }
    Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
        PlanRow("brain.head.profile", Palette.green, "Когнитивная батарея", if (batteryDone) "Пройдена" else "5 тестов · ~4 мин", batteryDone) {
            router.open(Route.Cognitive, AppTab.TODAY)
        }
        DividerLine()
        PlanRow("face.smiling", Palette.yellow, "Настроение и энергия", "Заполнено $filled из 3", filled == 3) { router.open(Route.Mood, AppTab.TODAY) }
        DividerLine()
        if (next != null) {
            PlanRow("calendar", Palette.cyan, next.title, "${Formatters.time(next.start)} – ${Formatters.time(next.end)}", false) {
                router.open(Route.Event(next.id), AppTab.TODAY)
            }
        } else {
            PlanRow("calendar", Palette.cyan, "Событий больше нет", "Открыть календарь", true) { router.open(Route.Calendar(), AppTab.TODAY) }
        }
    }
}

@Composable
private fun PlanRow(icon: String, tint: Color, title: String, detail: String, isDone: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClick = onClick).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(sfIcon(icon), tint, 40.dp, 17.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = Palette.textSecondary, fontSize = 13.sp)
        }
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .then(if (isDone) Modifier.background(Palette.green) else Modifier.border(2.dp, Palette.textTertiary, CircleShape)),
            contentAlignment = Alignment.Center
        ) { if (isDone) Icon(sfIcon("checkmark"), null, tint = Palette.background, modifier = Modifier.size(16.dp)) }
    }
}

/** Status on the left; streak, calendar, notifications and account on the right. */
@Composable
fun TopPanel(onNotifications: () -> Unit) {
    val core = observeCore()
    val router = LocalRouter.current
    val user = core.session.user.value
    val now = Clock.System.now()
    val unread = com.pulse.core.feature.NotificationInbox.visible(core.inboxItems(now), rememberDismissed().value).size
    val hasEventsToday = core.calendar.events(now, null).isNotEmpty()
    var menu by remember { mutableStateOf(false) }
    val size = 42.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        user?.role?.let { RoleBadge(it) }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.height(size).clip(RoundedCornerShape(Metrics.buttonRadius)).background(Palette.card)
                .border(1.dp, Palette.stroke, RoundedCornerShape(Metrics.buttonRadius)).padding(horizontal = 10.dp)
                .semantics { contentDescription = "Серия заполнения: ${core.streakDays(now)} дней" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(sfIcon("flame.fill"), null, tint = Palette.orange, modifier = Modifier.size(18.dp))
            Text("${core.streakDays(now)}", color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Box {
            HeaderIconButton("calendar", if (hasEventsToday) "Календарь, есть события сегодня" else "Календарь", size) { router.open(Route.Calendar(), AppTab.TODAY) }
            if (hasEventsToday) Box(Modifier.align(Alignment.TopEnd).offset(2.dp, (-2).dp).size(9.dp).clip(CircleShape).background(Palette.cyan))
        }
        Box {
            HeaderIconButton("bell", "Уведомления", size, onNotifications)
            if (unread > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).widthIn(min = 18.dp).height(18.dp).clip(CircleShape).background(Palette.red),
                    contentAlignment = Alignment.Center
                ) { Text("$unread", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Box {
            Box(
                Modifier.size(size).clip(CircleShape).background(Gradients.accentDiagonal).pressable { menu = true }.semantics { contentDescription = "Аккаунт" },
                contentAlignment = Alignment.Center
            ) { Text(user?.initials ?: "?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Профиль") }, onClick = { menu = false; router.select(AppTab.PROFILE) })
                DropdownMenuItem(text = { Text("Выйти", color = Palette.red) }, onClick = { menu = false; core.logout() })
            }
        }
    }
}

@Composable
fun RoleBadge(role: UserRole, compact: Boolean = true) {
    val tint = when (role) {
        UserRole.STAFF -> Palette.orange
        UserRole.TEAM_PLAYER -> Palette.cyan
        UserRole.SOLO_PLAYER -> Palette.purple
    }
    Row(
        Modifier.clip(CircleShape).background(tint.copy(alpha = 0.15f)).border(1.dp, tint.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 6.dp else 7.dp)
            .semantics { contentDescription = "Статус профиля: ${role.title}" },
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(sfIcon(role.badgeIcon), null, tint = tint, modifier = Modifier.size(14.dp))
        Text(role.badgeTitle, color = tint, fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.Bold)
    }
}
