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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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

/** Slight shrink while pressed (iOS `.pressable`). */
@Composable
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return scale(if (pressed) 0.97f else 1f).clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
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
            .clip(RoundedCornerShape(Metrics.buttonRadius))
            .background(brush)
            .alpha(if (enabled) 1f else 0.45f)
            .pressable(enabled && !isLoading, onClick),
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

/** Selectable chip used for filters and tags. */
@Composable
fun ChipButton(title: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .then(if (isSelected) Modifier.background(Gradients.accent) else Modifier.background(Palette.tile).border(1.dp, Palette.stroke, CircleShape))
            .pressable(onClick = onClick)
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
            modifier = Modifier.weight(1f))
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
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 40.dp, iconSize: Dp = 18.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}
