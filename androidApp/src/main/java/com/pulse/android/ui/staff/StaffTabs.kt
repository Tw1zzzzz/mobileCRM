package com.pulse.android.ui.staff

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.calendar.EventRow
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.PulseSegmented
import com.pulse.android.ui.components.ScoreRing
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.fit
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.AppTab
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.android.ui.today.TopPanel
import com.pulse.android.ui.today.WeeklySummaryCard
import com.pulse.core.demo.DemoData
import com.pulse.core.demo.TeamAnalytics
import com.pulse.core.model.EventTag
import com.pulse.core.util.Formatters
import com.pulse.core.util.StatisticsMath
import kotlin.time.Clock

/** Segmented section remembered between launches (iOS `@AppStorage`). */
@Composable
private fun <T : Enum<T>> rememberSection(key: String, values: Array<T>, default: T): Pair<T, (T) -> Unit> {
    val prefs = LocalContext.current.getSharedPreferences("pulse.settings", Context.MODE_PRIVATE)
    var section by remember { mutableStateOf(values.firstOrNull { it.name == prefs.getString(key, null) } ?: default) }
    return section to { value: T -> section = value; prefs.edit { putString(key, value.name) } }
}

private enum class PlayersSection(val title: String) { ROSTER("Состав"), FOOD("Питание") }
private enum class AnalyticsSection(val title: String) { TEAM("Команда"), CORRELATIONS("Корреляции") }

/** Staff «Игроки» tab: the roster and the food feed. */
@Composable
fun StaffPlayersTab() {
    val core = observeCore()
    val (section, select) = rememberSection("staff.players.section", PlayersSection.entries.toTypedArray(), PlayersSection.ROSTER)
    val scroll = rememberScrollState()
    LaunchedEffect(section) { scroll.scrollTo(0) }
    ScreenScaffold(spacing = 18.dp, scrollState = scroll, header = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NavHeader("Игроки", "${core.session.user.value?.team ?: "Команда"} · ${DemoData.players.size} в составе")
            PulseSegmented(PlayersSection.entries, section, { it.title }, height = 38.dp, onSelect = select)
        }
    }) {
        when (section) {
            PlayersSection.ROSTER -> PlayersListContent()
            PlayersSection.FOOD -> TeamFoodContent()
        }
    }
}

/** Staff «Аналитика» tab: team dashboard and correlations. */
@Composable
fun StaffAnalyticsTab() {
    val (section, select) = rememberSection("staff.analytics.section", AnalyticsSection.entries.toTypedArray(), AnalyticsSection.TEAM)
    val scroll = rememberScrollState()
    LaunchedEffect(section) { scroll.scrollTo(0) }
    ScreenScaffold(spacing = 22.dp, scrollState = scroll, header = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NavHeader("Аналитика", "Состояние и игра команды")
            PulseSegmented(AnalyticsSection.entries, section, { it.title }, height = 38.dp, onSelect = select)
        }
    }) {
        when (section) {
            AnalyticsSection.TEAM -> TeamAnalyticsContent()
            AnalyticsSection.CORRELATIONS -> TeamCorrelationsContent()
        }
    }
}

/** Staff «Сегодня»: team state, who needs attention, today's events. */
@Composable
fun StaffTodayScreen(onNotifications: () -> Unit) {
    val core = observeCore()
    val router = LocalRouter.current
    val snapshots = TeamAnalytics.snapshots()
    val readiness = StatisticsMath.mean(snapshots.map { it.readiness }) ?: 0.0
    val attention = snapshots.filter { it.needsAttention }
    val now = Clock.System.now()
    ScreenScaffold(header = { TopPanel(onNotifications) }) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Formatters.dayMonthWeekday(now).uppercase(), color = Palette.cyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
            Text("Команда ${core.session.user.value?.team ?: ""}", color = Palette.textPrimary, autoSize = fit(30.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.pulseCard(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(readiness / 10, Formatters.decimal(readiness), "ГОТОВНОСТЬ", 124.dp, 12.dp, 36.sp)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Игроков", "${snapshots.size}")
                Stat("Требуют внимания", "${attention.size}", if (attention.isEmpty()) Palette.green else Palette.orange)
                Stat("Средний score", "${(StatisticsMath.mean(snapshots.map { it.averageScore.toDouble() }) ?: 0.0).toInt()}")
            }
        }
        WeeklySummaryCard()
        PulseSection("Требуют внимания", "Аналитика", { router.select(AppTab.ANALYTICS) }) {
            if (attention.isEmpty()) {
                Text("Все игроки в норме 👍", color = Palette.textSecondary, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().pulseCard())
            } else {
                Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                    attention.forEachIndexed { index, snapshot ->
                        Row(
                            Modifier.fillMaxWidth().pressable { router.open(Route.Player(snapshot.player.id), AppTab.TODAY) }.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayerAvatar(snapshot.player.nickname, 40.dp, 14.sp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(snapshot.player.nickname, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(snapshot.attentionReason, color = Palette.orange, fontSize = 13.sp)
                            }
                            Icon(sfIcon("chevron.right"), null, tint = Palette.textTertiary, modifier = Modifier.size(14.dp))
                        }
                        if (index < attention.size - 1) DividerLine()
                    }
                }
            }
        }
        PulseSection("События сегодня", "Календарь", { router.open(Route.Calendar(), AppTab.TODAY) }) {
            // Players' personal plans are private: staff see team events and player schedules.
            val events = core.calendar.events(now, null).filter { it.tag != EventTag.Personal }
            if (events.isEmpty()) {
                Text("На сегодня событий нет", color = Palette.textSecondary, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().pulseCard())
            } else {
                Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                    events.forEachIndexed { index, event ->
                        Box(Modifier.pressable { router.open(Route.Event(event.id), AppTab.TODAY) }) { EventRow(event) }
                        if (index < events.size - 1) DividerLine()
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(title: String, value: String, tint: Color = Palette.textPrimary) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Palette.textSecondary, fontSize = 13.sp)
        Text(value, color = tint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
