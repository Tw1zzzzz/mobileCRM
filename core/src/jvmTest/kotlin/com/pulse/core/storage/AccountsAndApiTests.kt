package com.pulse.core.storage

import com.pulse.core.api.ApiClient
import com.pulse.core.api.AuthTokens
import com.pulse.core.api.RemoteAuthService
import com.pulse.core.api.SyncEngine
import com.pulse.core.api.TokenStore
import com.pulse.core.auth.DemoAuth
import com.pulse.core.auth.LocalAuthService
import com.pulse.core.auth.RegistrationOutcome
import com.pulse.core.auth.SessionStore
import com.pulse.core.model.AccessType
import com.pulse.core.model.AuthError
import com.pulse.core.model.AuthException
import com.pulse.core.model.AuthValidator
import com.pulse.core.model.BetaTerms
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    private suspend fun LocalAuthService.signUp(form: RegistrationForm): User = assertIs<RegistrationOutcome.SignedIn>(register(form)).user

    private suspend fun expectError(error: AuthError, block: suspend () -> Unit) {
        val thrown = assertFailsWith<AuthException> { block() }
        assertEquals(error, thrown.error)
    }

    @Test fun registerThenLogin() = runTest {
        val scope = scope()
        val secrets = MemorySecretStore()
        val user = service(scope, secrets).signUp(form(email = "  Nova@Team.GG "))
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
        val user = auth.signUp(form(access = AccessType.TEAM))
        assertEquals(UserRole.TEAM_PLAYER, user.role)
        assertEquals("Команда ABC12", user.team)
        assertTrue(auth.login("solo@pulse.app", DemoAuth.DEMO_PASSWORD).isDemo)
    }

    @Test fun deleteAccountForgetsCredentialsAndProfileEditsAreKept() = runTest {
        val secrets = MemorySecretStore()
        val auth = service(scope(), secrets)
        val user = auth.signUp(form())
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
        assertFalse(client.isConfigured)
        assertTrue(ApiClient(HttpClient(MockEngine { respond("") }), "https://api.test/v1/", TokenStore(MemorySecretStore())).isConfigured)
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

/** Sign-up with an email code, password reset, beta terms and token handling against the Pulse API. */
class RemoteSignUpTests {
    private val requests = ConcurrentLinkedQueue<HttpRequestData>()
    private val backgroundJob = SupervisorJob()
    private val background = CoroutineScope(Dispatchers.Unconfined + backgroundJob)

    /** Fire-and-forget requests (logout, account deletion) finish on the engine's thread. */
    private suspend fun awaitBackground() = backgroundJob.children.toList().joinAll()
    private val userJson = """{"id":"6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b","nickname":"Nova","email":"nova@team.gg","role":"teamPlayer",""" +
        """"tariff":"free","team":"Pulse","team_id":"0c1d2e3f-4a5b-4e5f-8a9b-6f1b6a2e1c2d","invite_code":null,"streak_days":0,"faceit_url":null}"""
    private val signedIn = ok("""{"user":$userJson,"access_token":"A1","refresh_token":"R1"}""")

    private fun ok(data: String, status: Int = 200) = status to """{"success":true,"data":$data,"error":null}"""
    private fun fail(status: Int, code: String) = status to """{"success":false,"data":null,"error":{"code":"$code","message":"x"}}"""

    private fun client(handler: suspend (HttpRequestData) -> Pair<Int, String>): ApiClient {
        requests.clear()
        val engine = MockEngine { request ->
            requests += request
            val (status, body) = handler(request)
            respond(body, HttpStatusCode.fromValue(status))
        }
        return ApiClient(HttpClient(engine), "https://api.test/v1", TokenStore(MemorySecretStore()))
    }

    private fun session(client: ApiClient, requireBetaTerms: Boolean = false) =
        SessionStore(RemoteAuthService(client, background), null, requireBetaTerms = requireBetaTerms)

    private fun paths() = requests.map { it.url.encodedPath }
    private fun HttpRequestData.text() = (body as TextContent).text

    private fun form(betaTerms: Boolean = true, email: String = " Nova@Team.gg ") = RegistrationForm(
        nickname = "Nova", email = email, password = "secret123", confirmPassword = "secret123",
        acceptedTerms = true, acceptedPrivacy = true, acceptedBetaTerms = betaTerms
    )

    private val pending = ok("""{"pending_verification":true,"email":"nova@team.gg"}""", 201)

    @Test fun registerWaitsForCode() = runTest {
        val client = client { request ->
            when (request.url.encodedPath) {
                "/v1/auth/register" -> pending
                "/v1/auth/verify-email" -> {
                    assertTrue(request.text().contains(""""email":"nova@team.gg""""))
                    assertTrue(request.text().contains(""""code":"123456""""))
                    signedIn
                }
                else -> fail(404, "not_found")
            }
        }
        val session = session(client, requireBetaTerms = true)
        session.register(form())
        assertEquals("nova@team.gg", session.pendingVerification.value)
        assertNull(session.user.value)
        assertNull(session.errorMessage.value)
        assertNull(client.tokens.tokens)

        session.verifyEmail("123456")
        assertEquals("Nova", session.user.value?.nickname)
        assertNull(session.pendingVerification.value)
        assertEquals(AuthTokens("A1", "R1"), client.tokens.tokens)
        assertEquals(listOf("/v1/auth/register", "/v1/auth/verify-email"), paths())
    }

    @Test fun unverifiedLoginOpensCodeStep() = runTest {
        var resendLimited = false
        val client = client { request ->
            when (request.url.encodedPath) {
                "/v1/auth/login" -> fail(403, "email_not_verified")
                "/v1/auth/resend-code" -> if (resendLimited) fail(429, "rate_limited") else ok("{}")
                else -> fail(404, "not_found")
            }
        }
        val session = session(client)
        session.login(" Nova@Team.gg ", "secret123")
        assertEquals("nova@team.gg", session.pendingVerification.value)
        assertNull(session.errorMessage.value)
        assertNull(session.user.value)

        session.resendCode()
        assertNull(session.errorMessage.value)
        assertTrue(requests.last().text().contains(""""email":"nova@team.gg""""))
        resendLimited = true
        session.resendCode()
        assertEquals("Слишком много запросов. Попробуйте через несколько минут", session.errorMessage.value)
        assertEquals(listOf("/v1/auth/login", "/v1/auth/resend-code", "/v1/auth/resend-code"), paths())

        session.cancelVerification()
        assertNull(session.pendingVerification.value)
        assertNull(session.errorMessage.value)
    }

    @Test fun codeErrorsUseSpecTexts() = runTest {
        val client = client { request ->
            when {
                request.url.encodedPath == "/v1/auth/register" -> pending
                request.text().contains("111111") -> fail(422, "invalid_code")
                request.text().contains("222222") -> fail(429, "too_many_attempts")
                request.text().contains("333333") -> fail(422, "code_expired")
                else -> fail(404, "not_found")
            }
        }
        val session = session(client)
        session.register(form())
        session.verifyEmail("111111")
        assertEquals("Неверный код", session.errorMessage.value)
        session.verifyEmail("222222")
        assertEquals("Слишком много попыток. Запросите новый код", session.errorMessage.value)
        session.verifyEmail("333333")
        assertEquals("Срок действия кода истёк. Запросите новый", session.errorMessage.value)
        assertEquals(4, requests.size)

        session.clearError()
        session.verifyEmail("12a")
        assertEquals("Неверный код", session.errorMessage.value)
        session.verifyEmail("1234567")
        assertEquals("Неверный код", session.errorMessage.value)
        assertEquals(4, requests.size)
        assertEquals("nova@team.gg", session.pendingVerification.value)
        assertNull(session.user.value)
    }

    @Test fun betaTermsRequiredWhenRemote() = runTest {
        val client = client { pending }
        val session = session(client, requireBetaTerms = true)
        session.register(form(betaTerms = false))
        assertEquals("Для продолжения необходимо принять условия бета-тестирования", session.errorMessage.value)
        assertTrue(requests.isEmpty())
        // Local mode never asks for the beta terms.
        assertNull(AuthValidator.validateRegistration(form(betaTerms = false)))

        session.register(form())
        val body = requests.single().text()
        assertTrue(body.contains(""""accepted_beta_terms":true"""), body)
        assertTrue(body.contains(""""beta_terms_version":"2026-10-26""""), body)
        assertEquals("2026-10-26", BetaTerms.VERSION)
    }

    @Test fun resetFlowSignsIn() = runTest {
        val client = client { request ->
            when (request.url.encodedPath) {
                "/v1/auth/password-reset" -> ok("{}")
                "/v1/auth/password-reset/confirm" -> {
                    val body = request.text()
                    assertTrue(body.contains(""""email":"nova@team.gg"""") && body.contains(""""code":"123456"""") &&
                        body.contains(""""new_password":"newpass123""""), body)
                    signedIn
                }
                else -> fail(404, "not_found")
            }
        }
        val session = session(client)
        session.requestPasswordReset("  ")
        assertEquals("Заполните обязательные поля", session.errorMessage.value)
        session.requestPasswordReset("nova@")
        assertEquals("Укажите корректный адрес электронной почты", session.errorMessage.value)
        assertTrue(requests.isEmpty())

        session.requestPasswordReset(" Nova@Team.gg ")
        assertNull(session.errorMessage.value)
        assertEquals("nova@team.gg", session.pendingReset.value)
        assertTrue(requests.single().text().contains(""""email":"nova@team.gg""""))

        val invalid = listOf(
            Triple("", "", "") to "Заполните обязательные поля",
            Triple("12a456", "newpass123", "newpass123") to "Неверный код",
            Triple("123456", "short", "short") to "Пароль должен содержать не менее 8 символов, включая буквы и цифры",
            Triple("123456", "newpass123", "newpass124") to "Введенные пароли не совпадают"
        )
        for ((input, message) in invalid) {
            session.confirmPasswordReset(input.first, input.second, input.third)
            assertEquals(message, session.errorMessage.value, input.toString())
        }
        assertEquals(1, requests.size)

        session.confirmPasswordReset("123456", "newpass123", "newpass123")
        assertNull(session.errorMessage.value)
        assertEquals("Nova", session.user.value?.nickname)
        assertNull(session.pendingReset.value)
        assertEquals(AuthTokens("A1", "R1"), client.tokens.tokens)
        assertEquals(listOf("/v1/auth/password-reset", "/v1/auth/password-reset/confirm"), paths())

        session.requestPasswordReset("nova@team.gg")
        session.cancelReset()
        assertNull(session.pendingReset.value)
    }

    @Test fun demoSignInNeedsNoServer() = runTest {
        val client = client { throw IllegalStateException("no server") }
        val session = session(client)
        session.switchToDemo(UserRole.STAFF)
        assertEquals("Coach", session.user.value?.nickname)
        session.logout()
        session.login("player@pulse.app", "wrong")
        assertEquals("Неверный логин или пароль", session.errorMessage.value)
        val auth = RemoteAuthService(client, background)
        assertEquals(AuthError.EMAIL_TAKEN, assertFailsWith<AuthException> { auth.register(form(email = "solo@pulse.app")) }.error)
        assertTrue(requests.isEmpty(), paths().toString())
    }

    @Test fun concurrentUnauthorizedRefreshesOnce() = runTest {
        val bothRejected = CompletableDeferred<Unit>()
        val oldTokenHits = AtomicInteger()
        val refreshes = AtomicInteger()
        val client = client { request ->
            val bearer = request.headers["Authorization"]
            when {
                request.url.encodedPath == "/v1/auth/refresh" ->
                    // A reused refresh token is rejected (and on the real server revokes every session).
                    if (refreshes.incrementAndGet() == 1) ok("""{"access_token":"new","refresh_token":"R2"}""") else fail(401, "unauthorized")
                bearer == "Bearer old" -> {
                    if (oldTokenHits.incrementAndGet() == 2) bothRejected.complete(Unit)
                    bothRejected.await()
                    fail(401, "token_expired")
                }
                bearer == "Bearer new" -> ok(userJson)
                else -> fail(401, "unauthorized")
            }
        }
        client.tokens.tokens = AuthTokens("old", "R1")
        val users = coroutineScope {
            listOf(
                async { client.send(HttpMethod.Get, "me", RemoteAuthService.UserDto.serializer()) },
                async { client.send(HttpMethod.Get, "me", RemoteAuthService.UserDto.serializer()) }
            ).awaitAll()
        }
        assertEquals(listOf("Nova", "Nova"), users.map { it.nickname })
        assertEquals(1, refreshes.get())
        assertEquals(1, paths().count { it == "/v1/auth/refresh" })
        assertEquals(AuthTokens("new", "R2"), client.tokens.tokens)
    }

    @Test fun logoutTellsServer() = runTest {
        val client = client { request -> if (request.url.encodedPath == "/v1/auth/logout") ok("{}") else fail(404, "not_found") }
        client.tokens.tokens = AuthTokens("A1", "R1")
        val session = session(client)
        session.logout()
        awaitBackground()
        val request = requests.single()
        assertEquals("/v1/auth/logout", request.url.encodedPath)
        assertEquals(HttpMethod.Post, request.method)
        assertTrue(request.text().contains(""""refresh_token":"R1""""))
        assertNull(client.tokens.tokens)

        // Without a stored session there is nothing to tell the server.
        session.logout()
        assertEquals(1, requests.size)
    }

    @Test fun logoutClearsTokensWhenTheServerIsUnreachable() = runTest {
        val client = client { throw IllegalStateException("offline") }
        client.tokens.tokens = AuthTokens("A1", "R1")
        session(client).logout()
        assertNull(client.tokens.tokens)
        awaitBackground()
        assertEquals(listOf("/v1/auth/logout"), paths())
        assertNull(client.tokens.tokens)
    }

    @Test fun deleteAccountIsStillAuthorized() = runTest {
        val client = client { request ->
            when (request.url.encodedPath) {
                "/v1/auth/login" -> signedIn
                "/v1/me" -> if (request.headers["Authorization"] == "Bearer A1") ok("{}") else fail(401, "unauthorized")
                else -> fail(404, "not_found")
            }
        }
        val session = session(client)
        session.login("nova@team.gg", "secret123")
        assertEquals("Nova", session.user.value?.nickname)
        session.deleteAccount()
        awaitBackground()
        assertNull(session.user.value)
        assertEquals(listOf("/v1/auth/login", "/v1/me"), paths())
        assertEquals(HttpMethod.Delete, requests.last().method)
        assertNull(client.tokens.tokens)
    }
}
