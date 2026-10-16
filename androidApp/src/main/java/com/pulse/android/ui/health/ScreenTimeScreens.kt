package com.pulse.android.ui.health

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.Callout
import com.pulse.android.ui.components.ChipButton
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.DurationText
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.components.FractionLabels
import com.pulse.android.ui.components.GradientBar
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.HeaderIconButton
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseBarChart
import com.pulse.android.ui.components.PulseLineChart
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.color
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.model.AppCategory
import com.pulse.core.model.AppUsage
import com.pulse.core.store.ScreenTimeStore
import com.pulse.core.util.Formatters
import com.pulse.core.util.addingDays
import com.pulse.core.util.isSameDay
import com.pulse.core.util.roundToIntSwift
import com.pulse.core.util.startOfDay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.time.Clock

private const val PEAK_THRESHOLD = 28

/** Mockup 5 — «Экранное время»: today, by hour, top apps. */
@Composable
fun ScreenTimeScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.screenTime
    var settings by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf(false) }
    ScreenScaffold(header = {
        NavHeader("Экранное время", onBack = { router.pop() }) {
            HeaderIconButton("gearshape", "Настройки экранного времени") { settings = true }
        }
    }) {
        if (!store.showsContent) {
            ScreenTimeEmptyState { settings = true }
            return@ScreenScaffold
        }
        TodayCard(store) { entry = true }
        if (store.hasDetailedData) {
            TilesRow(store)
            PulseSection("По часам") { HourlyCard(store.hourly) }
            PulseSection("Топ приложений", "Все", { router.open(Route.ScreenTimeApps) }) {
                AppUsageList(store.apps.take(2), filled = true)
            }
        } else {
            ManualScreenTimeInfo()
            LinkCard("chart.bar", "Неделя и история", "Динамика по введённым дням") { router.open(Route.ScreenTimeApps) }
        }
    }
    if (settings) PulseSheet({ settings = false }) { ScreenTimeSettingsSheet { settings = false } }
    if (entry) PulseSheet({ entry = false }) { ScreenTimeEntrySheet { entry = false } }
}

@Composable
private fun TodayCard(store: ScreenTimeStore, onEdit: () -> Unit) {
    val remaining = store.remainingMinutes()
    val withinLimit = remaining >= 0
    val progress = store.limitProgress()
    val limit = Formatters.durationParts(store.state.value.dailyLimitMinutes)
    Column(Modifier.pulseCard(padding = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("СЕГОДНЯ", color = Palette.cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.4.sp)
        DurationText(store.todayMinutes(), valueSize = 64.sp, unitSize = 38.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Лимит дня:", color = Palette.textSecondary, fontSize = 18.sp)
            Text("${limit.hours} ч  ${limit.minutes} м", color = Palette.textPrimary, fontSize = 18.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GradientBar(progress, Modifier.weight(1f), height = 14.dp)
            Text("${(progress * 100).roundToIntSwift()}%", color = Palette.textSecondary, fontSize = 16.sp)
        }
        Text(
            if (withinLimit) "В пределах лимита · осталось $remaining мин" else "Лимит превышен на ${abs(remaining)} мин",
            color = if (withinLimit) Palette.green else Palette.red, fontSize = 17.sp, fontWeight = FontWeight.Medium
        )
        if (!store.state.value.isDemo) {
            GradientButton(if (store.minutes(Clock.System.now()) == null) "Ввести время за сегодня" else "Изменить", icon = sfIcon("square.and.pencil"), onClick = onEdit)
        }
    }
}

@Composable
private fun TilesRow(store: ScreenTimeStore) {
    val change = store.changeVsYesterday()
    val diff = abs(store.yesterdayMinutes() - store.todayMinutes())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoTile("bell", Palette.yellow, "${store.pickups}", "Поднятий", "≈ ${store.pickups / 17} / час", Modifier.weight(1f))
        InfoTile("sun.max", Palette.purple, store.firstPickup, "Первый экран", "время", Modifier.weight(1f))
        // As in the mockup: a green trend icon means the day got better (less screen time).
        InfoTile(
            if (change <= 0) "chart.line.uptrend.xyaxis" else "chart.line.downtrend.xyaxis", if (change <= 0) Palette.green else Palette.red,
            "${if (change > 0) "+" else ""}${change.roundToIntSwift()}%", "Ко вчера",
            "${diff / 60} ч ${diff % 60} м ${if (change <= 0) "меньше" else "больше"}", Modifier.weight(1f)
        )
    }
}

@Composable
private fun InfoTile(icon: String, tint: Color, value: String, title: String, caption: String, modifier: Modifier) {
    Column(modifier.pulseCard(14.dp, Metrics.tileRadius), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(sfIcon(icon), null, tint = tint, modifier = Modifier.size(22.dp).padding(bottom = 2.dp))
        Text(value, color = Palette.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(title, color = Palette.textSecondary, fontSize = 12.sp, maxLines = 1, softWrap = false)
        Text(caption, color = Palette.textTertiary, fontSize = 11.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun HourlyCard(hourly: List<Int>) {
    val calm = Brush.verticalGradient(listOf(Color(0xFF16B4FF), Palette.blue))
    val peak = Brush.verticalGradient(listOf(Palette.red, Palette.red))
    Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PulseBarChart(
            hourly.map { it.toDouble() },
            labels = emptyList(),
            calloutFor = { hour -> Callout("%02d:00–%02d:00".format(hour, (hour + 1) % 24), "${hourly[hour]} мин", Palette.cyan) },
            height = 190.dp,
            upper = 60.0,
            barBrush = { hour -> if (hourly[hour] > PEAK_THRESHOLD) peak else calm },
            labelsBelow = {
                FractionLabels(listOf(0, 6, 12, 18, 23).map { hour -> (hour + 0.5f) / hourly.size.coerceAtLeast(1) to "%02d".format(hour) })
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Legend(Palette.cyan, "Обычно")
            Legend(Palette.red, "Пик (> $PEAK_THRESHOLD мин)")
        }
    }
}

@Composable
private fun Legend(color: Color, title: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Text(title, color = Palette.textSecondary, fontSize = 15.sp)
    }
}

/** App rows: `filled` — saturated brand tile (mockup 5), otherwise dark tile with a colored glyph (mockup 6). */
@Composable
fun AppUsageList(apps: List<AppUsage>, filled: Boolean = false) {
    val maxMinutes = (apps.maxOfOrNull { it.minutes } ?: 1).toDouble()
    Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 18.dp).animateContentSize()) {
        apps.forEachIndexed { index, app ->
            Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (filled) {
                    Box(Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(app.tint.color.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                        Icon(sfIcon(app.icon), null, tint = app.tint.color, modifier = Modifier.size(26.dp))
                    }
                } else {
                    IconBadge(sfIcon(app.icon), app.tint.color, 54.dp, 22.dp, background = Palette.track)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text(app.name, color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(Formatters.duration(app.minutes), color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(app.category.title, color = Palette.textSecondary, fontSize = 14.sp)
                    GradientBar(app.minutes / maxMinutes * 0.72, Modifier.padding(top = 4.dp), height = 7.dp)
                }
            }
            if (index < apps.size - 1) DividerLine()
        }
    }
}

@Composable
private fun ScreenTimeEmptyState(onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EmptyStateView("hourglass", "Нет данных", "Нет данных об экранном времени. Проверьте разрешения устройства.")
        GradientButton("Дать доступ", icon = sfIcon("lock.open"), onClick = onOpenSettings)
    }
}

@Composable
private fun ScreenTimeSettingsSheet(onClose: () -> Unit) {
    val core = observeCore()
    val store = core.screenTime
    val state = store.state.value
    SheetScaffold("Экранное время", onClose) {
        Row(Modifier.pulseCard(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Доступ к статистике", color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Нужно системное разрешение «Доступ к истории использования»", color = Palette.textSecondary, fontSize = 14.sp)
            }
            Switch(
                state.isAuthorized, { store.setAuthorized(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.blue, checkedThumbColor = Color.White)
            )
        }
        Text("ЛИМИТ ДНЯ", color = Palette.section, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(180, 240, 300, 360, 420).forEach { limit ->
                ChipButton("${limit / 60} ч", state.dailyLimitMinutes == limit) { store.setLimit(limit) }
            }
        }
    }
}

/** Where to find the number while automatic collection isn't connected (step 4: usage statistics). */
@Composable
private fun ManualScreenTimeInfo() {
    Row(Modifier.fillMaxWidth().pulseCard(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(sfIcon("hourglass"), null, tint = Palette.cyan, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Время берётся из настроек телефона", color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Открой Настройки → Цифровое благополучие и перенеси сюда время за день. Автоматический сбор, разбивка по часам и приложениям появятся, когда Pulse получит доступ к истории использования.",
                color = Palette.textSecondary, fontSize = 14.sp
            )
        }
    }
}

/** Manual entry of a day's screen time (today or one of the previous six days). */
@Composable
private fun ScreenTimeEntrySheet(onClose: () -> Unit) {
    val core = observeCore()
    val store = core.screenTime
    val today = Clock.System.now().startOfDay()
    var day by remember { mutableStateOf(today) }
    fun saved(on: kotlin.time.Instant) = store.minutes(on)
    var hours by remember { mutableIntStateOf((saved(today) ?: 180) / 60) }
    var minutes by remember { mutableIntStateOf((saved(today) ?: 0) % 60 / 5 * 5) }
    SheetScaffold("Экранное время", onClose) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0 until 7).map { today.addingDays(-it) }.forEach { option ->
                ChipButton(if (option == today) "Сегодня" else Formatters.dayMonthShort(option), option == day) {
                    day = option
                    saved(option)?.let {
                        hours = it / 60
                        minutes = it % 60 / 5 * 5
                    }
                }
            }
        }
        Column(Modifier.pulseCard(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StepperRow("Часы: $hours", hours > 0, hours < 20, { hours-- }, { hours++ })
            DividerLine()
            StepperRow("Минуты: $minutes", minutes > 0, minutes < 55, { minutes -= 5 }, { minutes += 5 })
        }
        GradientButton("Сохранить", icon = sfIcon("checkmark")) {
            store.setMinutes(hours * 60 + minutes, day)
            onClose()
        }
    }
}

@Composable
private fun StepperRow(title: String, canDecrease: Boolean, canIncrease: Boolean, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Palette.textPrimary, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.track)) {
            StepButton("minus", "Меньше", canDecrease, onDecrease)
            Box(Modifier.width(1.dp).height(32.dp).background(Palette.divider))
            StepButton("plus", "Больше", canIncrease, onIncrease)
        }
    }
}

@Composable
private fun StepButton(icon: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp, 32.dp).pressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(sfIcon(icon), description, tint = if (enabled) Palette.textPrimary else Palette.textTertiary, modifier = Modifier.size(18.dp)) }
}

/** Mockup 6 — «Приложения и недельная динамика» + categories and history. */
@Composable
fun ScreenTimeAppsScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.screenTime
    var showAll by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var period by remember { mutableIntStateOf(7) }
    ScreenScaffold(header = {
        NavHeader("Экранное время", "Приложения и недельная динамика", onBack = { router.pop() }) {
            HeaderIconButton("gearshape", "Настройки экранного времени") { settings = true }
        }
    }) {
        if (!store.showsContent) {
            ScreenTimeEmptyState { settings = true }
            return@ScreenScaffold
        }
        if (store.hasDetailedData) {
            PulseSection("Топ приложений", if (showAll) "Свернуть" else "Все", { showAll = !showAll }) {
                AppUsageList(if (showAll) store.apps else store.apps.take(6))
            }
        }
        PulseSection("Неделя") { WeekCard(store) }
        if (store.hasDetailedData) PulseSection("Категории") { CategoriesCard(store.categoryTotals) }
        PulseSection("Динамика") {
            val series = store.daily(period)
            Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 14, 30).forEach { days -> ChipButton("$days дней", period == days) { period = days } }
                }
                if (series.isEmpty()) {
                    Text("Нет введённых дней за этот период", color = Palette.textSecondary, fontSize = 15.sp)
                } else {
                    PulseLineChart(
                        series.map { it.value / 60 },
                        calloutFor = { i -> Callout(Formatters.dayMonthShort(series[i].date), Formatters.duration(series[i].value.toInt()), Palette.cyan) },
                        height = 160.dp,
                        lower = 0.0
                    )
                    FractionLabels(series.indices.filter { it % maxOf((series.size + 3) / 4, 1) == 0 }.map { i ->
                        (if (series.size <= 1) 0.5f else i / (series.size - 1f)) to Formatters.dayMonthShort(series[i].date)
                    })
                }
            }
        }
        PulseSection("История") { HistoryCard(store) }
    }
    if (settings) PulseSheet({ settings = false }) { ScreenTimeSettingsSheet { settings = false } }
}

@Composable
private fun WeekCard(store: ScreenTimeStore) {
    val now = Clock.System.now()
    val week = store.week(now)
    val limit = store.state.value.dailyLimitMinutes
    val maxMinutes = maxOf(week.maxOfOrNull { it.minutes } ?: 1, limit).toDouble()
    val chartHeight = 200.dp
    val barArea = chartHeight - 30.dp
    Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Среднее", color = Palette.textSecondary, fontSize = 18.sp, modifier = Modifier.weight(1f))
            DurationText(store.weekAverage(now), valueSize = 32.sp, unitSize = 22.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().height(chartHeight)) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    week.forEach { day ->
                        val isToday = day.date.isSameDay(now)
                        val parts = Formatters.durationParts(day.minutes)
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${parts.hours} ч ${parts.minutes} м", color = if (isToday) Palette.textPrimary else Palette.textSecondary,
                                fontSize = 10.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                            Box(
                                Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                                    .height(maxOf(barArea * (day.minutes / maxMinutes).toFloat(), 8.dp))
                                    .then(if (isToday) Modifier.shadow(10.dp, RoundedCornerShape(8.dp), ambientColor = Palette.cyan, spotColor = Palette.cyan) else Modifier)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isToday) Brush.verticalGradient(listOf(Color(0xFF22E0FF), Color(0xFF10B4F0)))
                                        else Brush.verticalGradient(listOf(Color(0xFF2B6FD6), Color(0xFF1A3F86)))
                                    )
                            )
                        }
                    }
                }
                val limitFraction = (limit / maxMinutes).toFloat()
                Canvas(Modifier.fillMaxSize()) {
                    val y = size.height - barArea.toPx() * limitFraction
                    drawLine(Palette.orange.copy(alpha = 0.85f), Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                }
                Text(
                    "Лимит ${limit / 60} ч", color = Palette.orange, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.TopEnd).offset(y = chartHeight - barArea * limitFraction - 22.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                week.forEach { day ->
                    val isToday = day.date.isSameDay(now)
                    Text(Formatters.weekdayShort(day.date), color = if (isToday) Palette.textPrimary else Palette.textSecondary, fontSize = 15.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

/** Donut of app categories; hold and slide over it to see a category's time in the middle. */
@Composable
private fun CategoriesCard(totals: List<Pair<AppCategory, Int>>) {
    val sum = maxOf(totals.sumOf { it.second }, 1)
    var selected by remember { mutableStateOf<AppCategory?>(null) }
    val haptics = LocalHapticFeedback.current
    Row(Modifier.pulseCard(padding = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            fun categoryAt(offset: Offset, side: Float): AppCategory? {
                val angle = atan2((offset.y - side / 2).toDouble(), (offset.x - side / 2).toDouble()) + Math.PI / 2
                val fraction = (if (angle < 0) angle + 2 * Math.PI else angle) / (2 * Math.PI)
                var running = 0.0
                return totals.firstOrNull { running += it.second.toDouble() / sum; fraction <= running }?.first ?: totals.lastOrNull()?.first
            }
            Canvas(Modifier.fillMaxSize().pointerInput(totals) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { selected = categoryAt(it, size.width.toFloat()); haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onDrag = { change, _ ->
                        val next = categoryAt(change.position, size.width.toFloat())
                        if (next != selected) { selected = next; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    },
                    onDragEnd = { selected = null },
                    onDragCancel = { selected = null }
                )
            }) {
                var start = -90f
                totals.forEach { (category, minutes) ->
                    val sweep = 360f * minutes / sum
                    val isSelected = selected == category
                    val outer = size.minDimension / 2 * (if (isSelected) 1f else 0.92f)
                    val thickness = outer - size.minDimension / 2 * 0.62f
                    val radius = outer - thickness / 2
                    drawArc(
                        category.tint.color.copy(alpha = if (selected == null || isSelected) 1f else 0.4f),
                        start + 1f, (sweep - 2f).coerceAtLeast(0.5f), useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2),
                        style = Stroke(thickness)
                    )
                    start += sweep
                }
            }
            val current = selected?.let { category -> totals.firstOrNull { it.first == category } }
            if (current != null) {
                Column(Modifier.width(70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Formatters.duration(current.second), color = Palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(current.first.title, color = Palette.textSecondary, fontSize = 11.sp, maxLines = 1)
                }
            } else {
                Icon(sfIcon("hand.tap"), null, tint = Palette.textTertiary, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            totals.forEach { (category, minutes) ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(category.tint.color))
                    Text(category.title, color = Palette.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${(minutes * 100.0 / sum).roundToIntSwift()}%", color = Palette.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(store: ScreenTimeStore) {
    val week = store.week().reversed()
    val limit = maxOf(store.state.value.dailyLimitMinutes, 1)
    Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 18.dp)) {
        week.forEachIndexed { index, day ->
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Formatters.dayMonthWeekday(day.date), color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(day.topCategory?.let { "Больше всего: ${it.title}" } ?: if (day.minutes == 0) "Не введено" else "Введено вручную",
                        color = Palette.textSecondary, fontSize = 14.sp)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Formatters.duration(day.minutes), color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("${day.minutes * 100 / limit}% лимита", color = if (day.minutes > limit) Palette.red else Palette.green, fontSize = 13.sp)
                }
            }
            if (index < week.size - 1) DividerLine()
        }
    }
}
