package com.pulse.android.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.components.ChipButton
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.HeaderIconButton
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.PulseSegmented
import com.pulse.android.ui.components.PulseSlider
import com.pulse.android.ui.components.PulseTextField
import com.pulse.android.ui.components.ScreenBackground
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.color
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.model.FocusGoal
import com.pulse.core.model.MindsetProfile
import com.pulse.core.model.MindsetTrait
import com.pulse.core.model.PlayTime
import com.pulse.core.model.PlayerGameRole
import com.pulse.core.model.PlayerMotivation
import com.pulse.core.model.PlayerSurvey
import com.pulse.core.model.SkillLevel
import com.pulse.core.model.UserRole
import com.pulse.core.util.Formatters

private enum class Step { WELCOME, GOALS, PLAYER, ABOUT, TEST_INTRO, TEST, RESULT }

/** FULL — first login; TEST_ONLY — «Пройти заново»; ABOUT_ONLY — «О тебе» once for players who answered the rest earlier. */
enum class OnboardingMode { FULL, TEST_ONLY, ABOUT_ONLY }

const val MINDSET_DISCLAIMER = "Это самооценка игровых качеств, а не психологическая или медицинская диагностика. Результат меняется — проходи тест раз в месяц."

/**
 * First-login questionnaire: goals → player type → «О тебе» → «Игровой характер» test → result. «О тебе» is mandatory:
 * no close button on it, and in [OnboardingMode.ABOUT_ONLY] no way out but saving or signing out. The «О тебе» answers
 * ([about]) are held by the caller: state saved inside the dialog window is not restored after recreation.
 */
@Composable
fun OnboardingFlow(mode: OnboardingMode, about: AboutYouDraft, onAbout: (AboutYouDraft) -> Unit, onClose: () -> Unit) {
    val core = observeCore()
    val store = core.survey
    val user = core.session.user.value
    val testOnly = mode == OnboardingMode.TEST_ONLY
    var aboutOnly by rememberSaveable { mutableStateOf(mode == OnboardingMode.ABOUT_ONLY) }
    var step by rememberSaveable {
        mutableStateOf(when (mode) { OnboardingMode.TEST_ONLY -> Step.TEST_INTRO; OnboardingMode.ABOUT_ONLY -> Step.ABOUT; else -> Step.WELCOME })
    }
    var draft by remember { mutableStateOf(store.survey ?: PlayerSurvey()) }
    var eloTarget by remember { mutableStateOf(core.gameGoals.goals.elo?.toString() ?: "") }
    var answers by remember { mutableStateOf(emptyMap<String, Int>()) }
    var result by remember { mutableStateOf<MindsetProfile?>(null) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    LaunchedEffect(step) { scroll.scrollTo(0) } // every step starts at the top
    val sharingNote = if (user?.role == UserRole.TEAM_PLAYER) "Профиль увидишь ты и тренер команды — так ему проще понять, как с тобой работать."
    else "Профиль видишь только ты."

    fun close() {
        if (store.needsOnboarding) store.dismissOnboarding()
        // «Позже» postpones the questionnaire, not «О тебе».
        if (step == Step.WELCOME && core.needsAboutYou) {
            aboutOnly = true
            step = Step.ABOUT
        } else {
            onClose()
        }
    }

    /** Spreads «О тебе» over the metrics (the core rebuilds Health data with the new age and weight), or shows what is wrong. */
    fun saveAbout(then: () -> Unit) = about.submit(onAbout, { focus.clearFocus(); scope.launch { scroll.animateScrollTo(0) } }) {
        if (core.applyAboutYou(about.form)) then()
    }

    fun saveSurvey() {
        store.saveSurvey(draft)
        val elo = eloTarget.filter(Char::isDigit).toIntOrNull()
        if (FocusGoal.RANK_UP in draft.goals && elo != null && elo > 0) {
            core.gameGoals.update(core.gameGoals.goals.copy(elo = elo), core.game.profile(user).elo)
        }
    }

    val canGoBack = step == Step.GOALS || step == Step.PLAYER || (step == Step.ABOUT && !aboutOnly) ||
        (step == Step.TEST_INTRO && !testOnly && !aboutOnly)
    val canClose = step != Step.ABOUT
    fun goBack() {
        step = when (step) {
            Step.PLAYER -> Step.GOALS
            Step.ABOUT -> Step.PLAYER
            Step.TEST_INTRO -> Step.ABOUT
            else -> Step.WELCOME
        }
    }
    BackHandler { if (canGoBack) goBack() else if (canClose) close() }

    ScreenBackground {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(Metrics.headerButtonSize)) { if (canGoBack) HeaderIconButton("chevron.left", "Назад") { goBack() } }
                val progress = when (step) {
                    Step.GOALS -> "Шаг 1 из 4"
                    Step.PLAYER -> "Шаг 2 из 4"
                    Step.ABOUT -> if (aboutOnly) null else "Шаг 3 из 4"
                    Step.TEST_INTRO, Step.TEST -> if (testOnly || aboutOnly) null else "Шаг 4 из 4"
                    else -> null
                }
                Text(progress ?: "", color = Palette.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Box(Modifier.size(Metrics.headerButtonSize)) { if (canClose) HeaderIconButton("xmark", "Закрыть") { close() } }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                when (step) {
                    Step.WELCOME -> {
                        IconBadge(sfIcon("person.text.rectangle"), Palette.cyan, 64.dp, 28.dp)
                        StepTitle("Давай познакомимся", "Четыре коротких шага — около 6 минут. Ответы можно поменять в профиле.")
                        Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Bullet("target", "Цели", "Что хочешь улучшить: игру, сон, тильт, форму")
                            Bullet("gamecontroller", "Ты как игрок", "Роль, уровень, сколько и когда играешь")
                            Bullet("person.text.rectangle", "О тебе", "Возраст, рост, вес, режим сна и цели на день")
                            Bullet("brain.head.profile", "Игровой характер", "24 утверждения: хладнокровие, тильт, фокус, командность, лидерство, дисциплина")
                        }
                        Text(sharingNote, color = Palette.textSecondary, fontSize = 13.sp)
                    }
                    Step.GOALS -> {
                        StepTitle("Твои цели", "Выбери до ${FocusGoal.MAX_SELECTED} главных — Pulse будет делать на них акцент в советах и сводках.")
                        SurveyGoalsPicker(draft.goals) { draft = draft.copy(goals = it) }
                        if (FocusGoal.RANK_UP in draft.goals) {
                            PulseTextField("Цель по ELO (необязательно)", eloTarget, "Например, 2500", KeyboardType.Number) { eloTarget = it.filter(Char::isDigit).take(5) }
                        }
                    }
                    Step.PLAYER -> {
                        StepTitle("Что ты за игрок", "Роль, уровень и режим игры — тренер и Pulse будут учитывать это в нагрузке и рекомендациях.")
                        SurveyPlayerForm(draft) { draft = it }
                    }
                    Step.ABOUT -> {
                        StepTitle(ABOUT_YOU_TITLE, ABOUT_YOU_SUBTITLE)
                        AboutYouFields(about, onAbout)
                    }
                    Step.TEST_INTRO -> {
                        IconBadge(sfIcon("brain.head.profile"), Palette.purple, 64.dp, 28.dp)
                        StepTitle("Игровой характер", "24 утверждения о том, как ты ведёшь себя в игре. Отвечай честно и быстро — правильных ответов нет. Около 3 минут.")
                        Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MindsetTrait.entries.forEach { trait ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(sfIcon(trait.icon), null, tint = trait.tint.color, modifier = Modifier.size(20.dp))
                                    Text(trait.title, color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        Text(MINDSET_DISCLAIMER, color = Palette.textTertiary, fontSize = 12.sp)
                    }
                    Step.TEST -> MindsetTestRunner(answers, { answers = it }) {
                        if (store.addMindset(answers)) {
                            result = store.profile
                            step = Step.RESULT
                        }
                    }
                    Step.RESULT -> result?.let { profile ->
                        StepTitle("Твой игровой характер", null)
                        MindsetProfileCard(profile)
                        MindsetAdviceCard(profile, forCoach = false)
                        Text(sharingNote, color = Palette.textSecondary, fontSize = 13.sp)
                        Text(MINDSET_DISCLAIMER, color = Palette.textTertiary, fontSize = 12.sp)
                    }
                }
            }
            val primary: Pair<String, () -> Unit>? = when (step) {
                Step.WELCOME -> "Начать" to { step = Step.GOALS }
                Step.GOALS -> "Дальше" to { step = Step.PLAYER }
                Step.PLAYER -> "Дальше" to { saveSurvey(); step = Step.ABOUT }
                Step.ABOUT -> if (aboutOnly) "Сохранить" to { saveAbout(onClose) } else "Дальше" to { saveAbout { step = Step.TEST_INTRO } }
                Step.TEST_INTRO -> "Начать тест" to { step = Step.TEST }
                Step.TEST -> null
                Step.RESULT -> "Готово" to onClose
            }
            val secondary: Pair<String, () -> Unit>? = when {
                step == Step.WELCOME -> "Позже" to { close() }
                step == Step.ABOUT && aboutOnly -> "Выйти из аккаунта" to { core.session.logout(); onClose() }
                step == Step.TEST_INTRO && !testOnly -> "Пройти позже" to onClose
                else -> null
            }
            primary?.let { (title, action) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GradientButton(title, enabled = step != Step.GOALS || draft.goals.isNotEmpty(), dimmed = step == Step.ABOUT && !about.isValid,
                        onClick = action)
                    secondary?.let { (label, run) ->
                        Text(label, color = Palette.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.pressable(onClick = run).padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(text: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, color = Palette.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, color = Palette.textSecondary, fontSize = 15.sp) }
    }
}

@Composable
private fun Bullet(icon: String, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(sfIcon(icon), Palette.cyan, 36.dp, 15.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = Palette.textSecondary, fontSize = 13.sp)
        }
    }
}

/** Grid of focus goals, up to `FocusGoal.MAX_SELECTED`. */
@Composable
fun SurveyGoalsPicker(goals: List<FocusGoal>, onChange: (List<FocusGoal>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FocusGoal.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { goal ->
                    val isSelected = goal in goals
                    val isLocked = !isSelected && goals.size >= FocusGoal.MAX_SELECTED
                    val tint = goal.tint.color
                    Column(
                        Modifier.weight(1f).heightIn(min = 88.dp).alpha(if (isLocked) 0.45f else 1f).clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) tint.copy(alpha = 0.16f) else Palette.tile)
                            .border(if (isSelected) 1.5.dp else 1.dp, if (isSelected) tint else Palette.stroke, RoundedCornerShape(14.dp))
                            .pressable(enabled = !isLocked) { onChange(if (isSelected) goals - goal else goals + goal) }.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(sfIcon(goal.icon), null, tint = tint, modifier = Modifier.size(20.dp))
                        Text(goal.title, color = Palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** Role, level, experience, schedule and motivation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SurveyPlayerForm(survey: PlayerSurvey, onChange: (PlayerSurvey) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        FormField("Роль") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerGameRole.entries.forEach { role -> ChipButton(role.title, survey.role == role) { onChange(survey.copy(role = role)) } }
            }
            Text(survey.role.subtitle, color = Palette.textSecondary, fontSize = 13.sp)
        }
        FormField("Уровень") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkillLevel.entries.forEach { level ->
                    val isSelected = survey.level == level
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.tile)
                            .border(if (isSelected) 1.5.dp else 1.dp, if (isSelected) Palette.cyan else Palette.stroke, RoundedCornerShape(14.dp))
                            .pressable { onChange(survey.copy(level = level)) }.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (isSelected) Palette.cyan else Palette.textTertiary, CircleShape), contentAlignment = Alignment.Center) {
                            if (isSelected) Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.cyan))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(level.title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(level.subtitle, color = Palette.textSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        FormField("Опыт") {
            Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Играю в CS", color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    RoundButton("minus", "Меньше") { onChange(survey.copy(yearsPlaying = maxOf(survey.yearsPlaying - 1, 0))) }
                    Text(Formatters.years(survey.yearsPlaying), color = Palette.cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 64.dp))
                    RoundButton("plus", "Больше") { onChange(survey.copy(yearsPlaying = minOf(survey.yearsPlaying + 1, 25))) }
                }
                PulseSlider("Часов в неделю", survey.hoursPerWeek.toDouble(), 1.0..70.0) { onChange(survey.copy(hoursPerWeek = it.toInt())) }
            }
        }
        FormField("Когда обычно играешь") {
            PulseSegmented(PlayTime.entries, survey.playTime, { it.title }) { onChange(survey.copy(playTime = it)) }
        }
        FormField("Что мотивирует больше всего") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerMotivation.entries.forEach { motivation ->
                    ChipButton(motivation.title, survey.motivation == motivation) { onChange(survey.copy(motivation = motivation)) }
                }
            }
        }
    }
}

@Composable
private fun FormField(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title.uppercase(), color = Palette.section, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
        content()
    }
}

@Composable
private fun RoundButton(icon: String, description: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(Palette.track).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(sfIcon(icon), description, tint = Palette.textPrimary, modifier = Modifier.size(16.dp))
    }
}
