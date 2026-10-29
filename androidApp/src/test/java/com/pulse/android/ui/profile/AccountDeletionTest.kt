package com.pulse.android.ui.profile

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pulse.android.ui.TEST_USER_JSON
import com.pulse.android.ui.setMain
import com.pulse.android.ui.testCore
import com.pulse.core.PulseCore
import com.pulse.core.api.Backend
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** «Удалить аккаунт» on the Pulse server: the account goes only once the server confirmed; offline it stays. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AccountDeletionTest {
    @get:Rule val rule = createComposeRule()
    private val online = AtomicBoolean(false)
    private val deletes = mutableListOf<String>()

    /** Signed in to a server with nothing stored; `DELETE /me` fails offline until [online]. */
    private fun signedIn(): PulseCore {
        val session = """{"user":$TEST_USER_JSON,"access_token":"A","refresh_token":"R"}"""
        val engine = MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/v1/")
            val data = when {
                path == "me" && request.method == HttpMethod.Delete -> {
                    synchronized(deletes) { deletes += path }
                    if (!online.get()) throw IOException("no network")
                    "{}"
                }
                path == "auth/login" -> session
                path == "sync" -> "[]"
                else -> """{"version":1,"updated_at":"2026-10-20T08:00:00Z"}"""
            }
            respond("""{"success":true,"data":$data,"error":null}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val core = testCore(null, backend = Backend.Remote("https://api.test/v1/"), http = HttpClient(engine))
        runBlocking { core.session.login("tester@example.com", "test-pass-1") }
        return core
    }

    private fun delete() {
        rule.onNodeWithText("Удалить аккаунт").performScrollTo().performClick()
        rule.onNodeWithText("Удалить безвозвратно").performClick()
    }

    @Test fun offlineDeletionShowsTheErrorAndKeepsTheAccount() {
        val core = signedIn()
        rule.setMain(core, "profile")
        delete()
        rule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            rule.onAllNodesWithText("Не удалось установить соединение с сервером").fetchSemanticsNodes().isNotEmpty()
        }
        assertNotNull(core.session.user.value)
        rule.onNodeWithText("Удалить аккаунт").assertExists()
        assertEquals(1, deletes.size)
    }

    @Test fun deletionSignsOutOnceTheServerConfirms() {
        val core = signedIn()
        online.set(true)
        rule.setMain(core, "profile")
        delete()
        rule.waitUntil(5_000) {
            // The core answers on the main looper, which a Robolectric test only runs when it asks for it.
            ShadowLooper.idleMainLooper()
            core.session.user.value == null
        }
        assertTrue(deletes.isNotEmpty())
    }
}
