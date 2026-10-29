package com.pulse.android.ui.main

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.pulse.android.ui.TEST_USER_JSON
import com.pulse.android.ui.setMain
import com.pulse.android.ui.testCore
import com.pulse.core.PulseCore
import com.pulse.core.SyncStatus
import com.pulse.core.api.Backend
import com.pulse.core.demo.PlayerSurveyState
import com.pulse.core.model.BodyProfile
import com.pulse.core.model.PlayerSurvey
import com.pulse.core.util.PulseJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * The questionnaire and «О тебе» wait for the first download after sign-in: answered on another phone, they must not
 * be asked again — and «Позже» or an empty «О тебе» saved over still-empty stores would replace the player's answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OnboardingAfterFirstLoadTest {
    @get:Rule val rule = createComposeRule()

    /** A remote core signed in to a server holding [documents] (collection → document); `GET /sync` waits for [gate]. */
    private fun signedIn(gate: CompletableDeferred<Unit>, documents: Map<String, JsonElement> = emptyMap()): PulseCore {
        val session = """{"user":$TEST_USER_JSON,"access_token":"A","refresh_token":"R"}"""
        val stamp = "2026-10-20T08:00:00Z"
        val engine = MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/v1/")
            val data = when {
                path == "auth/login" -> session
                path == "sync" -> {
                    gate.await()
                    JsonArray(documents.keys.sorted().map { buildJsonObject { put("collection", it); put("version", 1); put("updated_at", stamp) } }).toString()
                }
                path.startsWith("sync/") && request.method == HttpMethod.Get -> buildJsonObject {
                    put("collection", path.removePrefix("sync/"))
                    put("version", 1)
                    put("updated_at", stamp)
                    put("document", documents.getValue(path.removePrefix("sync/")))
                }.toString()
                else -> """{"version":1,"updated_at":"$stamp"}"""
            }
            respond("""{"success":true,"data":$data,"error":null}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val core = testCore(null, backend = Backend.Remote("https://api.test/v1/"), http = HttpClient(engine))
        runBlocking { core.session.login("tester@example.com", "test-pass-1") }
        return core
    }

    /** The core answers on the main looper, which a Robolectric test only runs when it asks for it. */
    private fun awaitStatus(core: PulseCore, expected: SyncStatus) {
        val deadline = System.currentTimeMillis() + 5_000
        while (core.syncStatus.value != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            ShadowLooper.idleMainLooper()
        }
        assertEquals(expected, core.syncStatus.value)
        rule.waitForIdle()
    }

    private fun hasText(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** The welcome step of the questionnaire («Позже») or «О тебе» on its own («Выйти из аккаунта»). */
    private fun onboardingShown() = hasText("Позже") || hasText("Выйти из аккаунта")

    @Test fun answersOnTheServerAreNotAskedForAgain() {
        val gate = CompletableDeferred<Unit>()
        val core = signedIn(gate, mapOf(
            "player-survey" to PulseJson.encodeToJsonElement(PlayerSurveyState.serializer(), PlayerSurveyState(survey = PlayerSurvey())),
            "body-profile" to PulseJson.encodeToJsonElement(BodyProfile.serializer(), BodyProfile.demo)
        ))
        assertEquals(SyncStatus.FirstLoad, core.syncStatus.value)
        rule.setMain(core)
        rule.waitForIdle()
        assertFalse(onboardingShown(), "nothing is asked while the first load runs")

        gate.complete(Unit)
        awaitStatus(core, SyncStatus.Idle)
        assertNotNull(core.survey.survey)
        assertNotNull(core.body.profile)
        assertFalse(onboardingShown())
    }

    @Test fun questionnaireFollowsTheFirstLoadWhenStillNeeded() {
        val gate = CompletableDeferred<Unit>()
        val core = signedIn(gate)
        rule.setMain(core)
        rule.waitForIdle()
        assertFalse(onboardingShown())

        gate.complete(Unit)
        awaitStatus(core, SyncStatus.Idle)
        rule.waitUntil(5_000) { hasText("Позже") }
    }
}
