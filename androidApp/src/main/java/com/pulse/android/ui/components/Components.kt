package com.pulse.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.theme.Gradients
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette

/** Dark navy background with the soft blue glow at the top of every screen. */
@Composable
fun ScreenBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Palette.background)) {
        Canvas(Modifier.fillMaxWidth().height(420.dp)) {
            drawRect(
                Brush.radialGradient(
                    listOf(Palette.backgroundGlow.copy(alpha = 0.85f), Color.Transparent),
                    center = Offset(size.width / 2, 0f),
                    radius = 380.dp.toPx()
                )
            )
        }
        content()
    }
}

/** Card with the vertical gradient and the thin stroke (iOS `pulseCard`). */
fun Modifier.pulseCard(padding: Dp = Metrics.cardPadding, radius: Dp = Metrics.cardRadius): Modifier =
    fillMaxWidth()
        .clip(RoundedCornerShape(radius))
        .background(Gradients.card)
        .border(1.dp, Palette.stroke, RoundedCornerShape(radius))
        .padding(padding)

fun Modifier.pulseTile(padding: Dp = 16.dp, radius: Dp = Metrics.tileRadius): Modifier =
    fillMaxWidth()
        .clip(RoundedCornerShape(radius))
        .background(Palette.tile)
        .border(1.dp, Palette.stroke, RoundedCornerShape(radius))
        .padding(padding)

/**
 * For `Text(autoSize = fit(13.sp), maxLines = …)`: the text keeps [size] while it fits on its lines with whole words,
 * and shrinks (to at most 70% of [size]) when it would be cut or break a word in half — large font scale, narrow
 * phone. Single long words in narrow tiles may go down to [minScale] (0.6). With autoSize set, Text ignores its `fontSize`.
 */
fun fit(size: TextUnit, minScale: Float = MIN_FIT_SCALE): TextAutoSize = WholeWordsFit(size, minScale)

private data class WholeWordsFit(private val size: TextUnit, private val minScale: Float) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        val smallest = size.value * minScale
        var current = size.value
        while (current > smallest) {
            if (performLayout(constraints, text, current.sp).fitsWholeWords()) return current.sp
            current -= FIT_STEP
        }
        if (!performLayout(constraints, text, smallest.sp).fitsWholeWords()) FitReport.onUnfit?.invoke(text.text)
        return smallest.sp
    }
}

/** Test hook: told about every fit() text that does not fit even at its smallest size (it ends in «…»). */
internal object FitReport {
    @Volatile var onUnfit: ((String) -> Unit)? = null
}

private const val MIN_FIT_SCALE = 0.7f
private const val FIT_STEP = 0.5f

/** All lines shown, and no line ends inside a word. */
internal fun TextLayoutResult.fitsWholeWords(): Boolean {
    if (multiParagraph.didExceedMaxLines || didOverflowHeight) return false
    val text = layoutInput.text.text
    return (0 until lineCount - 1).none { line ->
        val end = getLineEnd(line)
        end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
    }
}

/**
 * Slight shrink while pressed (iOS `.pressable`). TalkBack reads [role] («кнопка» by default) and [label] as the
 * action; with [selected] the control is selectable (tabs, chips, segments) and announces its state.
 */
@Composable
fun Modifier.pressable(enabled: Boolean = true, role: Role? = Role.Button, label: String? = null, selected: Boolean? = null, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scaled = scale(if (pressed) 0.97f else 1f)
    return if (selected != null) scaled.selectable(selected, interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
    else scaled.clickable(interaction, indication = null, enabled = enabled, onClickLabel = label, role = role, onClick = onClick)
}

@Composable
fun GradientButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    brush: Brush = Gradients.accent,
    onClick: () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            // Dims the whole button (background included) when disabled, like iOS.
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(Metrics.buttonRadius))
            .background(brush)
            .pressable(enabled && !isLoading, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            icon?.let {
                Icon(it, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Box(Modifier.width(10.dp))
            }
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SecondaryButton(title: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = Palette.textPrimary, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(Metrics.buttonRadius))
            .background(Palette.tile)
            .border(1.dp, Palette.stroke, RoundedCornerShape(Metrics.buttonRadius))
            .pressable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Icon(it, null, tint = tint, modifier = Modifier.size(20.dp))
            Box(Modifier.width(10.dp))
        }
        Text(title, color = tint, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A row with a trailing `Switch(checked, onCheckedChange = null)`: the whole row toggles, and TalkBack reads its text
 * together with the switch state.
 */
fun Modifier.switchRow(checked: Boolean, onChange: (Boolean) -> Unit): Modifier =
    toggleable(checked, interactionSource = null, indication = null, role = Role.Switch, onValueChange = onChange)

/** Selectable chip used for filters and tags. */
@Composable
fun ChipButton(title: String, isSelected: Boolean, tint: Color? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .then(if (isSelected) Modifier.background(tint?.let { Brush.horizontalGradient(listOf(it, it)) } ?: Gradients.accent) else Modifier.background(Palette.tile).border(1.dp, Palette.stroke, CircleShape))
            .pressable(role = Role.Tab, selected = isSelected, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(title, color = if (isSelected) Color.White else Palette.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ErrorBanner(message: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.red.copy(alpha = 0.12f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = Palette.red, modifier = Modifier.size(20.dp))
        Text(message, color = Palette.red, fontSize = 14.sp)
    }
}

/** Uppercase, letter-spaced section title with an optional trailing link. */
@Composable
fun SectionHeader(title: String, actionTitle: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), color = Palette.section, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp,
            modifier = Modifier.weight(1f).semantics { heading() })
        if (actionTitle != null && onAction != null) {
            Text("$actionTitle →", color = Palette.cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.pressable(onClick = onAction))
        }
    }
}

@Composable
fun PulseSection(title: String, actionTitle: String? = null, onAction: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title, actionTitle, onAction)
        content()
    }
}

/** Rounded-square tinted icon used in list rows and tiles. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 40.dp, iconSize: Dp = 18.dp, background: Color? = null) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(background ?: tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}
