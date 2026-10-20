package com.pulse.android.ui.tests

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.components.GradientBar
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.theme.Gradients
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.model.CognitiveOutcome
import com.pulse.core.model.CognitiveScoring
import com.pulse.core.util.StatisticsMath
import kotlinx.coroutines.delay
import kotlin.random.Random

private fun nowMs(): Long = System.nanoTime() / 1_000_000

/** Shared header for a running test: instruction + progress. */
@Composable
private fun TestChrome(instruction: String, progress: Double, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(instruction, color = Palette.textSecondary, fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        GradientBar(progress, height = 6.dp)
        Column(Modifier.fillMaxWidth().weight(1f), content = content)
    }
}

/** Tap without the pressed-scale effect (the test surface itself reacts by color). */
private fun Modifier.plainTap(onTap: () -> Unit) = clickable(MutableInteractionSource(), indication = null, onClick = onTap)

// 1. Reaction time

@Composable
fun ReactionTest(onFinish: (CognitiveOutcome) -> Unit) {
    val trials = 5
    var phase by remember { mutableStateOf("waiting") }
    var times by remember { mutableStateOf(emptyList<Double>()) }
    var goAt by remember { mutableLongStateOf(0L) }
    // Each round waits a random 1.3–3.2 s before turning green; an early tap costs a 0.9 s penalty first.
    var round by remember { mutableIntStateOf(0) }
    var penalty by remember { mutableStateOf(false) }
    LaunchedEffect(round) {
        if (penalty) {
            delay(900)
            penalty = false
        }
        phase = "waiting"
        delay(Random.nextLong(1300, 3201))
        goAt = nowMs()
        phase = "go"
    }
    TestChrome("Нажми, как только экран станет зелёным", times.size.toDouble() / trials) {
        val color = when (phase) { "go" -> Palette.green; "early" -> Palette.red; else -> Palette.track }
        val label = when (phase) { "go" -> "Жми!"; "early" -> "Рано! Ещё раз"; else -> "Жди…" }
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(color).plainTap {
                when (phase) {
                    "waiting" -> {
                        phase = "early"
                        penalty = true
                        round++
                    }
                    "go" -> {
                        val updated = times + (nowMs() - goAt).toDouble()
                        times = updated
                        if (updated.size >= trials) {
                            phase = "done"
                            val average = StatisticsMath.mean(updated) ?: 500.0
                            onFinish(CognitiveOutcome(CognitiveScoring.reactionScore(average), "${average.toInt()} мс в среднем"))
                        } else {
                            round++
                        }
                    }
                }
            },
            contentAlignment = Alignment.Center
        ) { Text(label, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold) }
    }
}

// 2. Go / No-Go

@Composable
fun GoNoGoTest(onFinish: (CognitiveOutcome) -> Unit) {
    val total = 20
    var index by remember { mutableIntStateOf(0) }
    var isGo by remember { mutableStateOf(true) }
    var visible by remember { mutableStateOf(false) }
    var tapped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var correct = 0
        for (i in 0 until total) {
            index = i
            isGo = Random.nextInt(10) < 7
            tapped = false
            visible = true
            delay(750)
            if (tapped == isGo) correct++
            visible = false
            delay(Random.nextLong(350, 701))
        }
        index = total
        onFinish(CognitiveOutcome(CognitiveScoring.accuracyScore(correct, total), "$correct из $total верно"))
    }
    TestChrome("Зелёный — жми. Красный — не трогай", index.toDouble() / total) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Palette.tile).plainTap { if (visible && !tapped) tapped = true },
            contentAlignment = Alignment.Center
        ) {
            Signal(visible, if (isGo) Palette.green else Palette.red)
        }
    }
}

/** Go / No-Go circle, outside the column scope so the plain `AnimatedVisibility` is used. */
@Composable
private fun Signal(visible: Boolean, color: Color) {
    AnimatedVisibility(visible, enter = scaleIn(), exit = scaleOut() + fadeOut()) {
        Box(Modifier.size(150.dp).shadow(24.dp, CircleShape, ambientColor = color, spotColor = color).clip(CircleShape).background(color))
    }
}

// 3. Stroop

private data class Ink(val word: String, val title: String, val color: Color)

@Composable
fun StroopTest(onFinish: (CognitiveOutcome) -> Unit) {
    val total = 12
    val palette = remember {
        listOf(Ink("КРАСНЫЙ", "Красный", Palette.red), Ink("ЗЕЛЁНЫЙ", "Зелёный", Palette.green), Ink("СИНИЙ", "Синий", Palette.blue), Ink("ЖЁЛТЫЙ", "Жёлтый", Palette.yellow))
    }
    var index by remember { mutableIntStateOf(0) }
    var word by remember { mutableStateOf(palette[0].word) }
    var ink by remember { mutableIntStateOf(0) }
    var shownAt by remember { mutableLongStateOf(nowMs()) }
    var correct by remember { mutableIntStateOf(0) }
    var times by remember { mutableStateOf(emptyList<Double>()) }
    fun next() {
        word = palette.random().word
        val options = palette.indices.filter { palette[it].word != word || Random.nextInt(4) == 0 }
        ink = options.randomOrNull() ?: 0
        shownAt = nowMs()
    }
    LaunchedEffect(Unit) { next() }
    TestChrome("Выбери цвет, которым написано слово", index.toDouble() / total) {
        Spacer(Modifier.weight(1f))
        Text(word, color = palette[ink].color, fontSize = 46.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            palette.indices.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { choice ->
                        Box(
                            Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(16.dp)).background(palette[choice].color.copy(alpha = 0.85f)).pressable {
                                if (index >= total) return@pressable
                                val updatedTimes = times + (nowMs() - shownAt).toDouble()
                                times = updatedTimes
                                if (choice == ink) correct++
                                index++
                                if (index >= total) {
                                    val average = StatisticsMath.mean(updatedTimes) ?: 1000.0
                                    onFinish(CognitiveOutcome(CognitiveScoring.speedAccuracyScore(correct, total, average), "$correct/$total · ${average.toInt()} мс"))
                                } else {
                                    next()
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) { Text(palette[choice].title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}

// 4. Digit span

private sealed interface MemoryPhase {
    data object Showing : MemoryPhase
    data object Input : MemoryPhase
    data class Feedback(val ok: Boolean) : MemoryPhase
}

@Composable
fun MemoryTest(onFinish: (CognitiveOutcome) -> Unit) {
    var length by remember { mutableIntStateOf(3) }
    var sequence by remember { mutableStateOf(emptyList<Int>()) }
    var visibleIndex by remember { mutableStateOf<Int?>(null) }
    var input by remember { mutableStateOf(emptyList<Int>()) }
    var phase by remember { mutableStateOf<MemoryPhase>(MemoryPhase.Showing) }
    var bestSpan by remember { mutableIntStateOf(2) }
    var mistakes by remember { mutableIntStateOf(0) }
    var round by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(round) {
        sequence = List(length) { Random.nextInt(10) }
        input = emptyList()
        phase = MemoryPhase.Showing
        delay(400)
        sequence.indices.forEach { i ->
            visibleIndex = i
            delay(750)
            visibleIndex = null
            delay(200)
        }
        phase = MemoryPhase.Input
    }
    LaunchedEffect(phase) {
        val feedback = phase as? MemoryPhase.Feedback ?: return@LaunchedEffect
        delay(700)
        if (feedback.ok) {
            bestSpan = length
            if (length >= 9) { finished = true; return@LaunchedEffect }
            length++
        } else {
            mistakes++
            if (mistakes >= 2) { finished = true; return@LaunchedEffect }
        }
        round++
    }
    LaunchedEffect(finished) {
        if (finished) onFinish(CognitiveOutcome(CognitiveScoring.memoryScore(bestSpan), "$bestSpan цифр"))
    }

    val instruction = when (val current = phase) {
        MemoryPhase.Showing -> "Запоминай цифры: $length шт."
        MemoryPhase.Input -> "Введи последовательность"
        is MemoryPhase.Feedback -> if (current.ok) "Верно! Длина растёт" else "Ошибка"
    }
    val isInput = phase == MemoryPhase.Input
    TestChrome(instruction, (length - 3) / 7.0) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
            if (phase == MemoryPhase.Showing) {
                Text(visibleIndex?.let { "${sequence[it]}" } ?: " ", color = Palette.textPrimary, fontSize = 110.sp, fontWeight = FontWeight.Bold)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(length) { i ->
                        Box(Modifier.size(34.dp, 48.dp).clip(RoundedCornerShape(10.dp)).background(Palette.track), contentAlignment = Alignment.Center) {
                            Text(input.getOrNull(i)?.toString() ?: "", color = Palette.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.alpha(if (isInput) 1f else 0.4f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(-1, 0, -2)).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { key ->
                        val modifier = Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(14.dp))
                            .then(if (key == -2) Modifier.background(Gradients.accent) else Modifier.background(Palette.tile))
                            .pressable(enabled = isInput) {
                                when (key) {
                                    -1 -> input = input.dropLast(1)
                                    -2 -> phase = MemoryPhase.Feedback(input == sequence)
                                    else -> if (input.size < length) input = input + key
                                }
                            }
                        Box(modifier, contentAlignment = Alignment.Center) {
                            when (key) {
                                -1 -> Icon(sfIcon("delete.left"), "Стереть", tint = Color.White, modifier = Modifier.size(24.dp))
                                -2 -> Icon(sfIcon("checkmark"), "Готово", tint = Color.White, modifier = Modifier.size(24.dp))
                                else -> Text("$key", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// 5. Aim

@Composable
fun AimTest(onFinish: (CognitiveOutcome) -> Unit) {
    val total = 15
    var hits by remember { mutableIntStateOf(0) }
    var misses by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf(Random.nextFloat() to Random.nextFloat()) }
    var shownAt by remember { mutableLongStateOf(nowMs()) }
    var times by remember { mutableStateOf(emptyList<Double>()) }
    TestChrome("Попадай по мишеням как можно быстрее", hits.toDouble() / total) {
        BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Palette.tile).plainTap { misses++ }) {
            val x = 40.dp + (maxWidth - 80.dp) * target.first - 31.dp
            val y = 40.dp + (maxHeight - 80.dp) * target.second - 31.dp
            Box(
                Modifier.offset(x, y).size(62.dp).clip(CircleShape).background(Palette.red).plainTap {
                    if (hits >= total) return@plainTap
                    val updated = times + (nowMs() - shownAt).toDouble()
                    times = updated
                    hits++
                    if (hits >= total) {
                        val average = StatisticsMath.mean(updated) ?: 1000.0
                        onFinish(CognitiveOutcome(CognitiveScoring.speedAccuracyScore(total, total + misses, average), "${average.toInt()} мс · промахов $misses"))
                    } else {
                        target = Random.nextFloat() to Random.nextFloat()
                        shownAt = nowMs()
                    }
                },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(Palette.red))
                }
            }
        }
    }
}
