package com.pulse.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.PulseSlider
import com.pulse.android.ui.components.PulseTextField
import com.pulse.android.ui.components.SectionHeader
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Palette
import com.pulse.core.PulseCore
import com.pulse.core.model.AboutYou
import com.pulse.core.model.AboutYouField
import com.pulse.core.model.AboutYouForm
import com.pulse.core.util.Formatters
import kotlin.time.Clock

const val ABOUT_YOU_TITLE = "О тебе"
const val ABOUT_YOU_SUBTITLE = "Возраст, рост и вес нужны для пульсовых зон и калорий, режим — для напоминаний о сне. Всё можно поменять в профиле."

/**
 * «О тебе» while it is filled in: the form plus the birth date exactly as typed («12.03.2004»). [revealed]: the player
 * pressed the button with a wrong answer, so every error is shown, not only those of fields already left.
 */
data class AboutYouDraft(val form: AboutYouForm, val birthText: String, val revealed: Boolean = false) {
    fun withBirthText(text: String) = copy(birthText = text, form = form.copy(birthDate = AboutYou.parseBirthDate(text)))

    val isValid: Boolean get() = AboutYou.errors(form, Clock.System.now()).isEmpty()

    companion object {
        fun start(core: PulseCore, isNewPlayer: Boolean): AboutYouDraft {
            val form = AboutYou.initial(core.sleep.window, core.fitness.goals, core.nutrition.goals,
                core.screenTime.state.value.dailyLimitMinutes, core.body.profile, isNewPlayer, core.fitness.currentWeightKg)
            return AboutYouDraft(form, form.birthDate?.let(AboutYou::birthDateText) ?: "")
        }
    }
}

/** Keeps the typed answers through rotation and process death. */
val AboutYouDraftSaver: Saver<AboutYouDraft, List<Any>> = Saver(
    save = { draft ->
        val f = draft.form
        listOf(draft.birthText, f.heightText, f.weightText, f.bedtimeHour, f.bedtimeMinute, f.wakeHour, f.wakeMinute, f.activeMinutes,
            f.steps, f.activeKcal, f.foodKcal, f.waterMl, f.screenLimitHours, f.suggestsFromWeight, f.kcalEdited, f.waterEdited, draft.revealed)
    },
    restore = { v ->
        val form = AboutYouForm(
            heightText = v[1] as String, weightText = v[2] as String, bedtimeHour = v[3] as Int, bedtimeMinute = v[4] as Int,
            wakeHour = v[5] as Int, wakeMinute = v[6] as Int, activeMinutes = v[7] as Int, steps = v[8] as Int, activeKcal = v[9] as Int,
            foodKcal = v[10] as Int, waterMl = v[11] as Int, screenLimitHours = v[12] as Int, suggestsFromWeight = v[13] as Boolean,
            kcalEdited = v[14] as Boolean, waterEdited = v[15] as Boolean
        )
        AboutYouDraft(form, "", revealed = v[16] as Boolean).withBirthText(v[0] as String)
    }
)

private val BODY_FIELDS = setOf(AboutYouField.BIRTH_DATE, AboutYouField.HEIGHT, AboutYouField.WEIGHT)

/** Errors that may already be shown: body fields only after the player has left them, or all once [AboutYouDraft.revealed]. */
fun visibleErrors(draft: AboutYouDraft, touched: Set<AboutYouField>): Map<AboutYouField, String> =
    AboutYou.errors(draft.form, Clock.System.now()).filterKeys { draft.revealed || it !in BODY_FIELDS || it in touched }

/** The button's action: [save] when every answer is right, otherwise show what is wrong. */
fun AboutYouDraft.submit(onChange: (AboutYouDraft) -> Unit, save: () -> Unit) = if (isValid) save() else onChange(copy(revealed = true))

/** «Тело», «Режим сна», «Цели на день»; the profile sheet shows the body only. */
@Composable
fun AboutYouFields(draft: AboutYouDraft, onChange: (AboutYouDraft) -> Unit, schedule: Boolean = true) {
    var touched by rememberSaveable { mutableStateOf(emptySet<AboutYouField>()) }
    val errors = visibleErrors(draft, touched)
    val form = draft.form
    /** Errors of a typed field appear once the player leaves it, not while typing «18» on the way to «182» — also when coming back to it. */
    fun leaves(field: AboutYouField, text: String) = Modifier.onFocusChanged { state ->
        when {
            state.hasFocus -> touched = touched - field
            text.isNotEmpty() -> touched = touched + field
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeader("Тело")
        Box(leaves(AboutYouField.BIRTH_DATE, draft.birthText)) {
            PulseTextField("Дата рождения", draft.birthText, "ДД.ММ.ГГГГ", KeyboardType.Number, error = errors[AboutYouField.BIRTH_DATE]) {
                onChange(draft.withBirthText(it.filter { c -> c.isDigit() || c == '.' }.take(10)))
            }
        }
        Box(leaves(AboutYouField.HEIGHT, form.heightText)) {
            PulseTextField("Рост, см", form.heightText, "Например, 182", KeyboardType.Number, error = errors[AboutYouField.HEIGHT]) {
                onChange(draft.copy(form = form.copy(heightText = it.filter(Char::isDigit).take(3))))
            }
        }
        Box(leaves(AboutYouField.WEIGHT, form.weightText)) {
            PulseTextField("Вес, кг", form.weightText, "Например, 74", KeyboardType.Decimal, error = errors[AboutYouField.WEIGHT]) {
                onChange(draft.copy(form = form.withWeightText(it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(5))))
            }
        }
        if (!schedule) return@Column
        SectionHeader("Режим сна")
        Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeRow("Отбой", form.bedtimeHour, form.bedtimeMinute) { h, m -> onChange(draft.copy(form = form.copy(bedtimeHour = h, bedtimeMinute = m))) }
            TimeRow("Подъём", form.wakeHour, form.wakeMinute) { h, m -> onChange(draft.copy(form = form.copy(wakeHour = h, wakeMinute = m))) }
            errors[AboutYouField.WAKE]?.let { Text(it, color = Palette.red, fontSize = 13.sp) }
        }
        SectionHeader("Цели на день")
        Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            fun range(r: IntRange) = r.first.toDouble()..r.last.toDouble()
            PulseSlider("Активные минуты в день", form.activeMinutes.toDouble(), range(AboutYou.ACTIVE_MINUTES), Palette.orange) {
                onChange(draft.copy(form = form.copy(activeMinutes = it.toInt())))
            }
            PulseSlider("Шаги в день", form.steps.toDouble(), range(AboutYou.STEPS), Palette.green, AboutYou.STEPS_STEP.toDouble(),
                valueText = Formatters.grouped(form.steps)) { onChange(draft.copy(form = form.copy(steps = it.toInt()))) }
            PulseSlider("Активные ккал в день", form.activeKcal.toDouble(), range(AboutYou.ACTIVE_KCAL), Palette.orange, 50.0) {
                onChange(draft.copy(form = form.copy(activeKcal = it.toInt())))
            }
            PulseSlider("Калории в день", form.foodKcal.toDouble(), range(AboutYou.FOOD_KCAL), Palette.cyan, 50.0,
                valueText = Formatters.grouped(form.foodKcal)) { onChange(draft.copy(form = form.withFoodKcal(it.toInt()))) }
            PulseSlider("Вода в день, л", form.waterMl / 1000.0, AboutYou.WATER_ML.first / 1000.0..AboutYou.WATER_ML.last / 1000.0, Palette.blue,
                AboutYou.WATER_STEP_ML / 1000.0, valueText = liters(form.waterMl)) { onChange(draft.copy(form = form.withWater((it * 1000).toInt()))) }
            PulseSlider("Лимит экранного времени, ч", form.screenLimitHours.toDouble(), range(AboutYou.SCREEN_LIMIT_HOURS), Palette.purple) {
                onChange(draft.copy(form = form.copy(screenLimitHours = it.toInt())))
            }
        }
    }
}

/** «2,5 л», «2,75 л», «3 л». */
private fun liters(ml: Int): String = Formatters.decimal(ml / 1000.0, if (ml % 1000 == 0) 0 else if (ml % 100 == 0) 1 else 2) + " л"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRow(title: String, hour: Int, minute: Int, onChange: (Int, Int) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val text = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Palette.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(text, color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.track).pressable { picking = true }
                .semantics { contentDescription = "$title: $text" }.padding(horizontal = 14.dp, vertical = 10.dp))
    }
    if (picking) {
        val state = rememberTimePickerState(hour, minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            containerColor = Palette.tile,
            text = {
                TimePicker(state, colors = TimePickerDefaults.colors(
                    clockDialColor = Palette.track, selectorColor = Palette.blue, clockDialSelectedContentColor = Color.White,
                    clockDialUnselectedContentColor = Palette.textPrimary, timeSelectorSelectedContainerColor = Palette.blue,
                    timeSelectorUnselectedContainerColor = Palette.track, timeSelectorSelectedContentColor = Color.White,
                    timeSelectorUnselectedContentColor = Palette.textPrimary
                ))
            },
            confirmButton = { TextButton({ onChange(state.hour, state.minute); picking = false }) { Text("Готово", color = Palette.cyan) } },
            dismissButton = { TextButton({ picking = false }) { Text("Отмена", color = Palette.textSecondary) } }
        )
    }
}

/** Профиль → «О тебе»: birth date, height and weight; goals and the sleep window keep their own editors. */
@Composable
fun AboutYouSheet(onClose: () -> Unit) {
    val core = observeCore()
    var draft by rememberSaveable(stateSaver = AboutYouDraftSaver) { mutableStateOf(AboutYouDraft.start(core, isNewPlayer = false)) }
    SheetScaffold(ABOUT_YOU_TITLE, onClose) {
        AboutYouFields(draft, { draft = it }, schedule = false)
        GradientButton("Сохранить", dimmed = !draft.isValid) {
            draft.submit({ draft = it }) { if (core.updateBody(draft.form)) onClose() }
        }
    }
}
