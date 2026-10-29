package com.pulse.core.storage

import com.pulse.core.api.ApiClient
import com.pulse.core.api.ApiException
import com.pulse.core.api.AuthTokens
import com.pulse.core.api.RemoteAuthService
import com.pulse.core.api.TokenStore
import com.pulse.core.auth.SessionStore
import com.pulse.core.model.GameGoals
import com.pulse.core.model.RegistrationForm
import com.pulse.core.model.User
import com.pulse.core.platform.JvmFileSystem
import com.pulse.core.platform.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ordinary mobile conditions (no network, a 5xx, a rate limit, a closed screen, a sign-out mid-request) never sign the
 * tester out; only the server rejecting the refresh token does. Also: the code step sends the password, account
 * deletion waits for the server.
 */
class SessionResilienceTests {
    private val requests = ConcurrentLinkedQueue<HttpRequestData>()
    private val backgroundJob = SupervisorJob()
    private val background = CoroutineScope(Dispatchers.Unconfined + backgroundJob)
    private val refreshes = AtomicInteger()
    private val userJson = """{"id":"6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b","nickname":"Nova","email":"nova@team.gg","role":"soloPlayer",""" +
        """"tariff":"free","team":null,"streak_days":0,"faceit_url":null}"""
    private val signedIn = ok("""{"user":$userJson,"access_token":"A1","refresh_token":"R1"}""")
    private val rotated = ok("""{"access_token":"new","refresh_token":"R2"}""")

    private fun ok(data: String, status: Int = 200) = status to """{"success":true,"data":$data,"error":null}"""
    private fun fail(status: Int, code: String) = status to """{"success":false,"data":null,"error":{"code":"$code","message":"x"}}"""

    /** [refresh] answers `POST /auth/refresh`; `GET /me` succeeds only with the new access token. */
    private fun client(secrets: SecretStore = RecordingSecretStore(), refresh: suspend () -> Pair<Int, String>): ApiClient {
        val engine = MockEngine { request ->
            requests += request
            val (status, body) = when {
                request.url.encodedPath == "/v1/auth/refresh" -> {
                    refreshes.incrementAndGet()
                    refresh()
                }
                request.url.encodedPath == "/v1/auth/logout" -> ok("{}")
                request.headers["Authorization"] == "Bearer new" -> ok(userJson)
                else -> fail(401, "token_expired")
            }
            respond(body, HttpStatusCode.fromValue(status))
        }
        return ApiClient(HttpClient(engine), "https://api.test/v1", TokenStore(secrets)).also { it.tokens.tokens = AuthTokens("old", "R1") }
    }

    private suspend fun ApiClient.me() = send(HttpMethod.Get, "me", RemoteAuthService.UserDto.serializer())

    // C1: only a rejected refresh token ends the session.

    @Test fun offlineRefreshKeepsTheTokens() = runTest {
        val client = client { throw IOException("no network") }
        val error = assertFailsWith<ApiException> { client.me() }
        assertEquals(ApiException.Kind.OFFLINE, error.kind)
        assertEquals(AuthTokens("old", "R1"), client.tokens.tokens)
    }

    @Test fun serverErrorOnRefreshKeepsTheTokens() = runTest {
        val client = client { 502 to "Bad gateway" }
        val error = assertFailsWith<ApiException> { client.me() }
        assertEquals(ApiException.Kind.SERVER, error.kind)
        assertEquals(AuthTokens("old", "R1"), client.tokens.tokens)
    }

    @Test fun rateLimitedRefreshKeepsTheTokens() = runTest {
        val client = client { fail(429, "rate_limited") }
        val error = assertFailsWith<ApiException> { client.me() }
        assertEquals("rate_limited", error.code)
        assertEquals(AuthTokens("old", "R1"), client.tokens.tokens)
        // Once the server answers again, the same refresh token still works.
        val recovered = client { rotated }.also { it.tokens.tokens = client.tokens.tokens }
        assertEquals("Nova", recovered.me().nickname)
    }

    @Test fun rejectedRefreshTokenEndsTheSession() = runTest {
        val client = client { fail(401, "unauthorized") }
        val error = assertFailsWith<ApiException> { client.me() }
        assertEquals(ApiException.Kind.UNAUTHORIZED, error.kind)
        assertNull(client.tokens.tokens)
    }

    // C2: a caller cancelled while the server rotates still stores the new pair.

    @Test fun cancelledCallerStillStoresTheRotatedTokens() = runTest {
        val refreshArrived = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        val client = client {
            refreshArrived.complete(Unit)
            answer.await()
            rotated
        }
        val caller = launch { client.me() }
        refreshArrived.await()
        caller.cancel()
        answer.complete(Unit)
        caller.join()
        assertTrue(caller.isCancelled)
        assertEquals(AuthTokens("new", "R2"), client.tokens.tokens)
        // The next request uses the new pair: no second refresh (which would reuse R1 and revoke every session).
        assertEquals("Nova", client.me().nickname)
        assertEquals(1, refreshes.get())
    }

    // Logout racing a refresh.

    @Test fun signOutDuringARefreshIsNotUndone() = runTest {
        val refreshArrived = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        val client = client {
            refreshArrived.complete(Unit)
            answer.await()
            rotated
        }
        val caller = async { runCatching { client.me() } }
        refreshArrived.await()
        RemoteAuthService(client, background).logout()
        answer.complete(Unit)
        val result = caller.await()
        backgroundJob.children.toList().joinAll()
        assertTrue(result.isFailure)
        assertNull(client.tokens.tokens)
    }

    // I4: the code step proves the password of the registration it confirms.

    private fun authClient(secrets: SecretStore, handler: (HttpRequestData) -> Pair<Int, String>): ApiClient {
        val engine = MockEngine { request ->
            requests += request
            val (status, body) = handler(request)
            respond(body, HttpStatusCode.fromValue(status))
        }
        return ApiClient(HttpClient(engine), "https://api.test/v1", TokenStore(secrets))
    }

    private fun HttpRequestData.text() = (body as TextContent).text

    private val pending = ok("""{"pending_verification":true,"email":"nova@team.gg"}""", 201)

    @Test fun codeStepAfterSignUpSendsThePasswordButNeverStoresIt() = runTest {
        val dir = Files.createTempDirectory("pulse-verify").toFile()
        val scope = DataScope(JvmFileSystem(dir))
        val secrets = RecordingSecretStore()
        val client = authClient(secrets) { request ->
            when (request.url.encodedPath) {
                "/v1/auth/register" -> pending
                "/v1/auth/verify-email" -> signedIn
                else -> fail(404, "not_found")
            }
        }
        val session = SessionStore(RemoteAuthService(client, background), JsonFileStorage("session.json", User.serializer(), scope, global = true), scope)
        session.register(
            RegistrationForm(nickname = "Nova", email = "nova@team.gg", password = "Secret-pass-77", confirmPassword = "Secret-pass-77",
                acceptedTerms = true, acceptedPrivacy = true, acceptedBetaTerms = true)
        )
        assertEquals("nova@team.gg", session.pendingVerification.value)
        assertNothingStoredContains("Secret-pass-77", dir, secrets)

        session.verifyEmail("123456")
        val body = requests.single { it.url.encodedPath == "/v1/auth/verify-email" }.text()
        assertTrue(body.contains(""""password":"Secret-pass-77""""), body)
        assertEquals("Nova", session.user.value?.nickname)
        assertNothingStoredContains("Secret-pass-77", dir, secrets)
    }

    @Test fun codeStepAfterAnUnverifiedSignInSendsTheTypedPassword() = runTest {
        val client = authClient(RecordingSecretStore()) { request ->
            when (request.url.encodedPath) {
                "/v1/auth/login" -> fail(403, "email_not_verified")
                "/v1/auth/verify-email" -> signedIn
                else -> fail(404, "not_found")
            }
        }
        val session = SessionStore(RemoteAuthService(client, background), null)
        session.login("Nova@Team.gg", "typed-pass-5")
        session.verifyEmail("654321")
        val body = requests.single { it.url.encodedPath == "/v1/auth/verify-email" }.text()
        assertTrue(body.contains(""""password":"typed-pass-5"""") && body.contains(""""code":"654321""""), body)
        assertNotNull(session.user.value)
    }

    private fun assertNothingStoredContains(secret: String, dir: File, secrets: RecordingSecretStore) {
        val files = dir.walkTopDown().filter { it.isFile }.filter { it.readText().contains(secret) }.toList()
        assertTrue(files.isEmpty(), "password on disk in $files")
        assertFalse(secrets.written.any { it.contains(secret) }, "password in the secure store")
    }

    // I5: «Удалить аккаунт» waits for the server.

    private fun deletion(answer: () -> Pair<Int, String>): Triple<ApiClient, SessionStore, DataScope> {
        val scope = DataScope(JvmFileSystem(Files.createTempDirectory("pulse-delete").toFile()))
        val client = authClient(RecordingSecretStore()) { request ->
            when (request.url.encodedPath) {
                "/v1/auth/login" -> signedIn
                "/v1/me" -> if (request.method == HttpMethod.Delete) answer() else fail(404, "not_found")
                else -> fail(404, "not_found")
            }
        }
        val session = SessionStore(RemoteAuthService(client, background), JsonFileStorage("session.json", User.serializer(), scope, global = true), scope)
        return Triple(client, session, scope)
    }

    private suspend fun signInWithData(session: SessionStore, scope: DataScope): JsonFileStorage<GameGoals> {
        session.login("nova@team.gg", "secret123")
        scope.current = DataScope.id(session.user.value!!)
        return JsonFileStorage("game-goals.json", GameGoals.serializer(), scope).also { it.save(GameGoals()) }
    }

    @Test fun deletionOfflineKeepsTheAccount() = runTest {
        val (client, session, scope) = deletion { throw IOException("no network") }
        val data = signInWithData(session, scope)
        assertEquals("Не удалось установить соединение с сервером", session.deleteAccount())
        assertEquals("Nova", session.user.value?.nickname)
        assertEquals(AuthTokens("A1", "R1"), client.tokens.tokens)
        assertNotNull(data.load())
    }

    @Test fun deletionRefusedByTheServerKeepsTheAccount() = runTest {
        val (client, session, scope) = deletion { 500 to "boom" }
        val data = signInWithData(session, scope)
        assertEquals("Не удалось удалить аккаунт. Повторите попытку позже", session.deleteAccount())
        assertNotNull(session.user.value)
        assertNotNull(client.tokens.tokens)
        assertNotNull(data.load())
    }

    @Test fun deletionErasesTheAccountOnceTheServerConfirms() = runTest {
        val (client, session, scope) = deletion { ok("{}") }
        val data = signInWithData(session, scope)
        assertNull(session.deleteAccount())
        assertNull(session.user.value)
        assertNull(client.tokens.tokens)
        assertNull(data.load())
        assertEquals(listOf("/v1/auth/login", "/v1/me"), requests.map { it.url.encodedPath })
    }
}

/** Remembers every value written, so a test can prove a secret never reached the store. */
private class RecordingSecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    val written = mutableListOf<String>()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value.also { written += it }
    }
}
