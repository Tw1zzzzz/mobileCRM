package com.pulse.android.ui.health

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.Callout
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.DurationText
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.components.GradientBar
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.HeaderIconButton
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseLineChart
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.ScoreRing
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.StatusPill
import com.pulse.android.ui.components.ValueUnitText
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Gradients
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.color
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.model.RecoveryMath
import com.pulse.core.model.SleepNight
import com.pulse.core.model.SleepSession
import com.pulse.core.model.SleepStage
import com.pulse.core.model.SleepWeekDay
import com.pulse.core.util.Formatters
import com.pulse.core.util.addingMinutes
import com.pulse.core.util.isSameDay
import com.pulse.core.util.localDate
import com.pulse.core.util.localDateTime
import com.pulse.core.util.secondsBetween
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant

/** Mockup 1 — «Сон»: score, phases, cycles & quality + tracking controls and history. */
@Composable
fun SleepScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val context = LocalContext.current
    val now = Clock.System.now()
    val store = core.sleep
    val night = store.displayNight(now)
    ScreenScaffold(header = {
        NavHeader("Сон", onBack = { router.pop() }) {
            HeaderIconButton("point.3.connected.trianglepath.dotted", "Поделиться") {
                val shown = night ?: return@HeaderIconButton
                val text = "Сон ${Formatters.duration(shown.totalMinutes)}, оценка ${shown.score} — Pulse"
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }
        }
    }) {
        when {
            night == null -> {
                PulseSection("Трекинг сна") { SleepTrackingCard() }
                NoSleepDataCard(core.fitness.healthName)
            }
            store.isSleeping -> {
                PulseSection("Трекинг сна") { SleepTrackingCard() }
                ScoreCard(night)
            }
            else -> {
                ScoreCard(night)
                PulseSection("Трекинг сна") { SleepTrackingCard() }
            }
        }
        if (night != null) {
            PulseSection("Фазы сна") { PhasesCard(night, core.fitness.healthName) }
            PulseSection("Циклы и качество") { QualityGrid(night) }
        }
        LinkCard("heart.text.square", "РЕЖИМ И ВОССТАНОВЛЕНИЕ", "Пульс, HRV, неделя и окно сна") { router.open(Route.SleepRecovery) }
        PulseSection("Статистика · 7 дней") {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatTile("Средняя", store.averageMinutes(7, now) ?: 0, Modifier.weight(1f))
                StatTile("Общая", store.totalMinutes(7, now), Modifier.weight(1f))
            }
        }
        PulseSection("История сна") { SleepHistoryList(store.sessions.take(10)) }
    }
}

@Composable
private fun ScoreCard(night: SleepNight) {
    val nightTitle = "НОЧЬ ${night.start.localDate().day} → ${Formatters.dayMonth(night.end).uppercase()}"
    Row(Modifier.pulseCard(padding = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        ScoreRing(night.score / 100.0, "${night.score}", "ОЦЕНКА СНА", size = 138.dp, lineWidth = 13.dp, valueSize = 48.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(nightTitle, color = Palette.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp, maxLines = 1)
            DurationText(night.totalMinutes, valueSize = 38.sp, unitSize = 24.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(sfIcon("moon"), null, tint = Palette.purple, modifier = Modifier.size(18.dp))
                Text("  ${Formatters.time(night.start)} → ${Formatters.time(night.end)}", color = Palette.textSecondary, fontSize = 17.sp)
            }
            StatusPill("${night.scoreLabel} · ${if (night.baselineDelta >= 0) "+" else ""}${night.baselineDelta} к норме")
        }
    }
}

@Composable
private fun PhasesCard(night: SleepNight, healthName: String) {
    if (night.hasStages && night.segments.isNotEmpty()) {
        Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Hypnogram(night)
            DividerLine()
            StageLegend(night)
        }
    } else {
        Row(Modifier.pulseCard(padding = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(sfIcon("applewatch"), null, tint = Palette.purple, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Фазы сна недоступны", color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Глубокий, REM и лёгкий сон записывают часы и трекеры, подключённые к $healthName. Сейчас показана длительность из трекинга сна.",
                    color = Palette.textSecondary, fontSize = 14.sp)
            }
        }
    }
}

/** Sleep phases: stage blocks on four levels with dashed hour guides. */
@Composable
fun Hypnogram(night: SleepNight, chartHeight: Int = 175) {
    val ticks = remember(night) {
        val result = mutableListOf(night.start)
        val startLocal = night.start.localDateTime()
        var cursor = night.start.addingMinutes(60 - startLocal.minute)
        while (cursor < night.end.addingMinutes(-45)) {
            if (cursor.localDateTime().hour % 2 == 1) result += cursor
            cursor = cursor.addingMinutes(60)
        }
        result + night.end
    }
    val total = secondsBetween(night.start, night.end)
    fun fraction(date: Instant) = if (total > 0) (secondsBetween(night.start, date) / total).toFloat() else 0f
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.fillMaxWidth().height(chartHeight.dp)) {
            ticks.forEach { tick ->
                val x = size.width * fraction(tick)
                drawLine(Color.White.copy(alpha = 0.13f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            }
            val segments = night.segments
            segments.forEachIndexed { index, segment ->
                if (index == 0) return@forEachIndexed
                val previous = segments[index - 1]
                val x = size.width * fraction(segment.start)
                val tint = if (segment.stage == SleepStage.AWAKE || previous.stage == SleepStage.AWAKE) Palette.orange else Color.White
                drawLine(tint.copy(alpha = 0.45f), Offset(x, (previous.stage.level * size.height).toFloat()), Offset(x, (segment.stage.level * size.height).toFloat()), 1.2.dp.toPx())
            }
            val barHeight = 17.dp.toPx()
            segments.forEach { segment ->
                val startX = size.width * fraction(segment.start)
                val endX = size.width * fraction(segment.end)
                val isAwake = segment.stage == SleepStage.AWAKE
                val width = maxOf(endX - startX, if (isAwake) 5.dp.toPx() else 3.dp.toPx())
                val height = if (isAwake && width < 10.dp.toPx()) barHeight + 8.dp.toPx() else barHeight
                val color = segment.stage.tint.color
                drawRoundRect(
                    Brush.verticalGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.75f))),
                    topLeft = Offset(startX, (segment.stage.level * size.height).toFloat() - height / 2),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(minOf(5.dp.toPx(), width / 2))
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp)) {
            ticks.forEachIndexed { index, tick ->
                val raw = maxWidth * fraction(tick)
                val x = when (index) {
                    0 -> raw
                    ticks.size - 1 -> raw - 44.dp
                    else -> raw - 22.dp
                }
                Text(Formatters.time(tick), color = Palette.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.offset(x = x))
            }
        }
    }
}

@Composable
private fun StageLegend(night: SleepNight) {
    @Composable
    fun Item(stage: SleepStage) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.padding(top = 4.dp).size(20.dp).clip(RoundedCornerShape(6.dp)).background(stage.tint.color))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stage.title, color = Palette.textSecondary, fontSize = 16.sp)
                Text(Formatters.duration(night.minutes(stage)), color = Palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Row {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) { Item(SleepStage.AWAKE); Item(SleepStage.LIGHT) }
        Box(Modifier.width(1.dp).height(130.dp).background(Palette.divider))
        Column(Modifier.weight(1f).padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) { Item(SleepStage.REM); Item(SleepStage.DEEP) }
    }
}

@Composable
private fun QualityGrid(night: SleepNight) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MetricTile("arrow.triangle.2.circlepath", Palette.purple, "Циклов", "${night.cycles}", null, "по ~${night.averageCycleMinutes} мин", Modifier.weight(1f))
            MetricTile("target", Palette.green, "Эффективность", "${night.efficiency}", "%", "время в кровати", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MetricTile("clock", Palette.blue, "Засыпание", "${night.latencyMinutes}", "мин", if (night.latencyMinutes <= 20) "Нормально" else "Долго", Modifier.weight(1f))
            MetricTile("eye", Palette.orange, "Пробуждений", "${night.awakenings}", null, if (night.awakenings <= 3) "Короткие" else "Частые", Modifier.weight(1f))
        }
    }
}

@Composable
fun MetricTile(icon: String, tint: Color, title: String, value: String, unit: String?, caption: String, modifier: Modifier = Modifier) {
    Column(modifier.pulseCard(14.dp, Metrics.tileRadius), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBadge(sfIcon(icon), tint, 34.dp, 15.dp)
            Text(title, color = Palette.textSecondary, fontSize = 14.sp, maxLines = 1)
        }
        ValueUnitText(value, unit, valueSize = 34.sp, unitSize = 20.sp)
        Text(caption, color = Palette.textSecondary, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
private fun StatTile(title: String, minutes: Int, modifier: Modifier) {
    Column(modifier.pulseCard(14.dp, Metrics.tileRadius), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Palette.textSecondary, fontSize = 16.sp)
        DurationText(minutes, valueSize = 26.sp, unitSize = 17.sp)
    }
}

@Composable
fun LinkCard(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.pressable(onClick = onClick).pulseCard(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(sfIcon(icon), Palette.cyan, 52.dp, 22.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Palette.cyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, maxLines = 1)
            Text(subtitle, color = Palette.textSecondary, fontSize = 15.sp, maxLines = 1)
        }
        Icon(sfIcon("chevron.right"), null, tint = Palette.textSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun NoSleepDataCard(healthName: String) {
    Row(Modifier.pulseCard(padding = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(sfIcon("moon.zzz"), null, tint = Palette.purple, modifier = Modifier.size(26.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Данных о сне пока нет", color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Нажми «Начать сон», когда ложишься, или подключи $healthName в «Фитнесе» — ночи с часов подтянутся сами.", color = Palette.textSecondary, fontSize = 14.sp)
        }
    }
}

/** «Начать / Завершить сон» and the running session with a live timer. */
@Composable
fun SleepTrackingCard() {
    val core = observeCore()
    val store = core.sleep
    var lastSaved by remember { mutableStateOf<SleepSession?>(null) }
    var tick by remember { mutableStateOf(Clock.System.now()) }
    val start = store.activeStart
    LaunchedEffect(start) {
        while (start != null) {
            tick = Clock.System.now()
            delay(1000)
        }
    }
    Column(Modifier.pulseCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (start != null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.green))
                    Text("  Сон активен", color = Palette.green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("с ${Formatters.time(start)}", color = Palette.textSecondary, fontSize = 15.sp)
                }
                Text(Formatters.timer(secondsBetween(start, tick)), color = Palette.textPrimary, fontSize = 46.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(sfIcon("bed.double"), Palette.purple, 48.dp, 22.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Сон не запущен", color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Нажми, когда ложишься спать", color = Palette.textSecondary, fontSize = 15.sp)
                }
            }
        }
        GradientButton(if (store.isSleeping) "Завершить сон" else "Начать сон", icon = sfIcon(if (store.isSleeping) "sun.max" else "moon.zzz")) {
            if (store.isSleeping) lastSaved = store.stopSleep() else {
                lastSaved = null
                store.startSleep()
            }
        }
        lastSaved?.let { StatusPill("Сохранено · ${Formatters.duration(it.durationMinutes)}", if (it.isValid) Palette.green else Palette.orange) }
        Text(
            if (store.isSleeping) "Таймер сна виден в уведомлении — там же кнопка «Проснулся»."
            else "Вечером придёт напоминание «Скоро отбой» — сон можно начать прямо из уведомления.",
            color = Palette.textTertiary, fontSize = 13.sp
        )
    }
}

/** Date, start, end, duration and status of each session. */
@Composable
fun SleepHistoryList(sessions: List<SleepSession>) {
    if (sessions.isEmpty()) {
        EmptyStateView("moon.zzz", "Записей пока нет", "Начни первую сессию сна, чтобы увидеть историю.")
        return
    }
    Column(Modifier.pulseCard(padding = 4.dp)) {
        sessions.forEachIndexed { index, session ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Formatters.dayMonth(session.end), color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("${Formatters.time(session.start)} → ${Formatters.time(session.end)}", color = Palette.textSecondary, fontSize = 15.sp)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Formatters.duration(session.durationMinutes), color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(session.statusTitle, color = if (session.isValid) Palette.green else Palette.orange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (index < sessions.size - 1) DividerLine()
        }
    }
}

/** Mockup 2 — «Сон · Восстановление и режим». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepRecoveryScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.sleep
    val now = Clock.System.now()
    val night = store.displayNight(now)
    var editWindow by remember { mutableStateOf(false) }
    ScreenScaffold(header = {
        NavHeader("Сон", "Восстановление и режим", onBack = { router.pop() }) {
            HeaderIconButton("gearshape", "Настройки сна") { editWindow = true }
        }
    }) {
        PulseSection("Готовность к нагрузке") { RecoveryCard() }
        if (night != null) PulseSection("Тело ночью") { BodyAtNightCard(night, core.fitness.healthName) } else NoSleepDataCard(core.fitness.healthName)
        PulseSection("Неделя") { WeekCard(store.week(now)) }
        PulseSection("Режим сна") {
            val window = store.window
            Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 18.dp)) {
                Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(sfIcon("moon"), Palette.purple, 54.dp, 24.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Окно сна", color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text("${window.bedtimeText} → ${window.wakeText} · ${window.durationMinutes / 60} ч", color = Palette.textSecondary, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                    }
                    Text("Править", color = Palette.cyan, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.pressable { editWindow = true })
                }
                DividerLine()
                Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(sfIcon("bell"), Palette.orange, 54.dp, 22.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Умный будильник", color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text("В лёгкой фазе ±20 мин", color = Palette.textSecondary, fontSize = 15.sp)
                    }
                    Switch(window.smartAlarmEnabled, { store.updateWindow(window.copy(smartAlarmEnabled = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.blue))
                }
            }
        }
    }
    if (editWindow) {
        ModalBottomSheet(onDismissRequest = { editWindow = false }, containerColor = Palette.background) {
            val window = store.window
            val bed = rememberTimePickerState(window.bedtimeHour, window.bedtimeMinute, is24Hour = true)
            val wake = rememberTimePickerState(window.wakeHour, window.wakeMinute, is24Hour = true)
            val colors = TimePickerDefaults.colors(
                timeSelectorSelectedContainerColor = Palette.blue, timeSelectorUnselectedContainerColor = Palette.tile,
                timeSelectorSelectedContentColor = Color.White, timeSelectorUnselectedContentColor = Palette.textPrimary
            )
            SheetScaffold("Окно сна", { editWindow = false }) {
                Text("Отбой", color = Palette.textSecondary, fontSize = 15.sp)
                TimeInput(bed, colors = colors)
                Text("Подъём", color = Palette.textSecondary, fontSize = 15.sp)
                TimeInput(wake, colors = colors)
                GradientButton("Сохранить") {
                    store.updateWindow(window.copy(bedtimeHour = bed.hour, bedtimeMinute = bed.minute, wakeHour = wake.hour, wakeMinute = wake.minute))
                    editWindow = false
                }
            }
        }
    }
}

@Composable
private fun BodyAtNightCard(night: SleepNight, healthName: String) {
    Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Пульс", color = Palette.textSecondary, fontSize = 18.sp, modifier = Modifier.weight(1f))
            night.heartRateBaselineDelta?.let { delta ->
                Text("${if (delta <= 0) "↓" else "↑"} ${kotlin.math.abs(delta)} к базовому", color = if (delta <= 0) Palette.green else Palette.orange, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
        night.restingHeartRate?.let { resting ->
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("$resting", color = Palette.textPrimary, fontSize = 54.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
                Text(night.minHeartRate?.let { "уд/мин · мин. $it" } ?: "уд/мин", color = Palette.textSecondary, fontSize = 18.sp, modifier = Modifier.alignByBaseline())
            }
        }
        if (night.heartRate.isEmpty()) {
            Text("Пульс ночью, HRV, дыхание и кислород записывают часы и трекеры, подключённые к $healthName.", color = Palette.textSecondary, fontSize = 14.sp)
        } else {
            val points = night.heartRate
            val step = secondsBetween(night.start, night.end) / maxOf(points.size - 1, 1)
            PulseLineChart(
                points.map { it.toDouble() },
                calloutFor = { i -> Callout(Formatters.time(night.start.addingMinutes((step * i / 60).toInt())), "${points[i]} уд/мин", Palette.cyan) },
                height = 84.dp, lower = (points.min() - 6).toDouble(), upper = (points.max() + 2).toDouble()
            )
        }
        DividerLine()
        Row {
            Vital("ДЫХАНИЕ", night.respiratoryRate?.toString() ?: "—", "вд/мин", night.respiratoryRate?.let { (it - 8) / 14.0 } ?: 0.0, Gradients.accent, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(96.dp).background(Palette.divider))
            Vital("HRV", night.hrv?.toString() ?: "—", "мс", night.hrv?.let { it / 120.0 } ?: 0.0, Gradients.accent, Modifier.weight(1f).padding(start = 14.dp))
            Box(Modifier.width(1.dp).height(96.dp).background(Palette.divider))
            Vital("SpO₂", night.spo2?.toString() ?: "—", "%", night.spo2?.let { (it - 90) / 10.0 } ?: 0.0, Brush.horizontalGradient(listOf(Palette.purple, Palette.purple)), Modifier.weight(1f).padding(start = 14.dp))
        }
    }
}

@Composable
private fun Vital(title: String, value: String, unit: String, progress: Double, brush: Brush, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Palette.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
        ValueUnitText(value, unit, valueSize = 30.sp, unitSize = 14.sp)
        GradientBar(progress, Modifier.padding(end = 12.dp), height = 7.dp, fill = brush)
    }
}

@Composable
private fun WeekCard(week: List<SleepWeekDay>) {
    val nights = week.filter { it.total > 0 }
    val average = nights.sumOf { it.total } / maxOf(nights.size, 1)
    val maxTotal = maxOf(week.maxOfOrNull { it.total } ?: 0, 1)
    val today = Clock.System.now()
    Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Среднее за 7 ночей", color = Palette.textPrimary, fontSize = 18.sp, modifier = Modifier.weight(1f))
            DurationText(average, valueSize = 26.sp, unitSize = 18.sp)
        }
        Row(Modifier.height(136.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            week.forEach { day ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)) {
                    val height = 100f * day.total / maxTotal
                    val total = maxOf(day.total, 1).toFloat()
                    Column(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(6.dp))) {
                        Box(Modifier.fillMaxWidth().weight(maxOf(day.light / total, 0.0001f)).background(Palette.navyBar))
                        Box(Modifier.fillMaxWidth().weight(maxOf(day.rem / total, 0.0001f)).background(Color(0xFF2A86F7)))
                        Box(Modifier.fillMaxWidth().weight(maxOf(day.deep / total, 0.0001f)).background(Palette.cyan))
                    }
                    val isToday = day.date.isSameDay(today)
                    Text(Formatters.weekdayShort(day.date), color = if (isToday) Palette.textPrimary else Palette.textSecondary, fontSize = 15.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

/** «Восстановление» 0–100: HRV and resting pulse against the personal norm, sleep and energy. */
@Composable
fun RecoveryCard() {
    val core = observeCore()
    val result = RecoveryMath.compute(core.recoveryInput())
    if (result == null) {
        Text("Недостаточно данных: нужен сон и хотя бы один показатель пульса из ${core.fitness.healthName}.",
            color = Palette.textSecondary, fontSize = 14.sp, modifier = Modifier.pulseCard(padding = 16.dp))
        return
    }
    Column(Modifier.pulseCard(padding = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(result.score / 100.0, "${result.score}", "ВОССТ.", size = 104.dp, lineWidth = 10.dp, valueSize = 34.sp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(result.label, color = result.tint.color, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(core.recoveryAdvice(result.score), color = Palette.textSecondary, fontSize = 14.sp)
            }
        }
        result.components.forEach { component ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(component.title, color = Palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(component.detail, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 1)
                }
                GradientBar(component.value / 100, height = 6.dp)
            }
        }
        if (core.fitness.state.value.health == null && core.sleep.state.value.isDemo) {
            Text("Демо-данные. С ${core.fitness.healthName} и часами показатели будут твоими.", color = Palette.textTertiary, fontSize = 12.sp)
        }
    }
}
