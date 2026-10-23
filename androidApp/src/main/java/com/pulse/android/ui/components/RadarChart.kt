package com.pulse.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.pulse.android.ui.theme.Palette
import com.pulse.core.util.Formatters
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Spider / radar chart (balance wheel: 8 areas 0…10, mindset: 6 traits 0…100).
 * Hold a finger and slide around the wheel to read each axis.
 */
@Composable
fun RadarChart(labels: List<String>, values: List<Double>, modifier: Modifier = Modifier, maxValue: Double = 10.0, height: Dp = 300.dp) {
    var selected by remember { mutableStateOf<Int?>(null) }
    val haptics = LocalHapticFeedback.current
    val count = labels.size
    val animated = values.mapIndexed { i, value ->
        animateFloatAsState((value / maxValue).coerceIn(0.0, 1.0).toFloat(), tween(300), label = "radar$i").value
    }
    fun angle(index: Int) = index.toDouble() / count * 2 * Math.PI - Math.PI / 2

    Box(modifier.fillMaxWidth().height(height)) {
        val labelInset = 46.dp
        Canvas(Modifier.fillMaxSize().pointerInput(count) {
            fun axis(offset: Offset): Int {
                val raw = atan2((offset.y - size.height / 2).toDouble(), (offset.x - size.width / 2).toDouble()) + Math.PI / 2
                val turn = (if (raw < 0) raw + 2 * Math.PI else raw) / (2 * Math.PI)
                return (turn * count).roundToInt() % count
            }
            detectDragGesturesAfterLongPress(
                onDragStart = { selected = axis(it); haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                onDrag = { change, _ ->
                    val next = axis(change.position)
                    if (next != selected) { selected = next; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                },
                onDragEnd = { selected = null },
                onDragCancel = { selected = null }
            )
        }) {
            if (count < 3) return@Canvas
            val radius = size.minDimension / 2 - labelInset.toPx()
            fun point(index: Int, scale: Float) = Offset(
                center.x + (cos(angle(index)) * radius * scale).toFloat(),
                center.y + (sin(angle(index)) * radius * scale).toFloat()
            )
            fun polygon(scales: List<Float>) = Path().apply {
                scales.forEachIndexed { i, s -> val p = point(i, s); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                close()
            }
            val grid = Color.White.copy(alpha = 0.08f)
            for (level in 1..4) drawPath(polygon(List(count) { level / 4f }), grid, style = Stroke(1.dp.toPx()))
            for (i in 0 until count) drawLine(grid, center, point(i, 1f), 1.dp.toPx())
            val shape = polygon(animated)
            drawPath(shape, Brush.verticalGradient(listOf(Palette.cyan.copy(alpha = 0.45f), Palette.blue.copy(alpha = 0.25f))))
            drawPath(shape, Palette.cyan.copy(alpha = 0.25f), style = Stroke(7.dp.toPx(), join = StrokeJoin.Round))
            drawPath(shape, Palette.cyan, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
            selected?.let { index ->
                drawLine(Palette.cyan.copy(alpha = 0.8f), center, point(index, 1f), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                val vertex = point(index, animated[index])
                drawCircle(Color.White, 7.dp.toPx(), vertex)
                drawCircle(Palette.cyan, 5.dp.toPx(), vertex)
            }
        }
        // Labels around the wheel; the selected axis turns into a pill with its score.
        Layout(
            content = {
                labels.forEachIndexed { index, label ->
                    if (selected == index) {
                        val value = values[index]
                        Column(
                            Modifier.background(Color(0xFF0E1730), CircleShape).border(1.dp, Palette.cyan.copy(alpha = 0.6f), CircleShape)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("${Formatters.decimal(value, if (value == Math.rint(value)) 0 else 1)} / ${maxValue.toInt()}", color = Palette.textPrimary, autoSize = fit(15.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(label, color = Palette.cyan, autoSize = fit(11.sp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Box(Modifier.width(76.dp)) {
                            Text(label, color = Palette.textSecondary, autoSize = fit(11.sp), fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                val cx = constraints.maxWidth / 2f
                val cy = constraints.maxHeight / 2f
                val labelRadius = minOf(cx, cy) - labelInset.toPx() + 30.dp.toPx()
                placeables.forEachIndexed { i, placeable ->
                    val x = cx + cos(angle(i)) * labelRadius - placeable.width / 2
                    val y = cy + sin(angle(i)) * labelRadius - placeable.height / 2
                    placeable.place(x.roundToInt(), y.roundToInt())
                }
            }
        }
    }
}
