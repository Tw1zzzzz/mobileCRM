package com.pulse.android.ui.tests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.GradientBar
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.HeaderIconButton
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.components.PulseSlider
import com.pulse.android.ui.components.RadarChart
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.fit
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Gradients
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.model.BalanceCategory
import com.pulse.core.model.DaySlot
import com.pulse.core.model.GameTestPhase
import com.pulse.core.model.GameTestResult
import com.pulse.core.util.Formatters
import com.pulse.core.util.addingDays
import com.pulse.core.util.isSameDay
import com.pulse.core.util.localDate
import com.pulse.core.util.startOfDay
import com.pulse.core.util.startOfWeek
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant

/** «Настроение и энергия»: week strip with three slot bars per day, morning/day/evening cards. */
@Composable
fun MoodScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.wellbeing
    val now = Clock.System.now()
    var selectedDay by remember { mutableStateOf(now.startOfDay()) }
    var editing by remember { mutableStateOf<DaySlot?>(null) }
    ScreenScaffold(header = { NavHeader("Настроение и энергия", onBack = { router.pop() }) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val start = now.startOfWeek()
            (0 until 7).map { start.addingDays(it) }.forEach { day ->
                val isSelected = day.isSameDay(selectedDay)
                val isFuture = day > now
                val filled = store.filledSlots(day)
                Column(
                    Modifier.weight(1f).alpha(if (isFuture) 0.45f else 1f).clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) Palette.blue.copy(alpha = 0.35f) else Palette.tile)
                        .border(1.dp, if (isSelected) Palette.cyan else Palette.stroke, RoundedCornerShape(14.dp))
                        .pressable(enabled = !isFuture) { selectedDay = day }.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(Formatters.weekdayShort(day), color = Palette.textSecondary, fontSize = 13.sp)
                    Text("${day.localDate().day}", color = if (isSelected) Color.White else Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        DaySlot.entries.forEach { slot ->
                            Box(Modifier.width(22.dp).height(4.dp).clip(CircleShape).background(if (slot in filled) Palette.cyan else Palette.track))
                        }
                    }
                }
            }
        }
        Text(Formatters.dayMonthWeekday(selectedDay), color = Palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DaySlot.entries.forEach { slot ->
                val entry = store.moodEntry(selectedDay, slot)
                Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(sfIcon(slot.icon), if (entry == null) Palette.textSecondary else Palette.cyan, 50.dp, 22.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(slot.title, color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            if (entry == null) Text("Нет данных", color = Palette.textTertiary, fontSize = 14.sp)
                        }
                        Text(
                            if (entry == null) "Добавить" else "Изменить", color = if (entry == null) Color.White else Palette.cyan, autoSize = fit(15.sp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(CircleShape).then(if (entry == null) Modifier.background(Gradients.accent) else Modifier.background(Palette.track))
                                .pressable { editing = slot }.padding(horizontal = 14.dp, vertical = 9.dp)
                        )
                    }
                    if (entry != null) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MoodMetric("Настроение", entry.mood, Palette.yellow, Modifier.weight(1f))
                        MoodMetric("Энергия", entry.energy, Palette.green, Modifier.weight(1f))
                    }
                }
            }
        }
    }
    editing?.let { slot ->
        PulseSheet({ editing = null }) {
            MoodEntrySheet(selectedDay, slot, onClose = { editing = null })
        }
    }
}

@Composable
private fun MoodMetric(title: String, value: Int, tint: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(title, color = Palette.textSecondary, autoSize = fit(13.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$value/10", color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        GradientBar(value / 10.0, height = 6.dp, fill = Brush.horizontalGradient(listOf(tint, tint)))
    }
}

private fun moodEmoji(value: Double): String = when {
    value < 4 -> "😞"
    value < 6 -> "😐"
    value < 8 -> "🙂"
    else -> "😄"
}

@Composable
private fun MoodEntrySheet(day: Instant, slot: DaySlot, onClose: () -> Unit) {
    val store = observeCore().wellbeing
    val existing = remember { store.moodEntry(day, slot) }
    var mood by remember { mutableDoubleStateOf(existing?.mood?.toDouble() ?: 6.0) }
    var energy by remember { mutableDoubleStateOf(existing?.energy?.toDouble() ?: 6.0) }
    SheetScaffold("${slot.title} · ${Formatters.dayMonth(day)}", onClose) {
        Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            PulseSlider("Настроение ${moodEmoji(mood)}", mood, tint = Palette.yellow) { mood = it }
            PulseSlider("Энергия ⚡️", energy, tint = Palette.green) { energy = it }
        }
        GradientButton("Сохранить") {
            store.saveMood(day, slot, mood.toInt(), energy.toInt())
            onClose()
        }
    }
}

/** «Колесо баланса»: date picker, 8 sliders, live radar, save for the date. */
@Composable
fun BalanceScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.wellbeing
    val today = Clock.System.now().startOfDay()
    var day by remember { mutableStateOf(today) }
    var values by remember { mutableStateOf(emptyMap<BalanceCategory, Double>()) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(day) {
        val entry = store.balanceEntry(day) ?: store.latestBalance
        values = BalanceCategory.entries.associateWith { entry?.value(it) ?: 5.0 }
    }
    LaunchedEffect(saved) {
        if (saved) { delay(1500); saved = false }
    }
    ScreenScaffold(header = { NavHeader("Колесо баланса", onBack = { router.pop() }) }) {
        val hasEntry = store.balanceEntry(day) != null
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderIconButton("chevron.left", "Предыдущий день") { day = day.addingDays(-1) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(Formatters.dayMonthWeekday(day), color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(if (hasEntry) "Данные сохранены" else "Нет сохранённых данных", color = if (hasEntry) Palette.green else Palette.textTertiary, fontSize = 13.sp)
            }
            val isToday = day.isSameDay(today)
            Box(Modifier.alpha(if (isToday) 0.4f else 1f)) {
                HeaderIconButton("chevron.right", "Следующий день") { if (!isToday) day = day.addingDays(1) }
            }
        }
        Box(Modifier.pulseCard(12.dp)) {
            RadarChart(BalanceCategory.entries.map { it.shortTitle }, BalanceCategory.entries.map { values[it] ?: 5.0 }, height = 320.dp)
        }
        Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            BalanceCategory.entries.forEach { category ->
                PulseSlider(category.title, values[category] ?: 5.0, 0.0..10.0) { values = values + (category to it) }
            }
        }
        GradientButton(if (saved) "Сохранено ✓" else "Сохранить", icon = if (saved) null else sfIcon("checkmark")) {
            store.saveBalance(day, values)
            saved = true
        }
    }
}

/** «Игровые тесты»: before/after training results, tap a row to correct it. */
@Composable
fun GameTestsScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.cognitive
    var editing by remember { mutableStateOf<GameTestResult?>(null) }
    ScreenScaffold(header = { NavHeader("Игровые тесты", "До и после тренировки", onBack = { router.pop() }) }) {
        Text("Результаты приходят из уведомлений перед и после тренировки. Нажми на запись, чтобы поправить вручную.", color = Palette.textSecondary, fontSize = 15.sp)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            store.gameResults.forEach { result ->
                val tint = if (result.phase == GameTestPhase.BEFORE_TRAINING) Palette.cyan else Palette.orange
                Column(Modifier.pressable { editing = result }.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(result.phase.title, color = tint, autoSize = fit(13.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(CircleShape).background(tint.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp))
                        Text(Formatters.dayMonthTime(result.date), color = Palette.textSecondary, autoSize = fit(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        GameScore("Фокус", result.focus)
                        GameScore("Усталость", result.fatigue)
                        GameScore("Готовность", result.readiness)
                    }
                }
            }
        }
    }
    editing?.let { result ->
        PulseSheet({ editing = null }) {
            GameTestSheet(result.phase, result, onClose = { editing = null })
        }
    }
}

@Composable
private fun GameScore(title: String, value: Int) {
    Column(Modifier.width(90.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$value", color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(title, color = Palette.textSecondary, fontSize = 12.sp)
    }
}

/**
 * Three sliders for a game test. With `existing` it corrects that record («Игровые тесты»); without it adds a new
 * result (opened from the «до / после тренировки» notification in step 4).
 */
@Composable
fun GameTestSheet(phase: GameTestPhase, existing: GameTestResult? = null, onClose: () -> Unit) {
    val store = observeCore().cognitive
    var focus by remember { mutableDoubleStateOf(existing?.focus?.toDouble() ?: 7.0) }
    var fatigue by remember { mutableDoubleStateOf(existing?.fatigue?.toDouble() ?: 3.0) }
    var readiness by remember { mutableDoubleStateOf(existing?.readiness?.toDouble() ?: 7.0) }
    SheetScaffold(phase.title, onClose) {
        if (existing == null) {
            Text(
                if (phase == GameTestPhase.BEFORE_TRAINING) "Как ты себя чувствуешь перед тренировкой? Ответ займёт 20 секунд."
                else "Как прошла тренировка? Отметь состояние сразу после неё.",
                color = Palette.textSecondary, fontSize = 16.sp
            )
        }
        Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PulseSlider("Фокус", focus) { focus = it }
            PulseSlider("Усталость", fatigue, tint = Palette.orange) { fatigue = it }
            PulseSlider("Готовность", readiness, tint = Palette.green) { readiness = it }
        }
        GradientButton(if (existing == null) "Сохранить результат" else "Сохранить изменения", icon = if (existing == null) sfIcon("checkmark") else null) {
            if (existing == null) {
                store.addGameResult(phase, focus.toInt(), fatigue.toInt(), readiness.toInt())
            } else {
                store.updateGameResult(existing.copy(focus = focus.toInt(), fatigue = fatigue.toInt(), readiness = readiness.toInt()))
            }
            onClose()
        }
    }
}
