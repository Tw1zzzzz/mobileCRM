package com.pulse.android.ui.tests

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.Callout
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.HeaderIconButton
import com.pulse.android.ui.components.HubRow
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseLineChart
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.ScoreRing
import com.pulse.android.ui.components.ScreenBackground
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.SectionHeader
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Gradients
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.color
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.model.CognitiveOutcome
import com.pulse.core.model.CognitiveTestKind
import com.pulse.core.util.Formatters
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.roundToIntSwift
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** «Тесты»: daily and weekly blocks. */
@Composable
fun TestsScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val now = Clock.System.now()
    ScreenScaffold(header = { NavHeader("Тесты", "Ежедневные и еженедельные", onBack = { router.pop() }) }) {
        PulseSection("Ежедневные тесты") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HubRow("brain.head.profile", Palette.green, "Когнитивные тесты",
                    if (core.cognitive.completedToday(now)) "Батарея пройдена сегодня" else "5 тестов когнитивной формы") { router.open(Route.Cognitive) }
                HubRow("face.smiling", Palette.yellow, "Настроение и энергия",
                    "Утро · день · вечер — ${core.wellbeing.filledSlots(now).size}/3") { router.open(Route.Mood) }
                HubRow("gamecontroller", Palette.cyan, "Игровые тесты", "До и после тренировки") { router.open(Route.GameTests) }
            }
        }
        PulseSection("Еженедельные") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HubRow("circle.hexagongrid", Palette.pink, "Колесо баланса",
                    core.wellbeing.latestBalance?.let { "Последнее: ${Formatters.dayMonth(it.day)}" } ?: "Ещё не заполнено") { router.open(Route.Balance) }
                Row(Modifier.pulseCard(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(sfIcon("calendar.badge.clock"), Palette.textSecondary, 52.dp, 22.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Еженедельные тесты", color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("Анкеты из веб-версии — подключим с API", color = Palette.textSecondary, fontSize = 14.sp)
                    }
                    Text("скоро", color = Palette.orange, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(CircleShape).background(Palette.orange.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }
    }
}

/** «Когнитивные тесты»: intro, battery launch, 7-day trend and history (blurred without PRO). */
@Composable
fun CognitiveScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.cognitive
    val locked = !core.session.hasSubscription
    var battery by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(true) }
    ScreenScaffold(header = { NavHeader("Когнитивные тесты", onBack = { router.pop() }) }) {
        Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Батарея из 5 тестов измеряет твою когнитивную форму: реакцию, самоконтроль, концентрацию, память и моторику. Проходи ежедневно в одно время — так тренд будет точнее.",
                color = Palette.textSecondary, fontSize = 16.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CognitiveTestKind.entries.forEach { IconBadge(sfIcon(it.icon), it.tint.color, 40.dp, 17.dp) }
            }
            GradientButton("Запустить батарею", icon = sfIcon("play.fill")) { battery = true }
        }
        PulseSection("Тренд последних 7 дней") {
            val series = store.dailyScores(7)
            Locked(locked) {
                Column(Modifier.pulseCard(padding = 20.dp)) {
                    if (series.isEmpty()) {
                        Text("Пройди батарею, чтобы увидеть тренд", color = Palette.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(vertical = 40.dp))
                    } else {
                        PulseLineChart(
                            series.map { it.value },
                            calloutFor = { i -> Callout(Formatters.dayMonthWeekday(series[i].date), "${series[i].value.roundToIntSwift()} баллов", Palette.cyan) },
                            height = 150.dp, showsArea = false, lower = 0.0, upper = 100.0,
                            labels = series.map { Formatters.weekdayShort(it.date) }
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.animateContentSize()) {
            Row(Modifier.pressable { historyExpanded = !historyExpanded }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionHeader("История") }
                Icon(sfIcon(if (historyExpanded) "chevron.up" else "chevron.down"), null, tint = Palette.textSecondary, modifier = Modifier.size(18.dp))
            }
            if (historyExpanded) Locked(locked) {
                Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                    CognitiveTestKind.entries.forEachIndexed { index, kind ->
                        val latest = store.latest(kind)
                        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(sfIcon(kind.icon), kind.tint.color, 44.dp, 18.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(kind.title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(kind.target, color = Palette.textSecondary, fontSize = 13.sp)
                                Text(latest?.let { "Последний раз: ${Formatters.dayMonthTime(it.date)}" } ?: "Ещё не проходил", color = Palette.textTertiary, fontSize = 12.sp)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(latest?.score?.toString() ?: "—", color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text(latest?.metric ?: "", color = Palette.textSecondary, fontSize = 12.sp)
                            }
                        }
                        if (index < CognitiveTestKind.entries.size - 1) DividerLine()
                    }
                }
            }
        }
    }
    if (battery) {
        Dialog({ battery = false }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            BatteryRunner(onResult = { kind, outcome -> store.record(kind, outcome) }, onClose = { battery = false })
        }
    }
}

/** Blur + lock label when the content needs PRO (iOS `lockedIfNeeded`). */
@Composable
private fun Locked(isLocked: Boolean, content: @Composable () -> Unit) {
    if (!isLocked) return content()
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.blur(8.dp)) { content() }
        // blur() is a no-op before Android 12 — the scrim keeps the content unreadable there too.
        Box(Modifier.matchParentSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(Metrics.cardRadius)).background(Palette.background.copy(alpha = 0.55f)))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(sfIcon("lock.fill"), null, tint = Palette.textPrimary, modifier = Modifier.size(26.dp))
            Text("Доступно по подписке PRO", color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private sealed interface BatteryStep {
    data object Intro : BatteryStep
    data class Countdown(val value: Int) : BatteryStep
    data object Running : BatteryStep
    data class Result(val outcome: CognitiveOutcome) : BatteryStep
    data object Finished : BatteryStep
}

/** Battery walk-through: intro → countdown → test → result → next test → summary. */
@Composable
private fun BatteryRunner(onResult: (CognitiveTestKind, CognitiveOutcome) -> Unit, onClose: () -> Unit) {
    val kinds = CognitiveTestKind.entries
    var index by remember { mutableIntStateOf(0) }
    var step by remember { mutableStateOf<BatteryStep>(BatteryStep.Intro) }
    var outcomes by remember { mutableStateOf(emptyMap<CognitiveTestKind, CognitiveOutcome>()) }
    val kind = kinds[index.coerceAtMost(kinds.size - 1)]
    BackHandler(onBack = onClose)
    LaunchedEffect(step) {
        val countdown = step as? BatteryStep.Countdown ?: return@LaunchedEffect
        delay(1000)
        step = if (countdown.value > 1) BatteryStep.Countdown(countdown.value - 1) else BatteryStep.Running
    }
    ScreenBackground {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeaderIconButton("xmark", "Закрыть", onClick = onClose)
                    Text(if (step == BatteryStep.Finished) "Готово" else "Тест ${index + 1} из ${kinds.size}", color = Palette.textSecondary, fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Metrics.headerButtonSize))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kinds.indices.forEach { i ->
                        val fill = when {
                            i < index || step == BatteryStep.Finished -> Modifier.background(Gradients.accent)
                            i == index -> Modifier.background(Palette.cyan.copy(alpha = 0.5f))
                            else -> Modifier.background(Palette.track)
                        }
                        Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).then(fill))
                    }
                }
            }
            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 20.dp), contentAlignment = Alignment.Center) {
                when (val current = step) {
                    BatteryStep.Intro -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            IconBadge(sfIcon(kind.icon), kind.tint.color, 76.dp, 34.dp)
                            Text(kind.title, color = Palette.textPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            InfoBlock("Что измеряет", kind.measures)
                            InfoBlock("Что нужно делать", kind.instructions)
                        }
                        GradientButton("Старт", icon = sfIcon("play.fill")) { step = BatteryStep.Countdown(3) }
                    }
                    is BatteryStep.Countdown -> AnimatedContent(current.value, transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() }, label = "countdown") { value ->
                        Text("$value", fontSize = 140.sp, fontWeight = FontWeight.Bold, color = Palette.cyan)
                    }
                    BatteryStep.Running -> {
                        val finish: (CognitiveOutcome) -> Unit = { outcome ->
                            onResult(kind, outcome)
                            outcomes = outcomes + (kind to outcome)
                            step = BatteryStep.Result(outcome)
                        }
                        when (kind) {
                            CognitiveTestKind.REACTION -> ReactionTest(finish)
                            CognitiveTestKind.GO_NO_GO -> GoNoGoTest(finish)
                            CognitiveTestKind.STROOP -> StroopTest(finish)
                            CognitiveTestKind.MEMORY -> MemoryTest(finish)
                            CognitiveTestKind.AIM -> AimTest(finish)
                        }
                    }
                    is BatteryStep.Result -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Spacer(Modifier.weight(1f))
                        ScoreRing(current.outcome.score / 100.0, "${current.outcome.score}", "БАЛЛОВ", 190.dp, valueSize = 60.sp)
                        Text(kind.title, color = Palette.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(current.outcome.metric, color = Palette.textSecondary, fontSize = 18.sp)
                        Spacer(Modifier.weight(1f))
                        GradientButton(if (index < kinds.size - 1) "Следующий тест" else "Завершить батарею", icon = sfIcon("arrow.right")) {
                            if (index < kinds.size - 1) {
                                index++
                                step = BatteryStep.Intro
                            } else {
                                step = BatteryStep.Finished
                            }
                        }
                    }
                    BatteryStep.Finished -> {
                        val average = StatisticsMath.mean(outcomes.values.map { it.score.toDouble() }) ?: 0.0
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            ScoreRing(average / 100, "${average.toInt()}", "КОГН. ФОРМА", 170.dp, valueSize = 54.sp)
                            Column(Modifier.pulseCard()) {
                                kinds.forEach { item ->
                                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        IconBadge(sfIcon(item.icon), item.tint.color, 36.dp, 15.dp)
                                        Text(item.title, color = Palette.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                                        Text(outcomes[item]?.score?.toString() ?: "—", color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            GradientButton("Вернуться к тестам", onClick = onClose)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoBlock(title: String, text: String) {
    Column(Modifier.fillMaxWidth().pulseCard(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), color = Palette.section, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Text(text, color = Palette.textPrimary, fontSize = 17.sp)
    }
}
