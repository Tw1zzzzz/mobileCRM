package com.pulse.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.pulse.android.ui.theme.sfIcon
import java.util.Locale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.theme.Palette
import kotlin.math.roundToInt

/** Titled slider with the integer value on the right (iOS `PulseSlider`). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PulseSlider(
    title: String, value: Double, range: ClosedFloatingPointRange<Double> = 1.0..10.0, tint: Color = Palette.cyan, step: Double = 1.0,
    valueText: String? = null, label: String = title, onChange: (Double) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text(title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(valueText ?: "${value.roundToInt()}", color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        val steps = ((range.endInclusive - range.start) / step).roundToInt() - 1
        val colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = tint, inactiveTrackColor = Palette.track,
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
        // iOS look: round white thumb on a continuous track, no Material stop dot or thumb gap.
        Slider(
            value.toFloat(), { onChange((it / step).roundToInt() * step) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(), steps = steps.coerceIn(0, 200), colors = colors,
            modifier = Modifier.semantics { contentDescription = label },
            thumb = { Box(Modifier.size(26.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White)) },
            track = { state ->
                SliderDefaults.Track(state, colors = colors, drawStopIndicator = null, drawTick = { _, _ -> }, thumbTrackGapSize = 0.dp,
                    modifier = Modifier.height(6.dp))
            }
        )
    }
}

/**
 * Search tile: magnifier, placeholder, clear button. The whole tile is the text field, so a tap anywhere on it
 * focuses the input and TalkBack reads [placeholder] as its name.
 */
@Composable
fun SearchField(query: String, placeholder: String, padding: Dp = 12.dp, onChange: (String) -> Unit) {
    BasicTextField(
        query, onChange, singleLine = true, textStyle = TextStyle(color = Palette.textPrimary, fontSize = 16.sp),
        cursorBrush = SolidColor(Palette.cyan), modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        decorationBox = { input ->
            Row(Modifier.pulseTile(padding), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(sfIcon("magnifyingglass"), null, tint = Palette.textSecondary, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(placeholder, color = Palette.textTertiary, fontSize = 16.sp)
                    input()
                }
                if (query.isNotEmpty()) {
                    Icon(sfIcon("xmark.circle.fill"), "Очистить", tint = Palette.textTertiary, modifier = Modifier.size(18.dp).pressable { onChange("") })
                }
            }
        }
    )
}

/** Text field styled for the dark cards (iOS `PulseTextField`). */
@Composable
fun PulseTextField(
    title: String, value: String, placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true,
    secure: Boolean = false, onChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Palette.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        TextField(
            value, onChange, placeholder = { Text(placeholder, color = Palette.textTertiary) }, singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = if (secure) KeyboardType.Password else keyboard, autoCorrectEnabled = !secure),
            visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1C2333), unfocusedContainerColor = Color(0xFF1C2333),
                focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Palette.cyan,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** The app is Russian-only: system pickers inside follow ru (month names, weekdays, Monday first), not the phone language. */
@Composable
fun RussianLocale(content: @Composable () -> Unit) {
    val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag("ru")) }
    CompositionLocalProvider(LocalConfiguration provides configuration, content = content)
}
