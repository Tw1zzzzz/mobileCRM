package com.pulse.android.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.Callout
import com.pulse.android.ui.components.ChipButton
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.ErrorBanner
import com.pulse.android.ui.components.GradientBar
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.HeaderIconButton
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseBarChart
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.PulseSlider
import com.pulse.android.ui.components.PulseTextField
import com.pulse.android.ui.components.ScoreRing
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.ValueUnitText
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.color
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.model.HeartRateZone
import com.pulse.core.model.TrainingLoadMath
import com.pulse.core.model.Workout
import com.pulse.core.model.WorkoutKind
import com.pulse.core.store.FitnessStore
import com.pulse.core.util.Formatters
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.isSameDay
import com.pulse.core.util.roundToIntSwift
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Mockup 4 — «Фитнес» (сегодня): кольцо активности, тренировки, нагрузка, зоны пульса. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitnessScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.fitness
    val now = Clock.System.now()
    var logWorkout by remember { mutableStateOf(false) }
    var rating by remember { mutableStateOf<Workout?>(null) }
    LaunchedEffect(Unit) { store.refreshHealth() }
    ScreenScaffold(header = {
        NavHeader("Фитнес", onBack = { router.pop() }) { HeaderIconButton("plus", "Записать тренировку") { logWorkout = true } }
    }) {
        HealthSourceCard()
        HeroCard(store, now)
        PulseSection("Восстановление") { RecoveryCard() }
        PulseSection("Тренировки сегодня", "История", { router.open(Route.FitnessWeek) }) {
            Column(Modifier.pulseCard()) {
                val workouts = store.todayWorkouts(now)
                if (workouts.isEmpty()) Text("Сегодня тренировок ещё не было", color = Palette.textSecondary, fontSize = 16.sp, modifier = Modifier.padding(vertical = 18.dp))
                workouts.forEachIndexed { index, workout ->
                    Box(Modifier.pressable { rating = workout }) { WorkoutRow(workout, store.effort(workout)) }
                    if (index < workouts.size - 1) DividerLine()
                }
                GradientButton("Записать тренировку", Modifier.padding(top = 16.dp), icon = sfIcon("plus")) { logWorkout = true }
            }
        }
        PulseSection("Нагрузка") { TrainingLoadCard() }
        PulseSection("Зоны пульса") {
            val zones = store.heartRateZones
            val average = store.averageHeartRate
            if (zones != null && average != null) HeartRateZonesCard(zones, average) else NoHeartRateCard(store.healthName)
        }
    }
    if (logWorkout) ModalBottomSheet(onDismissRequest = { logWorkout = false }, containerColor = Palette.background) {
        LogWorkoutSheet({ logWorkout = false }) { store.add(it) }
    }
    rating?.let { workout ->
        ModalBottomSheet(onDismissRequest = { rating = null }, containerColor = Palette.background) {
            RateWorkoutSheet(workout, store) { rating = null }
        }
    }
}

@Composable
private fun HeroCard(store: FitnessStore, now: kotlin.time.Instant) {
    val minutes = store.activeMinutesToday(now)
    val goal = store.goals.activeMinutes
    Column(Modifier.pulseCard(padding = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(minutes.toDouble() / goal, "$minutes", "АКТ. МИН", size = 124.dp, lineWidth = 12.dp, valueSize = 44.sp)
            Box(Modifier.width(1.dp).height(80.dp).background(Palette.divider))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("СЕГОДНЯ", color = Palette.cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                Text("$minutes / $goal мин", color = Palette.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(if (minutes >= goal) "Цель выполнена 🎉" else "Ещё ${goal - minutes} мин до цели", color = Palette.textSecondary, fontSize = 16.sp, maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniTile("flame", Palette.orange, "${store.kcalToday(now)}", "ккал", Modifier.weight(1f))
            MiniTile("shoe", Palette.blue, Formatters.decimal(store.distanceKm), "км", Modifier.weight(1f))
            MiniTile("shoeprints.fill", Palette.green, Formatters.grouped(store.steps), "шагов", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniTile(icon: String, tint: Color, value: String, unit: String, modifier: Modifier) {
    Column(
        modifier.heightIn(min = 88.dp).clip(RoundedCornerShape(16.dp)).background(Palette.background.copy(alpha = 0.35f)).padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(sfIcon(icon), null, tint = tint, modifier = Modifier.size(22.dp))
        ValueUnitText(value, unit, valueSize = 18.sp, unitSize = 12.sp)
    }
}

@Composable
fun WorkoutRow(workout: Workout, effort: FitnessStore.Effort?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(sfIcon(workout.kind.icon), workout.kind.tint.color, 50.dp, 22.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(workout.title, color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(if (workout.averageHeartRate > 0) "${workout.minutes} мин · ${workout.kcal} ккал · ${workout.averageHeartRate} уд/мин" else "${workout.minutes} мин · ${workout.kcal} ккал",
                color = Palette.textSecondary, fontSize = 14.sp)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Formatters.time(workout.date), color = Palette.textSecondary, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            effort?.let {
                Text("${if (it.isEstimated) "≈" else ""}RPE ${it.value}", color = if (it.isEstimated) Palette.textSecondary else Palette.cyan, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.clip(CircleShape).background(Palette.track).padding(horizontal = 7.dp, vertical = 3.dp))
            }
        }
    }
}

/** «Нагрузка»: ACWR status, last-7 vs usual week and a daily load chart (hold to inspect a day). */
@Composable
fun TrainingLoadCard() {
    val core = observeCore()
    val daily = core.fitness.dailyLoad()
    val ratio = TrainingLoadMath.ratio(daily)
    val recent = daily.takeLast(14)
    Column(Modifier.pulseCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ACWR", color = Palette.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
                Text(ratio.value?.let { Formatters.decimal(it, 2) } ?: "—", color = ratio.status.tint.color, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ratio.status.title, color = ratio.status.tint.color, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(ratio.status.tint.color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 5.dp))
                Text("7 дней: ${Formatters.grouped(ratio.acute.toInt())} · обычно ${Formatters.grouped(ratio.chronicWeekly.toInt())}", color = Palette.textSecondary, fontSize = 13.sp, maxLines = 1)
            }
        }
        PulseBarChart(
            recent.map { it.value }, recent.mapIndexed { i, d -> if (i % 2 == 1) Formatters.weekdayShort(d.date) else "" },
            calloutFor = { i -> Callout(Formatters.dayMonthWeekday(recent[i].date), if (recent[i].value > 0) "${Formatters.grouped(recent[i].value.toInt())} ед." else "отдых", Palette.cyan) },
            height = 130.dp
        )
        Text(ratio.status.advice, color = Palette.textSecondary, fontSize = 14.sp)
        Text("Нагрузка = тяжесть тренировки (1–10) × минуты. Норма ACWR — 0,8–1,3. Оцени тяжесть, нажав на тренировку.", color = Palette.textTertiary, fontSize = 12.sp)
    }
}

/** 1–10 slider with the meaning of the chosen level. */
@Composable
fun EffortPicker(value: Double, onChange: (Double) -> Unit) {
    val level = value.toInt()
    val tint = when {
        level < 4 -> Palette.green
        level < 7 -> Palette.yellow
        level < 9 -> Palette.orange
        else -> Palette.red
    }
    val description = when {
        level < 2 -> "Очень легко — почти как отдых"
        level < 4 -> "Легко — можно спокойно разговаривать"
        level < 6 -> "Умеренно — дыхание учащено, но комфортно"
        level < 8 -> "Тяжело — говорить трудно"
        level < 10 -> "Очень тяжело — на пределе"
        else -> "Максимум — больше сделать невозможно"
    }
    Column(Modifier.pulseCard(padding = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row {
            Text("Насколько было тяжело?", color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("$level/10", color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        PulseSlider("", value, 1.0..10.0, tint, onChange = onChange)
        Text(description, color = Palette.textSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun RateWorkoutSheet(workout: Workout, store: FitnessStore, onClose: () -> Unit) {
    var value by remember { mutableDoubleStateOf(store.effort(workout).value.toDouble()) }
    SheetScaffold("Тяжесть тренировки", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(workout.title, color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("${Formatters.dayMonthTime(workout.date)} · ${workout.minutes} мин", color = Palette.textSecondary, fontSize = 14.sp)
        }
        EffortPicker(value) { value = it }
        GradientButton("Сохранить", icon = sfIcon("checkmark")) {
            store.rate(workout, value.toInt())
            onClose()
        }
    }
}

@Composable
private fun LogWorkoutSheet(onClose: () -> Unit, onSave: (Workout) -> Unit) {
    var kind by remember { mutableStateOf(WorkoutKind.STRENGTH) }
    var detail by remember { mutableStateOf("") }
    var minutes by remember { mutableDoubleStateOf(30.0) }
    var kcal by remember { mutableDoubleStateOf(200.0) }
    var heartRate by remember { mutableDoubleStateOf(130.0) }
    var effort by remember { mutableDoubleStateOf(6.0) }
    val now = Clock.System.now()
    var hoursAgo by remember { mutableDoubleStateOf(0.0) }
    SheetScaffold("Новая тренировка", onClose) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkoutKind.entries.forEach { item -> ChipButton(item.title, item == kind) { kind = item } }
        }
        PulseTextField("Описание", detail, "например, верх / ноги") { detail = it }
        Column(Modifier.pulseCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PulseSlider("Часов назад", hoursAgo, 0.0..12.0, Palette.purple) { hoursAgo = it }
            PulseSlider("Длительность, мин", minutes, 5.0..180.0) { minutes = it }
            PulseSlider("Калории, ккал", kcal, 10.0..1500.0, Palette.orange, step = 10.0) { kcal = it }
            PulseSlider("Средний пульс", heartRate, 60.0..200.0, Palette.red) { heartRate = it }
        }
        EffortPicker(effort) { effort = it }
        GradientButton("Сохранить тренировку") {
            onSave(Workout(Uuid.random(), kind, detail.trim(), now - kotlin.time.Duration.parse("${hoursAgo.toInt()}h"),
                minutes.toInt(), kcal.toInt(), heartRate.toInt(), effort.toInt()))
            onClose()
        }
    }
}

@Composable
private fun HeartRateZonesCard(zones: List<HeartRateZone>, average: Int) {
    Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(sfIcon("heart"), null, tint = Palette.red, modifier = Modifier.size(26.dp))
            Text("Средний пульс", color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
            ValueUnitText("$average", "уд/мин", valueSize = 30.sp, unitSize = 15.sp)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape)) {
            val full = maxWidth
            Row {
                zones.forEach { zone -> Box(Modifier.width(full * zone.percent / 100f).fillMaxHeight().background(zone.tint.color)) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            zones.forEach { zone ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(zone.tint.color))
                    Text(zone.name, color = Palette.textPrimary, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 1)
                    Text(zone.range, color = Palette.textSecondary, fontSize = 16.sp)
                    Text("${zone.percent}%", color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun NoHeartRateCard(healthName: String) {
    Row(Modifier.pulseCard(padding = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(sfIcon("applewatch"), null, tint = Palette.red, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Сегодня нет данных о пульсе", color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Зоны пульса появятся с часами или трекером, который пишет пульс в $healthName.", color = Palette.textSecondary, fontSize = 14.sp)
        }
    }
}

/** Top of «Фитнес»: connect the health store, or show where the numbers come from. */
@Composable
fun HealthSourceCard() {
    val core = observeCore()
    val store = core.fitness
    val state = store.state.value
    val scope = rememberCoroutineScope()
    val heart = Color(0xFFFF375F)
    if (state.isHealthConnected) {
        Column(Modifier.pulseCard(padding = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(sfIcon("heart.fill"), null, tint = heart, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(store.healthName, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.health?.let { "Обновлено в ${Formatters.time(it.fetchedAt)}" } ?: if (state.isSyncing) "Загружаем данные…" else "Подключено",
                        color = Palette.textSecondary, fontSize = 13.sp)
                }
                Text("Демо", color = Palette.textSecondary, fontSize = 14.sp, modifier = Modifier.pressable { store.disconnectHealth() })
            }
            state.healthError?.let { ErrorBanner(it) }
        }
    } else {
        Column(Modifier.pulseCard(padding = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(sfIcon("heart.fill"), null, tint = heart, modifier = Modifier.size(20.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Подключи ${store.healthName}", color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (state.isDemo) "Шаги, калории, активные минуты, пульс и тренировки подтянутся автоматически. Сейчас показаны демо-данные."
                        else "Шаги, калории, активные минуты, пульс, сон и тренировки подтянутся автоматически.", color = Palette.textSecondary, fontSize = 14.sp)
                }
            }
            state.healthError?.let { ErrorBanner(it) }
            GradientButton("Подключить", icon = sfIcon("heart.fill"), enabled = store.isHealthAvailable) { scope.launch { store.connectHealth() } }
            Text(if (store.isHealthAvailable) "Pulse только читает данные и не меняет их. Доступ можно отозвать в настройках Health Connect."
                else "Подключение Health Connect появится в следующем обновлении (шаг 4).", color = Palette.textTertiary, fontSize = 12.sp)
        }
    }
}

/** Mockup 3 — «Фитнес · Недельный прогресс». */
@Composable
fun FitnessWeekScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.fitness
    val now = Clock.System.now()
    val days = store.weeklyActiveMinutes(now)
    val goal = store.goals.activeMinutes
    ScreenScaffold(header = {
        NavHeader("Фитнес", "Недельный прогресс", onBack = { router.pop() }) { HeaderIconButton("calendar", "Календарь") { router.open(Route.Calendar()) } }
    }) {
        PulseSection("Объём за неделю") {
            Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Активные минуты", color = Palette.textPrimary, fontSize = 18.sp, modifier = Modifier.weight(1f).alignByBaseline())
                    Text("${days.sumOf { it.value }.toInt()}", color = Palette.textPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
                    Text(" / ${goal * 7} мин", color = Palette.textSecondary, fontSize = 18.sp, modifier = Modifier.alignByBaseline())
                }
                PulseBarChart(
                    days.map { it.value }, days.map { Formatters.weekdayShort(it.date) },
                    calloutFor = { i ->
                        val left = goal - days[i].value.toInt()
                        Callout(Formatters.dayMonthWeekday(days[i].date), listOf("${days[i].value.toInt()} активных мин" to Palette.cyan,
                            (if (left > 0) "до цели $left мин" else "цель выполнена") to (if (left > 0) Palette.textSecondary else Palette.green)))
                    },
                    height = 180.dp, highlight = days.indexOfFirst { it.date.isSameDay(now) }.takeIf { it >= 0 }, goal = goal.toDouble()
                )
            }
        }
        PulseSection("Цели") {
            Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 18.dp)) {
                GoalRow("shoeprints.fill", Palette.green, "Шаги", store.steps, store.goals.steps, null)
                DividerLine()
                GoalRow("bolt", Palette.orange, "Активные минуты", store.activeMinutesToday(now), goal, "мин")
                DividerLine()
                GoalRow("drop", Palette.red, "Калории", store.kcalToday(now), store.goals.activeKcal, "ккал")
            }
        }
        PulseSection("Тело", "Детали", { router.open(Route.Statistics) }) {
            val metrics = store.bodyMetrics
            if (metrics.isEmpty()) {
                Text("Вес и состав тела появятся, когда их запишут в ${store.healthName} (умные весы или вручную).", color = Palette.textSecondary, fontSize = 14.sp,
                    modifier = Modifier.pulseCard(padding = 16.dp))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    metrics.forEach { metric ->
                        Column(Modifier.weight(1f).pulseCard(16.dp, Metrics.tileRadius), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(metric.title, color = Palette.textSecondary, fontSize = 16.sp)
                            ValueUnitText(Formatters.decimal(metric.value), metric.unit, valueSize = 26.sp, unitSize = 14.sp)
                            metric.delta?.let { Text(Formatters.signedDecimal(it), color = Palette.green, fontSize = 16.sp, fontFamily = FontFamily.Monospace) }
                        }
                    }
                }
            }
        }
        LinkCard("sparkle", "ВОССТАНОВЛЕНИЕ", "Отслеживай нагрузку и самочувствие") { router.open(Route.SleepRecovery) }
    }
}

@Composable
private fun GoalRow(icon: String, tint: Color, title: String, current: Int, goal: Int, unit: String?) {
    val progress = StatisticsMath.clampedProgress(current.toDouble(), goal.toDouble())
    Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(sfIcon(icon), tint, 52.dp, 22.dp)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, color = Palette.textPrimary, fontSize = 17.sp, modifier = Modifier.weight(1f).alignByBaseline(), maxLines = 1)
                Text(Formatters.grouped(current), color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
                Text(" / ${Formatters.grouped(goal)}${unit?.let { " $it" } ?: ""}", color = Palette.textSecondary, fontSize = 16.sp, modifier = Modifier.alignByBaseline())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                GradientBar(progress, Modifier.weight(1f), height = 10.dp)
                Text("${(progress * 100).roundToIntSwift()}%", color = Palette.textSecondary, fontSize = 15.sp)
            }
        }
    }
}
