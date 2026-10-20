package com.pulse.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.theme.Palette
import kotlin.math.roundToInt

data class ScatterDot(val x: Double, val y: Double, val color: Color, val label: String? = null)

/**
 * Dots on two numeric axes with an optional trend line (`intercept`, `slope`). Hold and slide to select the dot
 * nearest to the finger horizontally; its callout is shown above the plot.
 */
@Composable
fun ScatterChart(
    dots: List<ScatterDot>,
    calloutFor: (Int) -> Callout,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    xFormat: (Double) -> String = { it.toString() },
    yFormat: (Double) -> String = { it.toString() },
    trend: Pair<Double, Double>? = null,
    dotRadius: Dp = 5.dp
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    var calloutWidth by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = Palette.textSecondary, fontSize = 11.sp)
    val labelStyle = TextStyle(color = Palette.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    if (dots.isEmpty()) return
    val minX = dots.minOf { it.x }; val maxX = dots.maxOf { it.x }
    val minY = dots.minOf { it.y }; val maxY = dots.maxOf { it.y }
    val padX = maxOf((maxX - minX) * 0.12, 0.05); val padY = maxOf((maxY - minY) * 0.18, 0.05)
    val lowX = minX - padX; val highX = maxX + padX; val lowY = minY - padY; val highY = maxY + padY
    BoxWithConstraints(modifier.fillMaxWidth().height(height + 46.dp)) {
        val leftAxis = with(density) { 44.dp.toPx() }
        val bottomAxis = with(density) { 22.dp.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        val plotWidth = widthPx - leftAxis
        fun xPx(x: Double) = leftAxis + ((x - lowX) / (highX - lowX)).toFloat() * plotWidth
        fun nearest(px: Float): Int = dots.indices.minBy { kotlin.math.abs(xPx(dots[it].x) - px) }
        Canvas(Modifier.fillMaxWidth().height(height).offset(y = 46.dp).pointerInput(dots) {
            detectDragGesturesAfterLongPress(
                onDragStart = { selected = nearest(it.x); haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                onDrag = { change, _ ->
                    val next = nearest(change.position.x)
                    if (next != selected) { selected = next; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                },
                onDragEnd = { selected = null },
                onDragCancel = { selected = null }
            )
        }) {
            val plotHeight = size.height - bottomAxis
            fun yPx(y: Double) = ((1 - (y - lowY) / (highY - lowY)) * plotHeight).toFloat()
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            for (step in 0..3) {
                val fraction = step / 3.0
                val y = plotHeight * (1 - fraction).toFloat()
                drawLine(Color.White.copy(alpha = 0.07f), Offset(leftAxis, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dash)
                val label = measurer.measure(yFormat(lowY + (highY - lowY) * fraction), axisStyle)
                drawText(label, topLeft = Offset(0f, (y - label.size.height / 2f).coerceIn(0f, plotHeight - label.size.height)))
                val x = leftAxis + plotWidth * fraction.toFloat()
                val xLabel = measurer.measure(xFormat(lowX + (highX - lowX) * fraction), axisStyle)
                drawText(xLabel, topLeft = Offset((x - xLabel.size.width / 2f).coerceIn(leftAxis, size.width - xLabel.size.width), plotHeight + 4.dp.toPx()))
            }
            trend?.let { (intercept, slope) ->
                drawLine(Palette.cyan, Offset(xPx(minX), yPx(intercept + slope * minX)), Offset(xPx(maxX), yPx(intercept + slope * maxX)),
                    2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)))
            }
            dots.forEachIndexed { i, dot ->
                val center = Offset(xPx(dot.x), yPx(dot.y))
                val isSelected = selected == i
                val alpha = if (selected == null || isSelected) 1f else 0.35f
                drawCircle(dot.color.copy(alpha = dot.color.alpha * alpha), (if (isSelected) dotRadius * 1.4f else dotRadius).toPx(), center)
                if (dot.label != null && !isSelected) {
                    val text = measurer.measure(dot.label, labelStyle)
                    drawText(text, topLeft = Offset((center.x - text.size.width / 2f).coerceIn(leftAxis, size.width - text.size.width), center.y - text.size.height - 8.dp.toPx()), alpha = alpha)
                }
            }
            selected?.let { drawLine(Palette.textSecondary.copy(alpha = 0.55f), Offset(xPx(dots[it].x), 0f), Offset(xPx(dots[it].x), plotHeight), 1.dp.toPx(), pathEffect = dash) }
        }
        selected?.let { index ->
            val callout = calloutFor(index)
            val x = (xPx(dots[index].x) - calloutWidth / 2f).coerceIn(0f, (widthPx - calloutWidth).coerceAtLeast(0f))
            Box(Modifier.offset { IntOffset(x.roundToInt(), 0) }.onSizeChanged { calloutWidth = it.width }) { CalloutBubbleBox(callout) }
        }
    }
}
