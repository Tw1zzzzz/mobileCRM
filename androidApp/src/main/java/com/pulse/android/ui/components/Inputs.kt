package com.pulse.android.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.theme.Palette
import kotlin.math.roundToInt

/** Titled slider with the integer value on the right (iOS `PulseSlider`). */
@Composable
fun PulseSlider(title: String, value: Double, range: ClosedFloatingPointRange<Double> = 1.0..10.0, tint: Color = Palette.cyan, step: Double = 1.0, onChange: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text(title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${value.roundToInt()}", color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        val steps = ((range.endInclusive - range.start) / step).roundToInt() - 1
        Slider(
            value.toFloat(), { onChange((it / step).roundToInt() * step) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(), steps = steps.coerceIn(0, 200),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = tint, inactiveTrackColor = Palette.track,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
        )
    }
}

/** Text field styled for the dark cards (iOS `PulseTextField`). */
@Composable
fun PulseTextField(title: String, value: String, placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Palette.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        TextField(
            value, onChange, placeholder = { Text(placeholder, color = Palette.textTertiary) }, singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard), shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1C2333), unfocusedContainerColor = Color(0xFF1C2333),
                focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Palette.cyan,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
