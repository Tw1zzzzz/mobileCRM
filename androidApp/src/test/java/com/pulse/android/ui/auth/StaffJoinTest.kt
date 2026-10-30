package com.pulse.android.ui.auth

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pulse.android.ui.remoteTestCore
import com.pulse.android.ui.setMain
import com.pulse.android.ui.testCore
import com.pulse.core.PulseCore
import com.pulse.core.api.Backend
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** A helper joins a team as staff: «Вступить по коду стаффа» on sign-up, and the staff profile shows both team codes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StaffJoinTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun field(name: String) = rule.onNode(hasSetTextAction() and hasText(name))

    private fun exists(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun openStaffRegistration(core: PulseCore) {
        rule.setContent { AuthScreen(core.session) }
        rule.onNodeWithText("Зарегистрироваться").performScrollTo().performClick()
        rule.onNodeWithText("Командный доступ").performClick()
        rule.onNodeWithText("Я стафф").performClick()
        rule.waitForIdle()
    }

    @Test fun staffJoinModeShowsCodeField() {
        openStaffRegistration(remoteTestCore())
        // Creating a team is the default: its name, no code.
        assertEquals(true, exists("Создать команду"))
        field("Название команды").assertExists()
        assertEquals(false, exists("Код стаффа"))

        rule.onNodeWithText("Вступить по коду стаффа").performClick()
        rule.waitForIdle()
        field("Код стаффа").performScrollTo().assertExists()
        assertEquals(false, exists("Название команды"))
        rule.onNodeWithText("Код стаффа даёт тренер команды.").assertExists()
    }

    @Test fun localBuildHasNoStaffJoin() {
        openStaffRegistration(testCore(null))
        assertEquals(false, exists("Вступить по коду стаффа"))
        field("Название команды").assertExists()
    }

    private fun signedInStaff(inviteCode: String?, staffCode: String?): PulseCore {
        fun json(value: String?) = value?.let { "\"$it\"" } ?: "null"
        val user = """{"id":"6F1B6A2E-1C2D-4E5F-8A9B-0C1D2E3F4A5B","nickname":"Тренер","email":"coach@example.com",""" +
            """"role":"staff","tariff":"free","team":"Pulse Academy","invite_code":${json(inviteCode)},"staff_code":${json(staffCode)},""" +
            """"streak_days":0,"faceit_url":null}"""
        val engine = MockEngine {
            val data = if (it.url.encodedPath.endsWith("auth/login")) """{"user":$user,"access_token":"A","refresh_token":"R"}""" else "[]"
            respond("""{"success":true,"data":$data,"error":null}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val core = testCore(null, backend = Backend.Remote("https://api.test/v1/"), http = HttpClient(engine))
        runBlocking { core.session.login("coach@example.com", "test-pass-1") }
        return core
    }

    @Test fun profileShowsBothCodes() {
        rule.setMain(signedInStaff("ABCDEF", "K7M2XP"), "profile")
        rule.onNodeWithText("Код для игроков").performScrollTo().assertExists()
        rule.onNodeWithText("ABCDEF").assertExists()
        rule.onNodeWithText("Код для стаффа").performScrollTo().assertExists()
        rule.onNodeWithText("K7M2XP").assertExists()
    }

    @Test fun profileWithoutCodesShowsNoCodeRows() {
        rule.setMain(signedInStaff(null, null), "profile")
        rule.onNodeWithText("Команда").performScrollTo().assertExists()
        assertEquals(false, exists("Код для игроков"))
        assertEquals(false, exists("Код для стаффа"))
    }
}
