package com.pulse.android.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.GradientBar
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.HeaderIconButton
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.components.RadarChart
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.SecondaryButton
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.fit
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.AppTab
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Gradients
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.color
import com.pulse.android.ui.theme.sfIcon
import com.pulse.android.ui.today.SurveyReminderCard
import com.pulse.core.model.FocusGoal
import com.pulse.core.model.MindsetProfile
import com.pulse.core.model.MindsetTest
import com.pulse.core.model.MindsetTrait
import com.pulse.core.model.PlayerSurvey
import com.pulse.core.model.UserRole
import com.pulse.core.util.Formatters
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** One statement per screen, five answers, auto-advance. */
@Composable
fun MindsetTestRunner(answers: Map<String, Int>, onAnswers: (Map<String, Int>) -> Unit, onFinish: () -> Unit) {
    val questions = MindsetTest.questions
    var index by remember { mutableIntStateOf(0) }
    // Advance a moment after the tap so the chosen answer is visible.
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        delay(220)
        pending = null
        if (questions[index].id != id) return@LaunchedEffect
        if (index < questions.size - 1) index++ else onFinish()
    }
    val question = questions[index]
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Утверждение ${index + 1} из ${questions.size}", color = Palette.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (index > 0) Text("Назад", color = Palette.cyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.pressable { index-- })
        }
        GradientBar(index.toDouble() / questions.size, height = 6.dp)
        Text(question.text, color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.heightIn(min = 96.dp))
        Text("Насколько это про тебя?", color = Palette.textSecondary, fontSize = 14.sp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MindsetTest.answerTitles.forEachIndexed { offset, title ->
                val value = offset + 1
                val isSelected = answers[question.id] == value
                Row(
                    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(14.dp)).background(Palette.tile)
                        .border(if (isSelected) 1.5.dp else 1.dp, if (isSelected) Palette.cyan else Palette.stroke, RoundedCornerShape(14.dp))
                        .pressable(enabled = pending == null) {
                            onAnswers(answers + (question.id to value))
                            pending = question.id
                        }.padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).then(if (isSelected) Modifier.background(Gradients.accent) else Modifier.background(Palette.track)),
                        contentAlignment = Alignment.Center
                    ) { Text("$value", color = if (isSelected) Color.White else Palette.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    Text(title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Leading trait + radar of the six traits. */
@Composable
fun MindsetProfileCard(profile: MindsetProfile, caption: String? = null) {
    val archetype = profile.archetype
    Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(sfIcon(archetype.icon), archetype.tint.color, 50.dp, 22.dp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(archetype.caption.uppercase(), color = Palette.textTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(archetype.title, color = Palette.textPrimary, autoSize = fit(20.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(archetype.summary, color = Palette.textSecondary, fontSize = 14.sp)
            }
        }
        RadarChart(MindsetTrait.entries.map { it.shortTitle }, profile.orderedScores.map { it.toDouble() }, maxValue = 100.0, height = 250.dp)
        caption?.let { Text(it, color = Palette.textTertiary, fontSize = 12.sp) }
    }
}

/** Trait list with score bars and the change since the previous test. */
@Composable
fun MindsetTraitsCard(profile: MindsetProfile, previous: MindsetProfile? = null, showsMeaning: Boolean = true) {
    Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MindsetTrait.entries.forEach { trait ->
            val score = profile.scores[trait] ?: 0
            val tint = trait.tint.color
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(sfIcon(trait.icon), null, tint = tint, modifier = Modifier.size(16.dp))
                    Text(trait.title, color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    previous?.scores?.get(trait)?.takeIf { it != score }?.let { before ->
                        Text(Formatters.signed(score - before), color = if (score > before) Palette.green else Palette.orange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("$score", color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                GradientBar(score / 100.0, height = 6.dp, fill = Brush.horizontalGradient(listOf(tint, tint)))
                if (showsMeaning) Text(trait.meaning, color = Palette.textTertiary, fontSize = 12.sp)
            }
        }
    }
}

/** Strengths and growth zones with advice for the player or for the coach. */
@Composable
fun MindsetAdviceCard(profile: MindsetProfile, forCoach: Boolean) {
    Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AdviceGroup("Сильные стороны", "checkmark.seal.fill", Palette.green, profile.strengths, profile) { if (forCoach) it.coachStrength else it.meaning }
        if (profile.growthZones.isNotEmpty()) {
            DividerLine()
            AdviceGroup("Зоны роста", "arrow.up.forward.circle.fill", Palette.orange, profile.growthZones, profile) { if (forCoach) it.coachTip else it.playerTip }
        }
    }
}

@Composable
private fun AdviceGroup(title: String, icon: String, tint: Color, traits: List<MindsetTrait>, profile: MindsetProfile, text: (MindsetTrait) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(sfIcon(icon), null, tint = tint, modifier = Modifier.size(16.dp))
            Text(title, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        traits.forEach { trait ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${trait.title} · ${profile.scores[trait] ?: 0}", color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(text(trait), color = Palette.textSecondary, fontSize = 14.sp)
            }
        }
    }
}

/** Goals + player type in a compact card. */
@Composable
fun SurveySummaryCard(survey: PlayerSurvey) {
    Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(survey.role.title, Palette.cyan)
            Tag(survey.level.title, Palette.purple)
            Tag(survey.playTime.title, Palette.blue)
        }
        Text("${Formatters.years(survey.yearsPlaying)} в CS · ${survey.hoursPerWeek} ч в неделю · мотивация: ${survey.motivation.title.lowercase()}",
            color = Palette.textSecondary, fontSize = 14.sp)
        if (survey.goals.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            survey.goals.forEach { goal ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(sfIcon(goal.icon), null, tint = goal.tint.color, modifier = Modifier.size(16.dp))
                    Text(goal.title, color = Palette.textPrimary, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun Tag(title: String, tint: Color) {
    Text(title, color = tint, autoSize = fit(13.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(CircleShape).background(tint.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp))
}

/** Профиль → «Игровой профиль»: questionnaire answers, mindset radar, traits and advice. */
@Composable
fun PlayerPortraitScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.survey
    var edit by remember { mutableStateOf(false) }
    val isTeamPlayer = core.session.user.value?.role == UserRole.TEAM_PLAYER
    ScreenScaffold(spacing = 22.dp, header = {
        NavHeader("Игровой профиль", if (isTeamPlayer) "Видишь ты и тренер команды" else "Видишь только ты", onBack = { router.pop() }) {
            HeaderIconButton("pencil", "Изменить анкету") { edit = true }
        }
    }) {
        val survey = store.survey
        if (survey != null) PulseSection("Ты как игрок") { SurveySummaryCard(survey) } else SurveyReminderCard()
        val profile = store.profile
        val result = store.latestResult
        if (profile != null && result != null) {
            PulseSection("Игровой характер") { MindsetProfileCard(profile, "Тест от ${Formatters.dayMonth(result.date)}") }
            PulseSection("Черты") { MindsetTraitsCard(profile, store.previousProfile) }
            PulseSection("Что делать") { MindsetAdviceCard(profile, forCoach = false) }
            SecondaryButton("Пройти тест заново", icon = sfIcon("arrow.clockwise")) {
                router.surveyTestOnly = true
                router.showsSurvey = true
            }
            store.nextRetakeDate()?.takeIf { it > Clock.System.now() }?.let { next ->
                Text("Рекомендуем перепроходить раз в месяц — следующий раз после ${Formatters.dayMonth(next)}.", color = Palette.textSecondary, fontSize = 13.sp)
            }
        } else if (survey != null) {
            SurveyReminderCard()
        }
        Text(MINDSET_DISCLAIMER, color = Palette.textTertiary, fontSize = 12.sp)
    }
    if (edit) PulseSheet({ edit = false }) { SurveyEditSheet { edit = false } }
}

/** Compact block in «Профиль». */
@Composable
fun PlayerPortraitSection() {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.survey
    val profile = store.profile
    val survey = store.survey
    val open = { router.open(Route.PlayerPortrait, AppTab.PROFILE) }
    PulseSection("Игровой профиль", if (store.isComplete) "Подробнее" else null, open) {
        if (store.isComplete && profile != null && survey != null) {
            val archetype = profile.archetype
            Row(Modifier.pressable(onClick = open).pulseCard(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(sfIcon(archetype.icon), archetype.tint.color, 48.dp, 21.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${archetype.caption}: ${archetype.title.lowercase()}", color = Palette.textPrimary, autoSize = fit(17.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(survey.summary, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 2)
                    Text(
                        if (profile.growthZones.isEmpty()) "Без явных зон роста" else "Зона роста: ${profile.growthZones.joinToString(", ") { it.title.lowercase() }}",
                        color = Palette.orange, autoSize = fit(13.sp), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(sfIcon("chevron.right"), null, tint = Palette.textTertiary, modifier = Modifier.size(15.dp))
            }
        } else {
            SurveyReminderCard()
        }
    }
}

/** Edit goals and player type later. */
@Composable
private fun SurveyEditSheet(onClose: () -> Unit) {
    val store = observeCore().survey
    var draft by remember { mutableStateOf(store.survey ?: PlayerSurvey()) }
    SheetScaffold("Анкета игрока", onClose) {
        Text("ЦЕЛИ · ДО ${FocusGoal.MAX_SELECTED}", color = Palette.section, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
        SurveyGoalsPicker(draft.goals) { draft = draft.copy(goals = it) }
        SurveyPlayerForm(draft) { draft = it }
        GradientButton("Сохранить", enabled = draft.goals.isNotEmpty()) {
            store.saveSurvey(draft)
            onClose()
        }
    }
}
