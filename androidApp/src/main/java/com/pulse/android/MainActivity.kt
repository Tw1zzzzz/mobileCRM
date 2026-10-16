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
        val debugRoute = if (BuildConfig.DEBUG) intent.getStringExtra("route") else null
        if (BuildConfig.DEBUG) intent.getStringExtra("demo")?.let { role ->
            val target = com.pulse.core.model.UserRole.entries.firstOrNull { it.name.equals(role, ignoreCase = true) || it.badgeTitle.equals(role, ignoreCase = true) }
                ?: if (role == "player") com.pulse.core.model.UserRole.TEAM_PLAYER else null
            if (target != null && core.session.user.value?.role != target) {
                kotlinx.coroutines.runBlocking { core.session.switchToDemo(target) }
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.cyan, background = Palette.background, surface = Palette.card)) {
                val user by core.session.user.collectAsStateWithLifecycle()
                Crossfade(user, label = "session") { current ->
                    if (current == null) AuthScreen(core.session) else MainScreen(core, current, debugRoute)
                }
            }
        }
    }
}
