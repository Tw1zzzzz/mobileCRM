package com.pulse.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.auth.LegalDocument
import com.pulse.android.ui.theme.Palette

/** Opened by Health Connect («Почему приложению нужен доступ»): the privacy policy, read-only. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val document = LegalDocument.PRIVACY
        setContent {
            Column(
                Modifier.fillMaxSize().background(Palette.background).systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)
            ) {
                Text(document.title, color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(document.text, color = Palette.textSecondary, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 14.dp))
            }
        }
    }
}
