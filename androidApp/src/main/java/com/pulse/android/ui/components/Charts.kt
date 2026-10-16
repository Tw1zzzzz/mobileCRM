package com.pulse.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.theme.Palette
import kotlin.math.roundToInt

/** Floating label above the selected point: value(s) on top, date below (iOS `ChartCallout`). */
data class Callout(val title: String, val lines: List<Pair<String, Color>>) {
    constructor(title: String, value: String, tint: Color) : this(title, listOf(value to tint))
}

@Composable
private fun CalloutBubble(callout: Callout, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF0E1730)).border(1.dp, Palette.stroke, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        callout.lines.forEach { (text, tint) -> Text(text, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        Text(callout.title, color = Palette.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * Press and hold, then slide: the nearest point is selected and its callout shown above the chart
 * (released → hidden). Vertical scrolling keeps working because only a long press starts the scrub.
 */
@Composable
private fun ScrubbableChart(
    count: Int,
    height: Dp,
    calloutFor: (Int) -> Callout?,
    xFraction: (Int) -> Float,
    modifier: Modifier = Modifier,
    content: @Composable (selected: Int?) -> Unit
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    var calloutWidth by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth().height(height + 46.dp)) {
        val widthPx = with(density) { maxWidth.toPx() }
        fun index(x: Float): Int = if (count <= 1) 0 else (0 until count).minBy { kotlin.math.abs(xFraction(it) * widthPx - x) }
        Box(
            Modifier.fillMaxWidth().height(height).offset(y = 46.dp).pointerInput(count) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        selected = index(offset.x)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, _ ->
                        val next = index(change.position.x)
                        if (next != selected) {
                            selected = next
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onDragEnd = { selected = null },
                    onDragCancel = { selected = null }
                )
            }
        ) { content(selected) }
        selected?.let { index ->
            calloutFor(index)?.let { callout ->
                val centerX = xFraction(index) * widthPx
                val x = (centerX - calloutWidth / 2f).coerceIn(0f, (widthPx - calloutWidth).coerceAtLeast(0f))
                CalloutBubble(callout, Modifier.offset { IntOffset(x.roundToInt(), 0) }.onSizeChanged { calloutWidth = it.width })
            }
        }
    }
}

/** Day bars with weekday labels; hold to inspect a bar. */
@Composable
fun PulseBarChart(
    values: List<Double>,
    labels: List<String>,
    calloutFor: (Int) -> Callout?,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    brush: Brush = Brush.verticalGradient(listOf(Color(0xFF16B4FF), Palette.blue)),
    highlight: Int? = null,
    goal: Double? = null,
    upper: Double? = null,
    barBrush: ((Int) -> Brush?)? = null,
    labelsBelow: (@Composable () -> Unit)? = null
) {
    val count = values.size
    fun xFraction(i: Int) = (i + 0.5f) / count.coerceAtLeast(1)
    Column(modifier) {
        ScrubbableChart(count, height, calloutFor, ::xFraction) { selected ->
            Canvas(Modifier.fillMaxSize()) {
                val top = upper ?: maxOf(values.maxOrNull() ?: 0.0, goal ?: 0.0, 1.0)
                for (step in 1..3) {
                    val y = size.height * (1 - step / 3f)
                    drawLine(Color.White.copy(alpha = 0.07f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
                }
                goal?.let {
                    val y = (size.height * (1 - it / top)).toFloat()
                    drawLine(Palette.green.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                }
                val slot = size.width / count.coerceAtLeast(1)
                val barWidth = slot * 0.6f
                values.forEachIndexed { i, value ->
                    val barHeight = (size.height * (value.coerceAtMost(top) / top)).toFloat().coerceAtLeast(if (value > 0) 3.dp.toPx() else 0f)
                    val alpha = if (selected == null || selected == i) 1f else 0.35f
                    val isHighlight = highlight == i
                    drawRoundRect(
                        barBrush?.invoke(i) ?: if (isHighlight) Brush.verticalGradient(listOf(Palette.cyan, Color(0xFF12C8F5))) else brush,
                        topLeft = Offset(slot * i + (slot - barWidth) / 2, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        alpha = alpha
                    )
                }
            }
        }
        labelsBelow?.invoke()
        if (labels.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEachIndexed { i, label ->
                Text(label, color = if (highlight == i) Palette.textPrimary else Palette.textSecondary, fontSize = 12.sp,
                    fontWeight = if (highlight == i) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1)
            }
        }
    }
}

/** Axis labels centered on fractions of the width (clamped to the edges), e.g. hours 00, 06, 12, 18, 23. */
@Composable
fun FractionLabels(labels: List<Pair<Float, String>>, modifier: Modifier = Modifier) {
    Layout(
        content = { labels.forEach { (_, text) -> Text(text, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 1) } },
        modifier = modifier.fillMaxWidth().padding(top = 6.dp)
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { i, placeable ->
                val x = (labels[i].first * width - placeable.width / 2f).roundToInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                placeable.place(x, 0)
            }
        }
    }
}

/** Smooth line (optionally with area) over evenly spaced points; hold to inspect a point. */
@Composable
fun PulseLineChart(
    values: List<Double>,
    calloutFor: (Int) -> Callout?,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    color: Color = Palette.cyan,
    showsArea: Boolean = true,
    lineWidth: Dp = 3.dp,
    lower: Double? = null,
    upper: Double? = null,
    labels: List<String> = emptyList(),
    secondary: List<Double>? = null,
    secondaryColor: Color = Palette.purple
) {
    val count = values.size
    fun xFraction(i: Int) = if (count <= 1) 0.5f else i / (count - 1f)
    Column(modifier) {
        ScrubbableChart(count, height, calloutFor, ::xFraction) { selected ->
            Canvas(Modifier.fillMaxSize()) {
                if (count == 0) return@Canvas
                val all = values + (secondary ?: emptyList())
                val low = lower ?: (all.min() - maxOf((all.max() - all.min()) * 0.15, 0.5))
                val high = upper ?: (all.max() + maxOf((all.max() - all.min()) * 0.15, 0.5))
                fun point(i: Int, v: Double) = Offset(size.width * xFraction(i), (size.height * (1 - (v - low) / (high - low))).toFloat())
                for (step in 0..2) {
                    val y = size.height * (step / 2f)
                    drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
                }
                fun smooth(series: List<Double>): Path = Path().apply {
                    series.forEachIndexed { i, v ->
                        val p = point(i, v)
                        if (i == 0) moveTo(p.x, p.y) else {
                            val prev = point(i - 1, series[i - 1])
                            val mid = (prev.x + p.x) / 2
                            cubicTo(mid, prev.y, mid, p.y, p.x, p.y)
                        }
                    }
                }
                val line = smooth(values)
                if (showsArea && count > 1) {
                    val area = Path().apply {
                        addPath(line)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f))))
                }
                secondary?.let { drawPath(smooth(it), secondaryColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)) }
                drawPath(line, color, style = Stroke(lineWidth.toPx(), cap = StrokeCap.Round))
                val marker = selected ?: (count - 1)
                val p = point(marker, values[marker])
                if (selected != null) {
                    drawLine(Palette.textSecondary.copy(alpha = 0.55f), Offset(p.x, 0f), Offset(p.x, size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                }
                drawCircle(Palette.background, 7.dp.toPx(), p)
                drawCircle(color, 5.dp.toPx(), p)
            }
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                labels.forEach { Text(it, color = Palette.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1) }
            }
        }
    }
}

/** Swallows taps so a parent's click doesn't fire while scrubbing (used inside clickable cards). */
fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }
