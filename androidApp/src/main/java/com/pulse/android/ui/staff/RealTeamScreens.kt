package com.pulse.android.ui.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.ScoreRing
import com.pulse.android.ui.components.SearchField
import com.pulse.android.ui.components.fit
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.AppTab
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.android.ui.today.WeeklySummaryCard
import com.pulse.core.feature.StaffMetrics
import com.pulse.core.staff.StaffViews
import com.pulse.core.util.Formatters
import kotlinx.datetime.TimeZone
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val NO_DATA = "нет данных"

/** The phone's zone and today's day in it: the zone and the day every staff number is read in. */
private class TeamDay(val today: String, val tz: String)

private fun teamDay(): TeamDay {
    val tz = TimeZone.currentSystemDefault().id
    return TeamDay(StaffMetrics.dayOf(Clock.System.now(), tz), tz)
}

private fun readinessTint(status: StaffMetrics.ReadinessStatus?): Color = when (status) {
    StaffMetrics.ReadinessStatus.READY -> Palette.green
    StaffMetrics.ReadinessStatus.DOUBT -> Palette.orange
    StaffMetrics.ReadinessStatus.RISK -> Palette.red
    null -> Palette.textTertiary
}

private fun readinessTitle(status: StaffMetrics.ReadinessStatus): String = when (status) {
    StaffMetrics.ReadinessStatus.READY -> "Готов"
    StaffMetrics.ReadinessStatus.DOUBT -> "Под вопросом"
    StaffMetrics.ReadinessStatus.RISK -> "Риск"
}

private fun playerRoute(id: String): Route? = runCatching { Uuid.parse(id) }.getOrNull()?.let { Route.Player(it) }

// ------------------------------------------------------------------------------------------------ «Сегодня»

/** «Сегодня» of a real staff account: the team's readiness, who needs attention, the weekly summary entry. */
@Composable
fun RealTeamOverview() {
    val view = observeCore().realTeamView()
    val players = view.withPlayers
    val day = teamDay()
    val overview = remember(players?.team, day.today) { players?.let { StaffViews.todayOverview(it.team, day.today, day.tz) } }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionSpacing)) {
        if (players == null || overview == null) TeamStateCard(view)
        else {
            StaleNote(players)
            RealTodayRing(overview)
        }
        WeeklySummaryCard()
        if (overview != null) RealAttention(overview.attention)
    }
}

@Composable
private fun RealTodayRing(overview: StaffViews.TodayOverview) {
    val readiness = overview.teamReadiness
    Row(Modifier.pulseCard(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        ScoreRing((readiness ?: 0) / 100.0, readiness?.toString() ?: "—", "ГОТОВНОСТЬ", 124.dp, 12.dp, 36.sp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Игроков", "${overview.playersCount}")
            Stat("Требуют внимания", "${overview.attention.size}", if (overview.attention.isEmpty()) Palette.green else Palette.orange)
            Stat("Сон за 7 дней", overview.overview.sleep.average?.let { Formatters.duration((it * MINUTES_PER_HOUR).roundToInt()) } ?: NO_DATA)
        }
    }
}

@Composable
private fun RealAttention(attention: List<StaffViews.AttentionItem>) {
    val router = LocalRouter.current
    PulseSection("Требуют внимания", "Аналитика", { router.select(AppTab.ANALYTICS) }) {
        if (attention.isEmpty()) {
            Text("Все игроки в норме 👍", color = Palette.textSecondary, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().pulseCard())
            return@PulseSection
        }
        Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
            attention.forEachIndexed { index, item ->
                val route = playerRoute(item.playerId)
                Row(
                    Modifier.fillMaxWidth().then(if (route != null) Modifier.pressable { router.open(route, AppTab.TODAY) } else Modifier).padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerAvatar(item.nickname, 40.dp, 14.sp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.nickname, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(item.text, color = Palette.orange, fontSize = 13.sp)
                    }
                    Icon(sfIcon("chevron.right"), null, tint = Palette.textTertiary, modifier = Modifier.size(14.dp))
                }
                if (index < attention.size - 1) DividerLine()
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ «Игроки»

/** The roster of a real staff account: every player of the team, best readiness first, with search. */
@Composable
fun RealRosterContent() {
    val view = observeCore().realTeamView()
    val loaded = view.withPlayers
    if (loaded == null) {
        TeamStateCard(view)
        return
    }
    val day = teamDay()
    val rows = remember(loaded.team, day.today) { StaffViews.roster(loaded.team, day.today, day.tz) }
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim().lowercase()
    val shown = if (trimmed.isEmpty()) rows else rows.filter { trimmed in it.nickname.lowercase() }
    val now = Clock.System.now()
    Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StaleNote(loaded)
            SearchField(query, "Поиск игрока", padding = 14.dp) { query = it }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (shown.isEmpty()) EmptyStateView("person.3", "Никого не нашли", "Проверь ник")
            shown.forEach { RealRosterRow(it, now) }
        }
    }
}

@Composable
private fun RealRosterRow(row: StaffViews.RosterRow, now: kotlin.time.Instant) {
    val router = LocalRouter.current
    val route = playerRoute(row.id)
    val open = if (route != null) Modifier.pressable { router.open(route) } else Modifier
    Column(open.pulseCard(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerAvatar(row.nickname, 52.dp, 17.sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.nickname, color = Palette.textPrimary, autoSize = fit(18.sp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Активность: ${row.lastActivity?.let { Formatters.relative(it, now) } ?: NO_DATA}",
                    color = Palette.textSecondary, autoSize = fit(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            ReadinessBadge(row.readiness, row.status)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RosterMetric("Сон", row.sleepHours?.let { Formatters.duration((it * MINUTES_PER_HOUR).roundToInt()) }, Modifier.weight(1f))
            RosterMetric("Настр.", row.mood?.let { Formatters.decimal(it) }, Modifier.weight(1f))
            RosterMetric("Когн.", row.cognitive?.let { "${it.roundToInt()}" }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReadinessBadge(readiness: Int?, status: StaffMetrics.ReadinessStatus?) {
    val tint = readinessTint(status)
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (readiness == null || status == null) {
            Text(NO_DATA, color = Palette.textTertiary, fontSize = 13.sp)
        } else {
            Text("$readiness", color = tint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                readinessTitle(status), color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.clip(CircleShape).background(tint.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun RosterMetric(label: String, value: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Palette.textTertiary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(
            value ?: NO_DATA, color = if (value == null) Palette.textTertiary else Palette.textPrimary,
            autoSize = fit(15.sp), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

private const val MINUTES_PER_HOUR = 60
