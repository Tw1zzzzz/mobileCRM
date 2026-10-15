package com.pulse.android.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pulse.core.util.Tint

/** Design tokens from the Pulse mockups (same values as iOS `Theme`). */
object Palette {
    val background = Color(0xFF050A16)
    val backgroundGlow = Color(0xFF0D2550)
    val card = Color(0xFF0D1628)
    val cardHighlight = Color(0xFF121E35)
    val tile = Color(0xFF0F1A2F)
    val stroke = Color.White.copy(alpha = 0.08f)
    val divider = Color.White.copy(alpha = 0.07f)
    val track = Color(0xFF1A2640)

    val textPrimary = Color.White
    val textSecondary = Color(0xFF8B98B2)
    val textTertiary = Color(0xFF5D6A84)
    val section = Color(0xFF8695B0)

    val blue = Color(0xFF1E7CFF)
    val cyan = Color(0xFF00D4FF)
    val lightBlue = Color(0xFF4F7DF6)
    val navyBar = Color(0xFF1D4BA8)
    val green = Color(0xFF2CD67A)
    val orange = Color(0xFFF8AC2A)
    val yellow = Color(0xFFF5C443)
    val purple = Color(0xFFB27DFF)
    val red = Color(0xFFFF5A5F)
    val pink = Color(0xFFFF4F8B)
    val restZone = Color(0xFF2A3E70)
    val tabBar = Color(0xFF060B18)
}

object Gradients {
    val accent = Brush.horizontalGradient(listOf(Palette.blue, Palette.cyan))
    val accentDiagonal = Brush.linearGradient(listOf(Color(0xFF1C6BFF), Palette.cyan))
    val card = Brush.verticalGradient(listOf(Palette.cardHighlight, Palette.card))
}

object Metrics {
    val screenPadding = 16.dp
    val cardRadius = 22.dp
    val tileRadius = 18.dp
    val buttonRadius = 14.dp
    val cardPadding = 18.dp
    val sectionSpacing = 26.dp
    val headerButtonSize = 46.dp
}

/** Core palette tokens → Compose colors. */
val Tint.color: Color
    get() = when (this) {
        Tint.CYAN -> Palette.cyan
        Tint.BLUE -> Palette.blue
        Tint.LIGHT_BLUE -> Palette.lightBlue
        Tint.GREEN -> Palette.green
        Tint.ORANGE -> Palette.orange
        Tint.YELLOW -> Palette.yellow
        Tint.PURPLE -> Palette.purple
        Tint.RED -> Palette.red
        Tint.PINK -> Palette.pink
        Tint.REST_ZONE -> Palette.restZone
        Tint.TEXT_SECONDARY -> Palette.textSecondary
    }
