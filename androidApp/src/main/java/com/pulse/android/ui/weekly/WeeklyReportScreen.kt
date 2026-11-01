package com.pulse.android.ui.weekly

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.components.GradientBar
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.ScoreRing
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.fit
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.staff.MINUTES_PER_HOUR
import com.pulse.android.ui.staff.NO_DATA
import com.pulse.android.ui.staff.RealTeamScreenContent
import com.pulse.android.ui.staff.StaffTeamRefresh
import com.pulse.android.ui.staff.TeamLoadingState
import com.pulse.android.ui.staff.playerRoute
import com.pulse.android.ui.staff.readinessTint
import com.pulse.android.ui.staff.readinessTitle
import com.pulse.android.ui.staff.rememberTeamRefresh
import com.pulse.android.ui.staff.teamDay
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.color
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.demo.demo
import com.pulse.core.feature.StaffMetrics
import com.pulse.core.feature.WeeklyShareText
import com.pulse.core.model.TeamWeeklyBuilder
import com.pulse.core.model.TeamWeeklyReport
import com.pulse.core.model.TeamWeeklyRow
import com.pulse.core.model.WeekRange
import com.pulse.core.model.WeeklyGoalCheck
import com.pulse.core.model.WeeklyMetric
import com.pulse.core.model.WeeklyReport
import com.pulse.core.model.WeeklyReportBuilder
import com.pulse.core.staff.StaffViews
import com.pulse.core.staff.TeamSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.pulse.core.util.Formatters
import com.pulse.core.util.addingDays
import com.pulse.core.util.roundToIntSwift
import kotlin.time.Clock

/** «Итоги недели»: personal report for players, team summary for staff. */
@Composable
fun WeeklyReportScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val isStaff = core.session.isStaff
    var week by remember { mutableStateOf(WeekRange.containing(Clock.System.now())) }
    val realTeam = isStaff && !core.showsTeamDemo
    LaunchedEffect(realTeam) { if (realTeam) StaffTeamRefresh.startIfOld(core) }
    val refresh = rememberTeamRefresh().takeIf { realTeam }
    ScreenScaffold(spacing = 22.dp, isRefreshing = refresh?.isRefreshing ?: false, onRefresh = refresh?.refresh, header = {
        NavHeader(
            if (isStaff) "Сводка команды" else "Итоги недели",
            if (isStaff) core.session.user.value?.team ?: "Команда" else "Сравнение с прошлой неделей",
            onBack = { router.pop() }
        )
    }) {
        if (realTeam) {
            // A real staff account: the downloaded team, a few weeks back.
            val earliest = WeekRange.containing(Clock.System.now().addingDays(-(REAL_WEEKS_BACK - 1) * 7))
            RealTeamScreenContent { loaded ->
                WeekNavigator(week, earliest) { week = it }
                RealTeamWeeklyContent(loaded.team, week)
            }
            return@ScreenScaffold
        }
        // The staff demo keeps four weeks of team data.
        val earliest = if (isStaff) WeekRange.containing(Clock.System.now().addingDays(-27)) else null
        WeekNavigator(week, earliest) { week = it }
        if (isStaff) TeamWeeklyContent(TeamWeeklyBuilder.demo(week)) else PersonalWeeklyContent(WeeklyReportBuilder.build(core.weeklyInputs(), week))
    }
}

@Composable
private fun WeekNavigator(week: WeekRange, earliest: WeekRange?, onChange: (WeekRange) -> Unit) {
    val canGoBack = earliest?.let { week.start > it.start } ?: true
    val canGoForward = !week.isCurrent()
    Row(Modifier.pulseCard(padding = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Arrow("chevron.left", "Предыдущая неделя", canGoBack) { onChange(week.previous) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(week.title, color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(if (week.isCurrent()) "Текущая неделя" else "Завершённая неделя", color = Palette.textSecondary, fontSize = 12.sp)
        }
        Arrow("chevron.right", "Следующая неделя", canGoForward) { onChange(week.next) }
    }
}

@Composable
private fun Arrow(icon: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(Metrics.minTouchTarget).pressable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(sfIcon(icon), description, tint = if (enabled) Palette.cyan else Palette.textTertiary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PersonalWeeklyContent(report: WeeklyReport) {
    if (report.isEmpty) {
        EmptyStateView("calendar.badge.clock", "За эту неделю записей нет", "Отмечай сон, настроение, тесты и еду — в воскресенье здесь появятся итоги.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.pulseCard(16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            ScoreRing((report.score ?: 0) / 100.0, report.score?.toString() ?: "—", "НЕДЕЛЯ", 116.dp, 11.dp, 40.sp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(WeeklyShareText.verdict(report.score), color = Palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                val score = report.score
                val previous = report.previousScore
                if (score != null && previous != null) {
                    val delta = score - previous
                    Text("${if (delta >= 0) "↗" else "↘"} ${Formatters.signed(delta)} к прошлой неделе", color = if (delta >= 0) Palette.green else Palette.orange,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("Записи в ${report.activeDays} из ${report.elapsedDays} дн.", color = Palette.textSecondary, fontSize = 13.sp)
            }
        }
        PulseSection("Показатели") {
            Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                report.metrics.forEachIndexed { index, metric ->
                    MetricRow(metric)
                    if (index < report.metrics.size - 1) DividerLine()
                }
            }
        }
        if (report.wins.isNotEmpty()) PulseSection("Получилось") { BulletCard(report.wins, "arrow.up.right.circle.fill", Palette.green) }
        if (report.concerns.isNotEmpty()) PulseSection("Обратить внимание") { BulletCard(report.concerns, "exclamationmark.circle.fill", Palette.orange) }
        PulseSection("Цели недели") { GoalsCard(report.goals) }
        PulseSection("На следующую неделю") { BulletCard(report.tips, "lightbulb.fill", Palette.yellow) }
        ShareButton("Поделиться итогами", WeeklyShareText.personal(report))
    }
}

@Composable
private fun MetricRow(metric: WeeklyMetric) {
    val color = when (metric.trend) {
        WeeklyMetric.Trend.BETTER -> Palette.green
        WeeklyMetric.Trend.WORSE -> Palette.orange
        else -> Palette.textSecondary
    }
    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(sfIcon(metric.icon), metric.tint.color, 38.dp, 16.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(metric.title, color = Palette.textSecondary, fontSize = 13.sp)
            Text(metric.value, color = Palette.textPrimary, autoSize = fit(17.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(metric.detail, color = Palette.textTertiary, autoSize = fit(12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        metric.change?.let {
            Text(it, color = color, autoSize = fit(13.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun GoalsCard(goals: List<WeeklyGoalCheck>) {
    Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        goals.forEach { goal ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(goal.title, color = Palette.textPrimary, autoSize = fit(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${goal.achieved} из ${goal.total}", color = if (goal.progress >= 0.7) Palette.green else Palette.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                GradientBar(goal.progress, height = 6.dp)
            }
        }
    }
}

@Composable
private fun BulletCard(items: List<String>, icon: String, tint: Color) {
    Column(Modifier.fillMaxWidth().pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(sfIcon(icon), null, tint = tint, modifier = Modifier.size(17.dp).padding(top = 1.dp))
                Text(item, color = Palette.textPrimary, fontSize = 14.sp)
            }
        }
    }
}

/** System share sheet with the plain-text report (iOS `ShareLink`). */
@Composable
private fun ShareButton(title: String, text: String) {
    val context = LocalContext.current
    Row(
        Modifier.pressable {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, title))
        }.pulseCard(14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(sfIcon("square.and.arrow.up"), null, tint = Palette.cyan, modifier = Modifier.size(18.dp))
        Text("  $title", color = Palette.cyan, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TeamWeeklyContent(report: TeamWeeklyReport) {
    val router = LocalRouter.current
    val winDelta = report.winRate?.let { now -> report.previousWinRate?.let { "${Formatters.signed((now - it).roundToIntSwift())}%" } }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("Готовность", report.readiness?.let { "${Formatters.decimal(it)} / 10" } ?: "—",
                    report.readinessDelta?.let { Formatters.signedDecimal(it) }, (report.readinessDelta ?: 0.0) >= 0, Modifier.weight(1f))
                Kpi("Средний сон", report.sleepMinutes?.let { Formatters.duration(it) } ?: "—",
                    report.sleepDelta?.let { "${Formatters.signed(it)} мин" }, (report.sleepDelta ?: 0) >= 0, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("Матчи · победы", "${report.matches} · ${report.winRate?.let { "${it.roundToIntSwift()}%" } ?: "—"}",
                    winDelta, (report.winRate ?: 0.0) >= (report.previousWinRate ?: 0.0), Modifier.weight(1f))
                Kpi("В зоне риска", "${report.rows.count { it.risks.isNotEmpty() }} из ${report.rows.size}", null, report.risks.isEmpty(), Modifier.weight(1f))
            }
        }
        if (report.highlights.isNotEmpty()) PulseSection("Главное за неделю") { BulletCard(report.highlights, "star.fill", Palette.yellow) }
        if (report.risks.isNotEmpty()) PulseSection("Требуют внимания") { BulletCard(report.risks, "exclamationmark.triangle.fill", Palette.orange) }
        PulseSection("По игрокам") {
            Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                report.rows.forEachIndexed { index, row ->
                    Box(Modifier.pressable { router.open(Route.Player(row.id)) }) { PlayerRow(row) }
                    if (index < report.rows.size - 1) DividerLine()
                }
            }
        }
        PulseSection("Рекомендации") { BulletCard(report.tips, "lightbulb.fill", Palette.cyan) }
        ShareButton("Отправить сводку", WeeklyShareText.team(report))
        Text("Демо-данные команды. С сервером сводка будет строиться по данным игроков.", color = Palette.textTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun Kpi(title: String, value: String?, delta: String?, good: Boolean, modifier: Modifier) {
    Column(modifier.pulseCard(14.dp, Metrics.tileRadius), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Palette.textSecondary, autoSize = fit(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(value ?: NO_DATA, color = if (value == null) Palette.textTertiary else Palette.textPrimary, autoSize = fit(20.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(delta ?: " ", color = if (good) Palette.green else Palette.orange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PlayerRow(row: TeamWeeklyRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.nickname, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (row.risks.isNotEmpty()) Icon(sfIcon("exclamationmark.triangle.fill"), "Есть риски", tint = Palette.orange, modifier = Modifier.size(13.dp))
            }
            Text(listOfNotNull(row.sleepMinutes?.let { "сон ${Formatters.duration(it)}" }, "${row.matches} матч.", row.kd?.let { "K/D ${Formatters.decimal(it, 2)}" })
                .joinToString(" · "), color = Palette.textSecondary, autoSize = fit(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.readiness?.let { Formatters.decimal(it) } ?: "—", color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            row.readinessDelta?.let {
                Text(Formatters.signedDecimal(it), color = if (it >= 0) Palette.green else Palette.orange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        val status = row.load.status
        Text(status.title, color = status.tint.color, autoSize = fit(11.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.clip(CircleShape).background(status.tint.color.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 4.dp))
    }
}

// ------------------------------------------------------------------------------------------------ a real team

/** How many weeks back the real team report goes (the current one included). */
private const val REAL_WEEKS_BACK = 12

@Composable
private fun RealTeamWeeklyContent(team: TeamSnapshot, week: WeekRange) {
    val day = teamDay()
    val computed by produceState<StaffViews.WeeklyTeam?>(null, team, week, day.tz) {
        value = withContext(Dispatchers.Default) { StaffViews.weeklyTeam(team, week, day.tz) }
    }
    val report = computed
    if (report == null) {
        TeamLoadingState()
        return
    }
    val atRisk = report.rows.filter { it.risks.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("Готовность", report.readiness?.let { "${it.roundToInt()} / 100" }, report.readinessDelta?.let { Formatters.signed(it.roundToInt()) },
                    (report.readinessDelta ?: 0.0) >= 0, Modifier.weight(1f))
                Kpi("Средний сон", report.sleepHours?.let { Formatters.duration((it * MINUTES_PER_HOUR).roundToInt()) }, null, true, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("Настроение", report.mood?.let { "${Formatters.decimal(it)} / 10" }, null, true, Modifier.weight(1f))
                Kpi("В зоне риска", "${report.atRisk} из ${report.rows.size}", null, report.atRisk == 0, Modifier.weight(1f))
            }
        }
        if (atRisk.isNotEmpty()) PulseSection("Требуют внимания") {
            BulletCard(atRisk.map { "${it.nickname}: ${it.risks.joinToString(", ")}" }, "exclamationmark.triangle.fill", Palette.orange)
        }
        PulseSection("По игрокам") {
            Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                report.rows.forEachIndexed { index, row ->
                    RealPlayerRow(row)
                    if (index < report.rows.size - 1) DividerLine()
                }
            }
        }
        ShareButton("Отправить сводку", realShareText(week, report))
    }
}

@Composable
private fun RealPlayerRow(row: StaffViews.WeeklyRow) {
    val router = LocalRouter.current
    val route = playerRoute(row.id)
    val status = row.readiness?.let { StaffMetrics.readinessStatus(it) }
    val tint = readinessTint(status)
    val details = listOfNotNull(row.sleepHours?.let { "сон ${Formatters.duration((it * MINUTES_PER_HOUR).roundToInt())}" }, row.mood?.let { "настр. ${Formatters.decimal(it)}" })
    val open = if (route != null) Modifier.pressable { router.open(route) } else Modifier
    Row(open.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.nickname, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (row.risks.isNotEmpty()) Icon(sfIcon("exclamationmark.triangle.fill"), "Есть риски", tint = Palette.orange, modifier = Modifier.size(13.dp))
            }
            Text(if (details.isEmpty()) NO_DATA else details.joinToString(" · "), color = Palette.textSecondary, autoSize = fit(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (row.readiness == null) {
                Text(NO_DATA, color = Palette.textTertiary, fontSize = 13.sp)
            } else {
                Text("${row.readiness?.roundToInt()}", color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                row.readinessDelta?.let {
                    Text(Formatters.signed(it.roundToInt()), color = if (it >= 0) Palette.green else Palette.orange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (status != null) {
            Text(readinessTitle(status), color = tint, autoSize = fit(11.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.clip(CircleShape).background(tint.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 4.dp))
        }
    }
}

/** The plain-text summary for the system share sheet. */
private fun realShareText(week: WeekRange, report: StaffViews.WeeklyTeam): String = buildList {
    add("Pulse · сводка команды ${week.title}")
    report.readiness?.let { add("Готовность: ${it.roundToInt()}/100") }
    report.sleepHours?.let { add("Средний сон: ${Formatters.duration((it * MINUTES_PER_HOUR).roundToInt())}") }
    report.rows.filter { it.risks.isNotEmpty() }.forEach { add("⚠︎ ${it.nickname}: ${it.risks.joinToString(", ")}") }
}.joinToString("\n")
