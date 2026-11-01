package com.pulse.core.storage

import com.pulse.core.PulseCore
import com.pulse.core.api.Backend
import com.pulse.core.model.RegistrationForm
import com.pulse.core.model.UserRole
import com.pulse.core.platform.JvmFileSystem
import com.pulse.core.platform.MemorySecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The seeded demo team (players, readiness, food…) is shown to demo accounts only, never to a real staff account. */
class TeamDemoGateTests {
    private fun core() = PulseCore(
        JvmFileSystem(Files.createTempDirectory("pulse-gate").toFile()), MemorySecretStore(), HttpClient(MockEngine { respond("") }),
        CoroutineScope(Dispatchers.Unconfined), null
    )

    @Test fun signedOutShowsNoTeamDemo() {
        assertFalse(core().showsTeamDemo)
    }

    @Test fun everyDemoAccountShowsTheTeamDemo() = runTest {
        for (role in UserRole.entries) {
            val core = core()
            core.session.switchToDemo(role)
            assertTrue(core.session.user.value?.isDemo == true, "$role")
            assertTrue(core.showsTeamDemo, "$role")
        }
    }

    /** A staff account signed in on the Pulse server (the mock answers sign-in with a staff user, everything else empty). */
    @Test fun serverStaffShowsNoTeamDemo() = runTest {
        val staff = """{"id":"6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b","nickname":"Coach","email":"coach@team.gg","role":"staff",""" +
            """"tariff":"free","team":"Pulse Academy","streak_days":0,"faceit_url":null}"""
        val engine = MockEngine { request ->
            val data = when (request.url.encodedPath.removePrefix("/v1/")) {
                "auth/login" -> """{"user":$staff,"access_token":"A","refresh_token":"R"}"""
                "sync" -> "[]"
                else -> """{"version":1,"updated_at":"2026-10-20T08:00:00Z"}"""
            }
            respond("""{"success":true,"data":$data,"error":null}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val core = PulseCore(JvmFileSystem(Files.createTempDirectory("pulse-gate").toFile()), MemorySecretStore(), HttpClient(engine),
            CoroutineScope(Dispatchers.Unconfined), null, backend = Backend.Remote("https://api.test/v1/"))
        core.session.login("coach@team.gg", "abcd1234")
        assertTrue(core.session.user.value?.role?.isStaff == true)
        assertFalse(core.session.user.value?.isDemo == true)
        assertFalse(core.showsTeamDemo)
    }

    @Test fun registeredPlayerShowsNoTeamDemo() = runTest {
        val core = core()
        core.session.register(RegistrationForm(nickname = "Тестер", email = "t@team.gg", password = "abcd1234", confirmPassword = "abcd1234",
            acceptedTerms = true, acceptedPrivacy = true))
        assertFalse(core.showsTeamDemo)
    }
}
