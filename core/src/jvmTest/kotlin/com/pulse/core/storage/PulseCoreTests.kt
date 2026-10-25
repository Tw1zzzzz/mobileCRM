package com.pulse.core.storage

import com.pulse.core.PulseCore
import com.pulse.core.auth.DemoAuth
import com.pulse.core.model.AboutYou
import com.pulse.core.model.BodyProfile
import com.pulse.core.model.FitnessGoals
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
import kotlinx.datetime.LocalDate
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

    private suspend fun registered(root: java.io.File): PulseCore = core(root).also {
        it.session.register(RegistrationForm(nickname = "Тестер", email = "t@team.gg", password = "abcd1234", confirmPassword = "abcd1234",
            acceptedTerms = true, acceptedPrivacy = true))
    }

    private fun filled(core: PulseCore) = AboutYou.initial(core.sleep.window, core.fitness.goals, core.nutrition.goals,
        core.screenTime.state.value.dailyLimitMinutes, core.body.profile, isNewPlayer = true)
        .copy(birthDate = LocalDate(2004, 3, 12), heightText = "182", bedtimeHour = 23, bedtimeMinute = 30, wakeHour = 7, wakeMinute = 30,
            activeMinutes = 45, steps = 8_500, activeKcal = 500, screenLimitHours = 4)
        .withWeightText("74").withFoodKcal(2_600).withWater(2_750)

    @Test fun demoAccountsHaveAProfile() = runTest {
        val core = core(Files.createTempDirectory("pulse-core").toFile())
        core.session.switchToDemo(UserRole.TEAM_PLAYER)
        assertEquals(BodyProfile.demo, core.body.profile)
        assertFalse(core.needsAboutYou)
    }

    @Test fun needsAboutYouOnlyForRealPlayers() = runTest {
        val root = Files.createTempDirectory("pulse-core").toFile()
        val core = registered(root)
        assertNull(core.body.profile)
        assertTrue(core.needsAboutYou)
        assertTrue(core.applyAboutYou(filled(core)))
        assertFalse(core.needsAboutYou)
        core.session.switchToDemo(UserRole.STAFF)
        assertFalse(core.needsAboutYou)
    }

    @Test fun applyAboutYouWritesEveryMetric() = runTest {
        val root = Files.createTempDirectory("pulse-core").toFile()
        val core = registered(root)
        val protein = core.nutrition.goals.protein
        assertTrue(core.applyAboutYou(filled(core)))
        assertEquals(listOf(23, 30, 7, 30), core.sleep.window.let { listOf(it.bedtimeHour, it.bedtimeMinute, it.wakeHour, it.wakeMinute) })
        assertEquals(FitnessGoals(45, 8_500, 500), core.fitness.goals)
        assertEquals(2_600, core.nutrition.goals.kcal)
        assertEquals(2_750, core.nutrition.goals.waterMilliliters)
        assertEquals(protein, core.nutrition.goals.protein)
        assertEquals(240, core.screenTime.state.value.dailyLimitMinutes)
        assertEquals(182, core.body.profile?.heightCm)
        // Saved in the account's own file: a restart keeps it.
        val restarted = core(root)
        assertEquals(74.0, restarted.body.profile?.weightKg)
        assertFalse(restarted.needsAboutYou)
    }

    @Test fun invalidFormChangesNothing() = runTest {
        val core = registered(Files.createTempDirectory("pulse-core").toFile())
        val before = Triple(core.sleep.window, core.fitness.goals, core.nutrition.goals)
        assertFalse(core.applyAboutYou(filled(core).copy(heightText = "")))
        assertEquals(before, Triple(core.sleep.window, core.fitness.goals, core.nutrition.goals))
        assertNull(core.body.profile)
    }

    @Test fun bodyMetricsFromProfileWithoutHealth() = runTest {
        val core = registered(Files.createTempDirectory("pulse-core").toFile())
        assertTrue(core.fitness.bodyMetrics.isEmpty())
        core.applyAboutYou(filled(core))
        assertEquals(listOf("Вес", "Рост", "ИМТ"), core.fitness.bodyMetrics.map { it.title })
    }

    @Test fun updateBodyKeepsGoalsAndWindow() = runTest {
        val core = registered(Files.createTempDirectory("pulse-core").toFile())
        core.applyAboutYou(filled(core))
        core.screenTime.setLimit(330)
        val before = listOf(core.sleep.window, core.fitness.goals, core.nutrition.goals, core.screenTime.state.value.dailyLimitMinutes)
        val edited = AboutYou.initial(core.sleep.window, core.fitness.goals, core.nutrition.goals, 330, core.body.profile, isNewPlayer = false)
            .copy(heightText = "185").withWeightText("80")
        assertTrue(core.updateBody(edited))
        assertEquals(185, core.body.profile?.heightCm)
        assertEquals(80.0, core.body.profile?.weightKg)
        assertEquals(before, listOf(core.sleep.window, core.fitness.goals, core.nutrition.goals, core.screenTime.state.value.dailyLimitMinutes))
        assertFalse(core.updateBody(edited.copy(heightText = "")))
        assertEquals(185, core.body.profile?.heightCm)
    }
}
