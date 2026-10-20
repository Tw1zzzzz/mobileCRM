package com.pulse.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.theme.Gradients
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.model.DailyValue
import com.pulse.core.util.Formatters

/** Circular progress with the cyan→blue sweep used on Сон / Фитнес / Питание. */
@Composable
fun ProgressRing(progress: Double, modifier: Modifier = Modifier, lineWidth: Dp = 14.dp) {
    val target = progress.coerceIn(0.0, 1.0).toFloat()
    val animated by animateFloatAsState(target, tween(600), label = "ring")
    Canvas(modifier) {
        val stroke = lineWidth.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(Palette.track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (animated > 0f) {
            rotate(-90f) {
                drawArc(
                    Brush.sweepGradient(listOf(Palette.cyan, Color(0xFF1593FF), Palette.blue, Palette.cyan)),
                    0f, 360f * animated, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
    }
}

/** Ring with a big centered value and caption ("84 / ОЦЕНКА СНА"). */
@Composable
fun ScoreRing(progress: Double, value: String, caption: String, size: Dp = 150.dp, lineWidth: Dp = 14.dp, valueSize: TextUnit = 52.sp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        ProgressRing(progress, Modifier.fillMaxSize(), lineWidth)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(lineWidth + 6.dp)) {
            Text(value, color = Palette.textPrimary, fontSize = valueSize, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(caption, color = Palette.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp,
                maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

/** Horizontal capsule progress bar. */
@Composable
fun GradientBar(progress: Double, modifier: Modifier = Modifier, height: Dp = 10.dp, fill: Brush = Gradients.accent, track: Color = Palette.track) {
    val animated by animateFloatAsState(progress.coerceIn(0.0, 1.0).toFloat(), tween(500), label = "bar")
    BoxWithConstraints(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        if (animated > 0f) {
            Box(Modifier.fillMaxHeight().width(maxOf(height, maxWidth * animated)).clip(CircleShape).background(fill))
        }
    }
}

/** Rounded-square header button (back, share, settings, calendar, plus). */
@Composable
fun HeaderIconButton(icon: String, description: String, size: Dp = Metrics.headerButtonSize, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(Metrics.buttonRadius)).background(Palette.card)
            .border(1.dp, Palette.stroke, RoundedCornerShape(Metrics.buttonRadius)).pressable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { Icon(sfIcon(icon), null, tint = Palette.textPrimary, modifier = Modifier.size(size * 0.45f)) }
}

/** Top bar: square back button, centered title + optional subtitle, trailing buttons. */
@Composable
fun NavHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().heightIn(min = Metrics.headerButtonSize)) {
        Column(Modifier.align(Alignment.Center).padding(horizontal = Metrics.headerButtonSize + 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Palette.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = Palette.textSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Row(Modifier.fillMaxWidth().align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) HeaderIconButton("chevron.left", "Назад", onClick = onBack) else Spacer(Modifier.size(Metrics.headerButtonSize))
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = trailing)
        }
    }
}

/** Standard screen: fixed header + scrolling content (the background comes from the shell). */
@Composable
fun ScreenScaffold(
    spacing: Dp = Metrics.sectionSpacing,
    scrollState: ScrollState = rememberScrollState(),
    header: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.padding(horizontal = Metrics.screenPadding).padding(top = 4.dp, bottom = 10.dp)) { header() }
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = Metrics.screenPadding)
                .padding(top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.divider))
}

/** Number + small unit on a shared baseline: "56 уд/мин". */
@Composable
fun ValueUnitText(value: String, unit: String? = null, valueSize: TextUnit = 34.sp, unitSize: TextUnit = 17.sp, valueColor: Color = Palette.textPrimary) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value, color = valueColor, fontSize = valueSize, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.alignByBaseline())
        unit?.let { Text(it, color = Palette.textSecondary, fontSize = unitSize, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.alignByBaseline()) }
    }
}

/** "7ч 42м" with large numbers and small units. */
@Composable
fun DurationText(minutes: Int, valueSize: TextUnit = 40.sp, unitSize: TextUnit = 24.sp) {
    val parts = Formatters.durationParts(minutes)
    Row(verticalAlignment = Alignment.Bottom) {
        Text("${parts.hours}", color = Palette.textPrimary, fontSize = valueSize, fontWeight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
        Text("ч  ", color = Palette.textSecondary, fontSize = unitSize, fontWeight = FontWeight.SemiBold, modifier = Modifier.alignByBaseline())
        Text("${parts.minutes}", color = Palette.textPrimary, fontSize = valueSize, fontWeight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
        Text("м", color = Palette.textSecondary, fontSize = unitSize, fontWeight = FontWeight.SemiBold, modifier = Modifier.alignByBaseline())
    }
}

/** Small colored pill ("● Хорошо · +2 к базовому"). */
@Composable
fun StatusPill(text: String, tint: Color = Palette.green, showsDot: Boolean = true) {
    Row(
        Modifier.clip(CircleShape).background(tint.copy(alpha = 0.12f)).border(1.dp, tint.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        if (showsDot) Box(Modifier.size(9.dp).clip(CircleShape).background(tint))
        Text(text, color = tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Empty-state placeholder shared by nutrition, calendar, statistics, screen time. */
@Composable
fun EmptyStateView(icon: String, title: String, message: String) {
    Column(Modifier.pulseCard().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(Palette.track), contentAlignment = Alignment.Center) {
            Icon(sfIcon(icon), null, tint = Palette.textSecondary, modifier = Modifier.size(32.dp))
        }
        Text(title, color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(message, color = Palette.textSecondary, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

/** Tiny axis-less trend line for dashboard cards. */
@Composable
fun Sparkline(values: List<DailyValue>, modifier: Modifier = Modifier, color: Color = Palette.cyan, showsArea: Boolean = true) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val numbers = values.map { it.value }
        val low = numbers.min()
        val high = numbers.max()
        val pad = maxOf((high - low) * 0.15, 0.5)
        val range = (high + pad) - (low - pad)
        val points = numbers.mapIndexed { index, value ->
            Offset(size.width * index / (numbers.size - 1), (size.height * (1 - (value - (low - pad)) / range)).toFloat())
        }
        val line = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                val previous = points[i - 1]
                val current = points[i]
                val mid = (previous.x + current.x) / 2
                cubicTo(mid, previous.y, mid, current.y, current.x, current.y)
            }
        }
        if (showsArea) {
            val area = Path().apply {
                addPath(line)
                lineTo(points.last().x, size.height)
                lineTo(points.first().x, size.height)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), Color.Transparent)))
        }
        drawPath(line, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Equal-width segmented control (iOS `PulseSegmented`). */
@Composable
fun <T> PulseSegmented(options: List<T>, selected: T, title: (T) -> String, height: Dp = 42.dp, fontSize: TextUnit = 15.sp, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C2333)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier.weight(1f).height(height).clip(RoundedCornerShape(10.dp))
                    .then(if (isSelected) Modifier.background(Gradients.accent) else Modifier).pressable { onSelect(option) },
                contentAlignment = Alignment.Center
            ) {
                Text(title(option), color = if (isSelected) Color.White else Palette.textSecondary, fontSize = fontSize,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Scrollable tab strip: each tab takes its natural width (iOS `PulseScrollTabs`). */
@Composable
fun <T> PulseScrollTabs(options: List<T>, selected: T, title: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C2333)).horizontalScroll(rememberScrollState()).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier.height(38.dp).clip(RoundedCornerShape(10.dp)).then(if (isSelected) Modifier.background(Gradients.accent) else Modifier)
                    .pressable { onSelect(option) }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) { Text(title(option), color = if (isSelected) Color.White else Palette.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

/** Sheet title row with a close button (iOS `SheetScaffold`). */
/** Bottom sheet that opens at full content height (like iOS `.large`), never half-expanded. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.background,
        content = content
    )
}

@Composable
fun SheetScaffold(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Palette.track).pressable(onClick = onClose).semantics { contentDescription = "Закрыть" },
                contentAlignment = Alignment.Center
            ) { Icon(sfIcon("xmark"), null, tint = Palette.textSecondary, modifier = Modifier.size(18.dp)) }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp), content = content
        )
    }
}

/** Tinted icon, title, subtitle and chevron — rows of the hubs («Тесты», «Состояние»). */
@Composable
fun HubRow(icon: String, tint: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.pressable(onClick = onClick).pulseCard(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(sfIcon(icon), tint, 52.dp, 22.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, color = Palette.textSecondary, fontSize = 14.sp, maxLines = 2)
        }
        Icon(sfIcon("chevron.right"), null, tint = Palette.textTertiary, modifier = Modifier.size(16.dp))
    }
}
