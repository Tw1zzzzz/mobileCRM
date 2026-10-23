package com.pulse.android.ui.profile

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.GradientBar
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.components.PulseSlider
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.fit
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.components.switchRow
import com.pulse.android.ui.health.SleepWindowSheet
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Gradients
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.model.FitnessGoals
import com.pulse.core.model.GameGoals
import com.pulse.core.model.GameSummary
import com.pulse.core.model.GoalProgress
import com.pulse.core.model.NutritionGoals
import com.pulse.core.util.Formatters
import com.pulse.core.util.roundToIntSwift

private enum class GoalEditor { GAME, FITNESS, NUTRITION, SLEEP, SCREEN }

/** Профиль → «Цели»: one row per area with the current targets; a tap opens its editor. */
@Composable
fun GoalsSection() {
    val core = observeCore()
    var editor by remember { mutableStateOf<GoalEditor?>(null) }
    val game = core.gameGoals.goals
    val gameSummary = listOfNotNull(
        game.elo?.let { "ELO ${Formatters.grouped(it)}" },
        game.kd?.let { "K/D ${Formatters.decimal(it, 2)}" },
        game.adr?.let { "ADR ${Formatters.decimal(it)}" },
        game.winRate?.let { "победы $it%" }
    ).joinToString(" · ").ifEmpty { "Не заданы" }
    val fitness = core.fitness.goals
    val nutrition = core.nutrition.goals
    val window = core.sleep.window
    PulseSection("Цели") {
        Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
            GoalRow("gamecontroller", Palette.cyan, "Игра", gameSummary) { editor = GoalEditor.GAME }
            DividerLine()
            GoalRow("figure.run", Palette.orange, "Фитнес",
                "${fitness.activeMinutes} мин · ${Formatters.grouped(fitness.steps)} шагов · ${Formatters.grouped(fitness.activeKcal)} ккал") { editor = GoalEditor.FITNESS }
            DividerLine()
            GoalRow("fork.knife", Palette.green, "Питание",
                "${Formatters.grouped(nutrition.kcal)} ккал · вода ${Formatters.liters(nutrition.waterMilliliters)} л") { editor = GoalEditor.NUTRITION }
            DividerLine()
            GoalRow("moon", Palette.purple, "Сон", "${window.bedtimeText} → ${window.wakeText} · ${Formatters.duration(window.durationMinutes)}") { editor = GoalEditor.SLEEP }
            DividerLine()
            GoalRow("hourglass", Palette.cyan, "Экранное время", "не больше ${core.screenTime.state.value.dailyLimitMinutes / 60} ч в день") { editor = GoalEditor.SCREEN }
        }
    }
    editor?.let { current ->
        val close = { editor = null }
        PulseSheet(close) {
            when (current) {
                GoalEditor.GAME -> GameGoalsSheet(close)
                GoalEditor.FITNESS -> FitnessGoalsSheet(close)
                GoalEditor.NUTRITION -> NutritionGoalsSheet(close)
                GoalEditor.SLEEP -> SleepWindowSheet(close)
                GoalEditor.SCREEN -> ScreenLimitSheet(close)
            }
        }
    }
}

@Composable
private fun GoalRow(icon: String, tint: Color, title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.pressable(onClick = onClick).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(sfIcon(icon), tint, 36.dp, 16.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Palette.textPrimary, fontSize = 16.sp)
            Text(value, color = Palette.textSecondary, autoSize = fit(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(sfIcon("chevron.right"), null, tint = Palette.textTertiary, modifier = Modifier.size(15.dp))
    }
}

/** Progress towards the player's CS2 targets (Профиль and «Состояние → Игра»). */
@Composable
fun GameGoalsCard(onEdit: () -> Unit) {
    val core = observeCore()
    val user = core.session.user.value
    val game = core.game
    val goals = core.gameGoals
    val recent = GameSummary.of(game.recentMatches(user, GameGoals.RECENT_MATCHES))
    val rows = if (game.hasData) goals.progress(game.profile(user).elo, recent) else emptyList()
    Column(Modifier.pulseCard(padding = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            !game.hasData && !goals.goals.isEmpty -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(sfIcon("link"), null, tint = Palette.orange, modifier = Modifier.size(22.dp))
                Text("Цели сохранены. Подключи Faceit в «Состояние → Игра», чтобы видеть прогресс по матчам.", color = Palette.textSecondary, fontSize = 14.sp)
            }
            rows.isEmpty() -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(sfIcon("target"), null, tint = Palette.cyan, modifier = Modifier.size(24.dp))
                    Text("Поставь цель — например, ELO 3 000 или K/D 1,2 — и следи за прогрессом.", color = Palette.textSecondary, fontSize = 14.sp)
                }
                GradientButton("Поставить цель", icon = sfIcon("target"), onClick = onEdit)
            }
            else -> {
                rows.forEach { GoalProgressRow(it) }
                Text("K/D, ADR и победы — по последним ${GameGoals.RECENT_MATCHES} матчам", color = Palette.textTertiary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun GoalProgressRow(row: GoalProgress) {
    val tint = if (row.isReached) Palette.green else Palette.cyan
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(row.title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(row.current, color = tint, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(" / ${row.target}", color = Palette.textSecondary, fontSize = 15.sp)
        }
        GradientBar(row.progress, height = 8.dp, fill = if (row.isReached) Brush.horizontalGradient(listOf(Palette.green, Palette.green)) else Gradients.accent)
        Text(row.note, color = if (row.isReached) Palette.green else Palette.textSecondary, fontSize = 13.sp)
    }
}

/** Slider with a formatted value for targets that are not small integers (K/D, steps, litres). */
@Composable
private fun DecimalSlider(
    title: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double, digits: Int = 1, suffix: String = "",
    tint: Color = Palette.cyan, onChange: (Double) -> Unit
) {
    val text = (if (digits == 0) Formatters.grouped(value.roundToIntSwift()) else Formatters.decimal(value, digits)) + suffix
    PulseSlider(title, value, range, tint, step, valueText = text, onChange = onChange)
}

@Composable
fun GameGoalsSheet(onClose: () -> Unit) {
    val core = observeCore()
    val user = core.session.user.value
    val player = core.game.profile(user)
    val recent = GameSummary.of(core.game.recentMatches(user, GameGoals.RECENT_MATCHES))
    val goals = core.gameGoals.goals
    var useElo by remember { mutableStateOf(goals.elo != null) }
    var elo by remember { mutableDoubleStateOf((goals.elo ?: ((player.elo / 100 + 2) * 100)).toDouble()) }
    var useKd by remember { mutableStateOf(goals.kd != null) }
    var kd by remember { mutableDoubleStateOf(goals.kd ?: 1.1) }
    var useAdr by remember { mutableStateOf(goals.adr != null) }
    var adr by remember { mutableDoubleStateOf(goals.adr ?: 85.0) }
    var useWinRate by remember { mutableStateOf(goals.winRate != null) }
    var winRate by remember { mutableDoubleStateOf((goals.winRate ?: 55).toDouble()) }
    // Targets start just above the current ELO, rounded to 25.
    val eloLow = ((player.elo / 25 + 1) * 25).toDouble()
    SheetScaffold("Игровые цели", onClose) {
        Text(
            "Включи нужные цели. Сейчас: ELO ${Formatters.grouped(player.elo)} · K/D ${Formatters.decimal(recent.kd, 2)} · ADR ${Formatters.decimal(recent.adr)} · победы ${recent.winRate.roundToIntSwift()}%",
            color = Palette.textSecondary, fontSize = 14.sp
        )
        GoalToggleCard("ELO Faceit", useElo, { useElo = it }) {
            DecimalSlider("Цель", elo.coerceIn(eloLow, eloLow + 1500), eloLow..eloLow + 1500, 25.0, 0) { elo = it }
        }
        GoalToggleCard("K/D", useKd, { useKd = it }) { DecimalSlider("Цель", kd, 0.5..2.5, 0.05, 2, tint = Palette.green) { kd = it } }
        GoalToggleCard("ADR", useAdr, { useAdr = it }) { DecimalSlider("Цель", adr, 50.0..130.0, 1.0, 0, tint = Palette.orange) { adr = it } }
        GoalToggleCard("Процент побед", useWinRate, { useWinRate = it }) {
            DecimalSlider("Цель", winRate, 30.0..90.0, 1.0, 0, "%", Palette.purple) { winRate = it }
        }
        GradientButton("Сохранить", icon = sfIcon("checkmark")) {
            core.gameGoals.update(
                GameGoals(
                    elo = if (useElo) elo.coerceIn(eloLow, eloLow + 1500).toInt() else null,
                    kd = if (useKd) kd else null,
                    adr = if (useAdr) adr else null,
                    winRate = if (useWinRate) winRate.toInt() else null
                ),
                currentElo = player.elo
            )
            onClose()
        }
    }
}

@Composable
private fun GoalToggleCard(title: String, isOn: Boolean, onToggle: (Boolean) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.pulseCard(padding = 16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.switchRow(isOn, onToggle), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Switch(isOn, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = Palette.blue, checkedThumbColor = Color.White))
        }
        if (isOn) content()
    }
}

@Composable
private fun FitnessGoalsSheet(onClose: () -> Unit) {
    val store = observeCore().fitness
    var minutes by remember { mutableDoubleStateOf(store.goals.activeMinutes.toDouble()) }
    var steps by remember { mutableDoubleStateOf(store.goals.steps.toDouble()) }
    var kcal by remember { mutableDoubleStateOf(store.goals.activeKcal.toDouble()) }
    SheetScaffold("Цели фитнеса", onClose) {
        Column(Modifier.pulseCard(padding = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            PulseSlider("Активные минуты в день", minutes, 10.0..180.0, Palette.orange) { minutes = it }
            DecimalSlider("Шаги в день", steps, 2000.0..30000.0, 500.0, 0, tint = Palette.green) { steps = it }
            DecimalSlider("Активные калории, ккал", kcal, 100.0..2000.0, 50.0, 0, tint = Palette.red) { kcal = it }
        }
        Text("ВОЗ рекомендует 150–300 минут умеренной активности в неделю — это 30–60 минут в день.", color = Palette.textTertiary, fontSize = 13.sp)
        GradientButton("Сохранить", icon = sfIcon("checkmark")) {
            store.updateGoals(FitnessGoals(minutes.toInt(), steps.toInt(), kcal.toInt()))
            onClose()
        }
    }
}

@Composable
private fun NutritionGoalsSheet(onClose: () -> Unit) {
    val store = observeCore().nutrition
    val goals = store.goals
    var kcal by remember { mutableDoubleStateOf(goals.kcal.toDouble()) }
    var protein by remember { mutableDoubleStateOf(goals.protein.toDouble()) }
    var fat by remember { mutableDoubleStateOf(goals.fat.toDouble()) }
    var carbs by remember { mutableDoubleStateOf(goals.carbs.toDouble()) }
    var water by remember { mutableDoubleStateOf(goals.waterMilliliters / 1000.0) }
    SheetScaffold("Цели питания", onClose) {
        Column(Modifier.pulseCard(padding = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            DecimalSlider("Калории, ккал", kcal, 1200.0..4500.0, 50.0, 0) { kcal = it }
            PulseSlider("Белки, г", protein, 40.0..300.0, Palette.cyan) { protein = it }
            PulseSlider("Жиры, г", fat, 20.0..200.0, Palette.orange) { fat = it }
            PulseSlider("Углеводы, г", carbs, 50.0..600.0, Palette.purple) { carbs = it }
            DecimalSlider("Вода, л", water, 1.0..5.0, 0.25, 2) { water = it }
        }
        GradientButton("Сохранить", icon = sfIcon("checkmark")) {
            store.updateGoals(NutritionGoals(kcal.toInt(), protein.toInt(), fat.toInt(), carbs.toInt(), (water * 1000).toInt()))
            onClose()
        }
    }
}

@Composable
private fun ScreenLimitSheet(onClose: () -> Unit) {
    val store = observeCore().screenTime
    var hours by remember { mutableDoubleStateOf(store.state.value.dailyLimitMinutes / 60.0) }
    SheetScaffold("Экранное время", onClose) {
        Column(Modifier.pulseCard(padding = 18.dp)) { PulseSlider("Лимит в день, ч", hours, 1.0..12.0, Palette.cyan) { hours = it } }
        GradientButton("Сохранить", icon = sfIcon("checkmark")) {
            store.setLimit(hours.toInt() * 60)
            onClose()
        }
    }
}
