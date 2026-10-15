package com.pulse.core.storage

import com.pulse.core.api.ApiClient
import com.pulse.core.api.AppConfig
import com.pulse.core.api.AuthTokens
import com.pulse.core.api.Backend
import com.pulse.core.api.RemoteAuthService
import com.pulse.core.api.SyncEngine
import com.pulse.core.api.TokenStore
import com.pulse.core.auth.DemoAuth
import com.pulse.core.auth.LocalAuthService
import com.pulse.core.auth.SessionStore
import com.pulse.core.model.AccessType
import com.pulse.core.model.AuthError
import com.pulse.core.model.AuthException
import com.pulse.core.model.RegistrationForm
import com.pulse.core.model.Tariff
import com.pulse.core.model.TeamRole
import com.pulse.core.model.User
import com.pulse.core.model.UserRole
import com.pulse.core.platform.JvmFileSystem
import com.pulse.core.platform.MemorySecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Ported from PulseTests/LocalAccountTests.swift and APIClientTests.swift. */
class LocalAuthTests {
    private fun scope() = DataScope(JvmFileSystem(Files.createTempDirectory("pulse-tests").toFile()))

    private fun service(scope: DataScope, secrets: MemorySecretStore) = LocalAuthService(
        JsonFileStorage("accounts.json", ListSerializer(LocalAuthService.Account.serializer()), scope, global = true), secrets
    )

    private fun form(email: String = "nova@team.gg", password: String = "secret123", access: AccessType = AccessType.SOLO, role: TeamRole = TeamRole.PLAYER) =
        RegistrationForm(nickname = "Nova", email = email, password = password, confirmPassword = password, accessType = access,
            teamRole = role, teamCode = "abc12", acceptedTerms = true, acceptedPrivacy = true)

    private suspend fun expectError(error: AuthError, block: suspend () -> Unit) {
        val thrown = assertFailsWith<AuthException> { block() }
        assertEquals(error, thrown.error)
    }

    @Test fun registerThenLogin() = runTest {
        val scope = scope()
        val secrets = MemorySecretStore()
        val user = service(scope, secrets).register(form(email = "  Nova@Team.GG "))
        assertEquals("nova@team.gg", user.email)
        assertFalse(user.isDemo)
        // The password itself is never stored, only salt + hash.
        assertNull(secrets.read("auth.nova@team.gg")?.takeIf { it.contains("secret123") })
        assertEquals(user.id, service(scope, secrets).login("NOVA@team.gg", "secret123").id)
    }

    @Test fun wrongPasswordUnknownEmailDuplicatesAndStaff() = runTest {
        val auth = service(scope(), MemorySecretStore())
        auth.register(form())
        expectError(AuthError.INVALID_CREDENTIALS) { auth.login("nova@team.gg", "wrong1234") }
        expectError(AuthError.INVALID_CREDENTIALS) { auth.login("ghost@team.gg", "secret123") }
        expectError(AuthError.EMAIL_TAKEN) { auth.register(form()) }
        expectError(AuthError.EMAIL_TAKEN) { auth.register(form(email = "player@pulse.app")) }
        expectError(AuthError.STAFF_NEEDS_SERVER) { auth.register(form(email = "coach@team.gg", access = AccessType.TEAM, role = TeamRole.MANAGER)) }
    }

    @Test fun teamPlayerKeepsTheCodeAndDemoLoginWorks() = runTest {
        val auth = service(scope(), MemorySecretStore())
        val user = auth.register(form(access = AccessType.TEAM))
        assertEquals(UserRole.TEAM_PLAYER, user.role)
        assertEquals("Команда ABC12", user.team)
        assertTrue(auth.login("solo@pulse.app", DemoAuth.DEMO_PASSWORD).isDemo)
    }

    @Test fun deleteAccountForgetsCredentialsAndProfileEditsAreKept() = runTest {
        val secrets = MemorySecretStore()
        val auth = service(scope(), secrets)
        val user = auth.register(form())
        auth.updateProfile(user.copy(nickname = "Nova2"))
        assertEquals("Nova2", auth.login("nova@team.gg", "secret123").nickname)
        auth.deleteAccount(user)
        assertNull(secrets.read("auth.nova@team.gg"))
        expectError(AuthError.INVALID_CREDENTIALS) { auth.login("nova@team.gg", "secret123") }
        auth.register(form()) // the e-mail is free again
    }

    @Test fun sessionFlowWithSpecMessages() = runTest {
        val scope = scope()
        val session = SessionStore(service(scope, MemorySecretStore()), JsonFileStorage("session.json", User.serializer(), scope, global = true), scope)
        var changes = 0
        session.onUserChange = { changes++ }
        session.login("", "")
        assertEquals("Заполните обязательные поля", session.errorMessage.value)
        session.login("player@pulse.app", "wrong")
        assertEquals("Неверный логин или пароль", session.errorMessage.value)
        session.login("PLAYER@pulse.app ", DemoAuth.DEMO_PASSWORD)
        assertEquals(UserRole.TEAM_PLAYER, session.user.value?.role)
        assertEquals(1, changes)
        // The session survives a restart.
        assertEquals("Nova", SessionStore(service(scope, MemorySecretStore()), JsonFileStorage("session.json", User.serializer(), scope, global = true)).user.value?.nickname)
        assertEquals("Укажите корректную ссылку на профиль Faceit", session.updateProfile("Nova", "https://evil.com"))
        session.logout()
        assertNull(session.user.value)
    }
}

class DataScopeAndLedgerTests {
    @Test fun demoAndRealAccountsUseDifferentFolders() {
        val real = User(Uuid.random(), "A", "a@b.cc", UserRole.SOLO_PLAYER, Tariff.FREE, null, 0)
        assertEquals(real.id.toString().uppercase(), DataScope.id(real))
        assertEquals("demo-teamPlayer", DataScope.id(DemoAuth.accounts[0].user))
    }

    @Test fun changesStayPendingUntilSynced() {
        val scope = DataScope(JvmFileSystem(Files.createTempDirectory("pulse-ledger").toFile()))
        val ledger = SyncLedger(scope)
        val start = Instant.fromEpochSeconds(1_000_000)
        ledger.markChanged("sleep.json", start)
        assertEquals(listOf("sleep.json"), ledger.pending())
        ledger.markSynced("sleep.json", start.plus(kotlin.time.Duration.parse("60s")))
        assertTrue(ledger.pending().isEmpty())
        ledger.markChanged("sleep.json", start.plus(kotlin.time.Duration.parse("120s")))
        ledger.markChanged("nutrition.json", start)
        assertEquals(listOf("nutrition.json", "sleep.json"), ledger.pending())
    }

    @Test fun storageWritesIntoTheAccountFolderAndMarksTheLedger() {
        val scope = DataScope(JvmFileSystem(Files.createTempDirectory("pulse-storage").toFile()))
        scope.current = "demo-teamPlayer"
        val storage = JsonFileStorage("game-goals.json", com.pulse.core.model.GameGoals.serializer(), scope)
        storage.save(com.pulse.core.model.GameGoals(elo = 3000))
        assertEquals(3000, storage.load()?.elo)
        assertTrue(scope.files.exists("accounts/demo-teamPlayer/game-goals.json"))
        assertEquals(listOf("game-goals.json"), SyncLedger(scope).pending())
        scope.current = "other"
        assertNull(storage.load())
    }
}

class ApiClientTests {
    private val requests = mutableListOf<HttpRequestData>()
    private val userJson = """{"id":"6F1B6A2E-1C2D-4E5F-8A9B-0C1D2E3F4A5B","nickname":"Nova","email":"nova@team.gg","role":"teamPlayer","tariff":"free","team":"Pulse","streak_days":4,"faceit_url":null}"""

    private fun client(handler: (HttpRequestData) -> Pair<Int, String>): ApiClient {
        requests.clear()
        val engine = MockEngine { request ->
            requests += request
            val (status, body) = handler(request)
            respond(body, HttpStatusCode.fromValue(status))
        }
        return ApiClient(HttpClient(engine), "https://api.test/v1", TokenStore(MemorySecretStore()))
    }

    private val background = CoroutineScope(Dispatchers.Unconfined)

    @Test fun loginStoresTokensAndMapsUser() = runTest {
        val client = client { request ->
            assertEquals("/v1/auth/login", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            200 to """{"success":true,"data":{"user":$userJson,"access_token":"A1","refresh_token":"R1"},"error":null}"""
        }
        val user = RemoteAuthService(client, background).login("Nova@Team.gg ", "secret123")
        assertEquals("Nova", user.nickname)
        assertEquals(4, user.streakDays)
        assertEquals(AuthTokens("A1", "R1"), client.tokens.tokens)
        assertTrue((requests.first().body as TextContent).text.contains("nova@team.gg"))
    }

    @Test fun serverErrorsBecomeSpecMessages() = runTest {
        val taken = client { 409 to """{"success":false,"data":null,"error":{"code":"email_taken","message":"exists"}}""" }
        val form = RegistrationForm(nickname = "Nova", email = "nova@team.gg", password = "secret123", confirmPassword = "secret123")
        assertEquals(AuthError.EMAIL_TAKEN, assertFailsWith<AuthException> { RemoteAuthService(taken, background).register(form) }.error)
        val wrong = client { 401 to """{"success":false,"data":null,"error":{"code":"invalid_credentials","message":null}}""" }
        assertEquals(AuthError.INVALID_CREDENTIALS, assertFailsWith<AuthException> { RemoteAuthService(wrong, background).login("a@b.cc", "x") }.error)
    }

    @Test fun expiredTokenIsRefreshedOnce() = runTest {
        val client = client { request ->
            when {
                request.url.encodedPath == "/v1/auth/refresh" ->
                    200 to """{"success":true,"data":{"access_token":"new","refresh_token":"R2"},"error":null}"""
                request.url.encodedPath == "/v1/me" && request.headers["Authorization"] == "Bearer new" ->
                    200 to """{"success":true,"data":$userJson,"error":null}"""
                else -> 401 to """{"success":false,"data":null,"error":{"code":"token_expired","message":null}}"""
            }
        }
        client.tokens.tokens = AuthTokens("old", "R1")
        val me = client.send(HttpMethod.Get, "me", RemoteAuthService.UserDto.serializer())
        assertEquals("Nova", me.nickname)
        assertEquals("new", client.tokens.tokens?.accessToken)
        assertEquals(listOf("/v1/me", "/v1/auth/refresh", "/v1/me"), requests.map { it.url.encodedPath })
    }

    @Test fun notConfiguredMeansNoConnection() = runTest {
        val client = ApiClient(HttpClient(MockEngine { respond("") }), null, TokenStore(MemorySecretStore()))
        assertEquals(AuthError.NO_CONNECTION, assertFailsWith<AuthException> { RemoteAuthService(client, background).login("a@b.cc", "x") }.error)
        assertEquals(Backend.Local, AppConfig.backend)
    }

    @Test fun syncUploadsPendingCollectionsUntilAFailure() = runTest {
        val scope = DataScope(JvmFileSystem(Files.createTempDirectory("pulse-sync").toFile()))
        scope.current = "acc"
        JsonFileStorage("game-goals.json", com.pulse.core.model.GameGoals.serializer(), scope).save(com.pulse.core.model.GameGoals(elo = 1))
        JsonFileStorage("sleep.json", com.pulse.core.model.GameGoals.serializer(), scope).save(com.pulse.core.model.GameGoals(elo = 2))
        val client = client { request -> if (request.url.encodedPath.endsWith("game-goals")) 200 to """{"success":true}""" else 500 to "{}" }
        val uploaded = SyncEngine(client, scope).pushPending(force = true)
        assertEquals(listOf("game-goals.json"), uploaded)
        assertEquals(listOf("sleep.json"), SyncLedger(scope).pending())
        assertNotEquals(0, requests.size)
    }
}
