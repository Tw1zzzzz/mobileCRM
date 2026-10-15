package com.pulse.core.storage

import com.pulse.core.PulseCore
import com.pulse.core.auth.DemoAuth
import com.pulse.core.model.RegistrationForm
import com.pulse.core.model.UserRole
import com.pulse.core.platform.JvmFileSystem
import com.pulse.core.platform.MemorySecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The composition root keeps every account in its own folder: demo is seeded, a new account starts empty. */
class PulseCoreTests {
    private fun core(root: java.io.File) = PulseCore(
        JvmFileSystem(root), MemorySecretStore(), HttpClient(MockEngine { respond("") }), CoroutineScope(Dispatchers.Unconfined), health = null
    )

    @Test fun accountsDoNotShareData() = runTest {
        val root = Files.createTempDirectory("pulse-core").toFile()
        val core = core(root)
        core.session.switchToDemo(UserRole.TEAM_PLAYER)
        assertEquals("demo-teamPlayer", core.scope.current)
        assertTrue(core.sleep.sessions.isNotEmpty())
        assertTrue(core.nutrition.meals.isNotEmpty())
        assertFalse(core.survey.needsOnboarding)
        core.sleep.startSleep()
        core.sleep.stopSleep()

        core.session.logout()
        core.session.register(RegistrationForm(nickname = "Тестер", email = "t@team.gg", password = "abcd1234", confirmPassword = "abcd1234",
            acceptedTerms = true, acceptedPrivacy = true))
        assertEquals(UserRole.SOLO_PLAYER, core.session.user.value?.role)
        assertTrue(core.sleep.sessions.isEmpty())
        assertTrue(core.nutrition.meals.isEmpty())
        assertTrue(core.wellbeing.moodEntries.isEmpty())
        assertTrue(core.survey.needsOnboarding)
        assertNull(core.sleep.displayNight())

        // Restart: the session and the account folder survive.
        val restarted = core(root)
        assertEquals("t@team.gg", restarted.session.user.value?.email)
        assertTrue(restarted.sleep.sessions.isEmpty())
        restarted.session.logout()
        restarted.session.login("player@pulse.app", DemoAuth.DEMO_PASSWORD)
        assertTrue(restarted.sleep.sessions.size >= 30) // demo history + the night recorded above
    }
}
