package com.pulse.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulse.android.ui.auth.AuthScreen
import com.pulse.android.ui.main.MainScreen
import com.pulse.android.ui.theme.Palette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val core = (application as PulseApplication).core
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.cyan, background = Palette.background, surface = Palette.card)) {
                val user by core.session.user.collectAsStateWithLifecycle()
                Crossfade(user, label = "session") { current ->
                    if (current == null) AuthScreen(core.session) else MainScreen(core, current)
                }
            }
        }
    }
}
