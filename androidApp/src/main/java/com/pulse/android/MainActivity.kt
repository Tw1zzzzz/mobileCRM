package com.pulse.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pulse.android.platform.PulseNotifications
import com.pulse.android.platform.UsageStatsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulse.android.ui.auth.AuthScreen
import com.pulse.android.ui.main.MainScreen
import com.pulse.android.ui.theme.Palette

class MainActivity : FontCappedActivity() {
    /** Screen requested by a tapped notification (`PulseNotifications.EXTRA_OPEN`), consumed by [MainScreen]. */
    private var pendingOpen by mutableStateOf<String?>(null)

    /** Health Connect keeps collecting while Pulse is closed — refresh whenever the app comes back. */
    override fun onResume() {
        super.onResume()
        val app = application as PulseApplication
        app.appScope.launch { app.core.fitness.refreshHealth() }
        // Usage statistics also keep counting in the background; reading 30 days takes a moment, so off the main thread.
        app.appScope.launch {
            val usage = withContext(Dispatchers.IO) { UsageStatsReader(app).read(Clock.System.now()) }
            app.core.screenTime.applyUsage(usage)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(PulseNotifications.EXTRA_OPEN)?.let { pendingOpen = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val core = (application as PulseApplication).core
        val debugRoute = if (BuildConfig.DEBUG) intent.getStringExtra("route") else null
        if (savedInstanceState == null) pendingOpen = intent.getStringExtra(PulseNotifications.EXTRA_OPEN)
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
                // Keyed by the account id string, not the User: its hash mixes in enum identity hashes, which can differ
                // in a new process, and the saved screen state (open tab, back stacks) is found by this key after process death.
                Crossfade(user?.id?.toString(), label = "session") { id ->
                    val current = user?.takeIf { it.id.toString() == id }
                    when {
                        id == null -> AuthScreen(core.session)
                        current != null -> MainScreen(core, current, debugRoute, pendingOpen) { pendingOpen = null }
                    }
                }
            }
        }
    }
}
