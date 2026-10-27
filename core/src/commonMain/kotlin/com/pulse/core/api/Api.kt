package com.pulse.core.api

import com.pulse.core.auth.AuthService
import com.pulse.core.auth.DemoAuth
import com.pulse.core.auth.LocalAuthService
import com.pulse.core.auth.RegistrationOutcome
import com.pulse.core.model.AuthError
import com.pulse.core.model.AuthException
import com.pulse.core.model.BetaTerms
import com.pulse.core.model.DemoAccounts
import com.pulse.core.model.RegistrationForm
import com.pulse.core.model.Tariff
import com.pulse.core.model.User
import com.pulse.core.model.UserRole
import com.pulse.core.platform.SecretStore
import com.pulse.core.storage.DataScope
import com.pulse.core.storage.SyncLedger
import com.pulse.core.util.PulseJson
import com.pulse.core.util.UuidSerializer
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

/** Where data lives: on the phone (first release) or the Pulse API. Switching turns on server sign-in and sync. */
sealed class Backend {
    data object Local : Backend()
    data class Remote(val baseUrl: String) : Backend()
}

/** API wire format: snake_case keys, ISO dates (see docs/API.md). */
val ApiJson: Json = Json(PulseJson) { namingStrategy = JsonNamingStrategy.SnakeCase }

class ApiException(val kind: Kind, val code: String? = null, val serverMessage: String? = null, val status: Int? = null) :
    Exception("API $kind ${code ?: ""} ${status ?: ""}".trim()) {
    enum class Kind { NOT_CONFIGURED, OFFLINE, UNAUTHORIZED, REJECTED, SERVER, UNREADABLE }

    val isOffline: Boolean get() = kind == Kind.OFFLINE || kind == Kind.NOT_CONFIGURED

    /** Maps to the user-facing texts from the spec («Авторизация»). */
    val authError: AuthError
        get() = when (kind) {
            Kind.OFFLINE, Kind.NOT_CONFIGURED -> AuthError.NO_CONNECTION
            Kind.UNAUTHORIZED -> AuthError.INVALID_CREDENTIALS
            Kind.REJECTED -> when (code) {
                "invalid_credentials" -> AuthError.INVALID_CREDENTIALS
                "email_taken" -> AuthError.EMAIL_TAKEN
                "team_not_found" -> AuthError.TEAM_NOT_FOUND
                "invalid_email" -> AuthError.INVALID_EMAIL
                "weak_password" -> AuthError.WEAK_PASSWORD
                "email_not_verified" -> AuthError.EMAIL_NOT_VERIFIED
                "invalid_code" -> AuthError.INVALID_CODE
                "code_expired" -> AuthError.CODE_EXPIRED
                "too_many_attempts" -> AuthError.TOO_MANY_ATTEMPTS
                "rate_limited" -> AuthError.RATE_LIMITED
                else -> AuthError.SERVER
            }
            Kind.SERVER, Kind.UNREADABLE -> AuthError.SERVER
        }
}

/** Access + refresh tokens, kept in the secure store (camelCase JSON, like iOS). */
@Serializable
data class AuthTokens(val accessToken: String, val refreshToken: String)

class TokenStore(private val secrets: SecretStore) {
    private val key = "api.tokens"

    var tokens: AuthTokens?
        get() = secrets.read(key)?.let { runCatching { PulseJson.decodeFromString(AuthTokens.serializer(), it) }.getOrNull() }
        set(value) = secrets.write(key, value?.let { PulseJson.encodeToString(AuthTokens.serializer(), it) })
}

/**
 * Thin JSON client for the Pulse API: bearer auth, one automatic token refresh on 401, envelope unwrapping.
 * Envelope: `{ "success": true, "data": …, "error": { "code", "message" }, "meta": { … } }`.
 */
class ApiClient(private val http: HttpClient, private val baseUrl: String?, val tokens: TokenStore) {
    /** False in a local build: every request fails with `NOT_CONFIGURED` before touching the network. */
    val isConfigured: Boolean get() = baseUrl != null

    /** Refresh tokens rotate and a reused one revokes every session on the server, so only one refresh runs at a time. */
    private val refreshLock = Mutex()

    suspend fun <T> send(method: HttpMethod, path: String, response: KSerializer<T>, body: JsonElement? = null, authorized: Boolean = true): T {
        val data = perform(method, path, body?.toString(), authorized, retryOnUnauthorized = authorized)
        return runCatching { ApiJson.decodeFromJsonElement(response, data ?: JsonNull) }
            .getOrElse { throw ApiException(ApiException.Kind.UNREADABLE) }
    }

    /** For endpoints without a payload in `data`. */
    suspend fun sendEmpty(method: HttpMethod, path: String, body: JsonElement? = null, authorized: Boolean = true) {
        perform(method, path, body?.toString(), authorized, retryOnUnauthorized = authorized)
    }

    /** Raw JSON body (document sync uploads the collection file as is). */
    suspend fun sendRaw(method: HttpMethod, path: String, json: String) {
        perform(method, path, json, authorized = true, retryOnUnauthorized = true)
    }

    private suspend fun perform(method: HttpMethod, path: String, body: String?, authorized: Boolean, retryOnUnauthorized: Boolean): JsonElement? {
        val base = baseUrl ?: throw ApiException(ApiException.Kind.NOT_CONFIGURED)
        val accessToken = if (authorized) tokens.tokens?.accessToken else null
        val response = try {
            http.request(base.trimEnd('/') + "/" + path.trimStart('/')) {
                this.method = method
                header("Accept", "application/json")
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                accessToken?.let { header("Authorization", "Bearer $it") }
            }
        } catch (error: Exception) {
            throw ApiException(ApiException.Kind.OFFLINE)
        }
        val status = response.status.value
        if (status == 401 && retryOnUnauthorized && refreshTokens(rejectedAccessToken = accessToken)) {
            return perform(method, path, body, authorized, retryOnUnauthorized = false)
        }
        val envelope = runCatching { ApiJson.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        val error = envelope?.get("error")?.takeIf { it !is JsonNull }?.jsonObject
        if (error != null) {
            if (status == 401) throw ApiException(ApiException.Kind.UNAUTHORIZED, status = status)
            throw ApiException(ApiException.Kind.REJECTED, error["code"]?.jsonPrimitive?.contentOrNull,
                error["message"]?.jsonPrimitive?.contentOrNull, status)
        }
        return when (status) {
            in 200..299 -> envelope?.get("data")?.takeIf { it !is JsonNull }
            401 -> throw ApiException(ApiException.Kind.UNAUTHORIZED, status = status)
            else -> throw ApiException(ApiException.Kind.SERVER, status = status)
        }
    }

    /**
     * `POST /auth/refresh` with the refresh token; on failure the session is over. Single-flight: a caller that waited
     * for another refresh finds the stored access token changed and retries with it instead of refreshing again.
     */
    private suspend fun refreshTokens(rejectedAccessToken: String?): Boolean = refreshLock.withLock {
        val current = tokens.tokens ?: return@withLock false
        if (current.accessToken != rejectedAccessToken) return@withLock true
        try {
            val body = ApiJson.encodeToJsonElement(RefreshBody.serializer(), RefreshBody(current.refreshToken))
            val data = perform(HttpMethod.Post, "auth/refresh", body.toString(), authorized = false, retryOnUnauthorized = false)
            tokens.tokens = ApiJson.decodeFromJsonElement(AuthTokens.serializer(), data ?: JsonNull)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            tokens.tokens = null
            false
        }
    }

    @Serializable
    private data class RefreshBody(val refreshToken: String)
}

/** Sign-in against the Pulse API (used when the build's `Backend` is `Remote`). Endpoints: docs/API.md → «Auth». */
class RemoteAuthService(private val client: ApiClient, private val background: CoroutineScope) : AuthService {
    @Serializable
    data class UserDto(
        @Serializable(with = UuidSerializer::class) val id: Uuid,
        val nickname: String,
        val email: String,
        val role: UserRole,
        val tariff: Tariff,
        val team: String? = null,
        val streakDays: Int? = null,
        val faceitUrl: String? = null
    ) {
        val model: User get() = User(id, nickname, email, role, tariff, team, streakDays ?: 0, faceitUrl)
    }

    @Serializable
    data class SessionResponse(val user: UserDto, val accessToken: String, val refreshToken: String)

    @Serializable
    private data class LoginBody(val email: String, val password: String)

    @Serializable
    data class RegisterRequest(
        val nickname: String,
        val email: String,
        val password: String,
        val role: UserRole,
        val faceitUrl: String?,
        val teamCode: String?,
        val teamName: String?,
        val acceptedTerms: Boolean,
        val acceptedPrivacy: Boolean,
        val acceptedBetaTerms: Boolean,
        val betaTermsVersion: String
    )

    /** `201` of `POST /auth/register`: no tokens until the emailed code is confirmed. */
    @Serializable
    private data class PendingVerification(val email: String)

    @Serializable
    private data class EmailBody(val email: String)

    @Serializable
    private data class CodeBody(val email: String, val code: String)

    @Serializable
    private data class ResetConfirmBody(val email: String, val code: String, val newPassword: String)

    @Serializable
    private data class TokenBody(val refreshToken: String)

    @Serializable
    private data class ProfileBody(val nickname: String, val faceitUrl: String?)

    /** Demo addresses never reach the server: they sign in on the phone, also in remote mode. */
    override suspend fun login(email: String, password: String): User {
        val normalized = LocalAuthService.normalize(email)
        DemoAuth.login(normalized, password)?.let { return it }
        return signIn("auth/login", ApiJson.encodeToJsonElement(LoginBody.serializer(), LoginBody(normalized, password)))
    }

    override suspend fun register(form: RegistrationForm): RegistrationOutcome {
        val email = LocalAuthService.normalize(form.email)
        if (email in DemoAccounts.emails) throw AuthException(AuthError.EMAIL_TAKEN)
        fun trimmed(text: String) = text.trim().ifEmpty { null }
        val request = RegisterRequest(
            nickname = form.nickname.trim(),
            email = email,
            password = form.password,
            role = form.resolvedRole,
            faceitUrl = trimmed(form.faceitURL),
            teamCode = if (form.isTeamPlayer) trimmed(form.teamCode)?.uppercase() else null,
            teamName = if (form.isTeamStaff) trimmed(form.teamName) else null,
            acceptedTerms = form.acceptedTerms,
            acceptedPrivacy = form.acceptedPrivacy,
            acceptedBetaTerms = form.acceptedBetaTerms,
            betaTermsVersion = BetaTerms.VERSION
        )
        return try {
            val body = ApiJson.encodeToJsonElement(RegisterRequest.serializer(), request)
            val pending = client.send(HttpMethod.Post, "auth/register", PendingVerification.serializer(), body, authorized = false)
            RegistrationOutcome.VerificationSent(pending.email)
        } catch (error: ApiException) {
            val mapped = error.authError
            throw AuthException(
                when {
                    error.isOffline -> AuthError.NO_CONNECTION
                    mapped == AuthError.SERVER -> AuthError.REGISTRATION_SERVER
                    else -> mapped
                }
            )
        }
    }

    override suspend fun verifyEmail(email: String, code: String): User =
        signIn("auth/verify-email", ApiJson.encodeToJsonElement(CodeBody.serializer(), CodeBody(LocalAuthService.normalize(email), code)))

    override suspend fun resendCode(email: String) = mapErrors {
        client.sendEmpty(HttpMethod.Post, "auth/resend-code", emailBody(email), authorized = false)
    }

    override suspend fun requestPasswordReset(email: String) = mapErrors {
        client.sendEmpty(HttpMethod.Post, "auth/password-reset", emailBody(email), authorized = false)
    }

    override suspend fun confirmPasswordReset(email: String, code: String, newPassword: String): User = signIn(
        "auth/password-reset/confirm",
        ApiJson.encodeToJsonElement(ResetConfirmBody.serializer(), ResetConfirmBody(LocalAuthService.normalize(email), code, newPassword))
    )

    /**
     * Revokes the refresh token on the server. The tokens are dropped right away (even if the request fails),
     * so a quick sign-in afterwards cannot lose its new session to this late request.
     */
    override fun logout() {
        val refresh = client.tokens.tokens?.refreshToken ?: return
        client.tokens.tokens = null
        background.launch {
            runCatching {
                client.sendEmpty(HttpMethod.Post, "auth/logout", ApiJson.encodeToJsonElement(TokenBody.serializer(), TokenBody(refresh)), authorized = false)
            }
        }
    }

    override fun updateProfile(user: User) {
        background.launch {
            runCatching {
                client.sendEmpty(HttpMethod.Patch, "me", ApiJson.encodeToJsonElement(ProfileBody.serializer(), ProfileBody(user.nickname, user.faceitURL)))
            }
        }
    }

    override fun deleteAccount(user: User) {
        background.launch {
            runCatching { client.sendEmpty(HttpMethod.Delete, "me") }
            client.tokens.tokens = null
        }
    }

    /** Posts [body] to an endpoint that answers with a session, stores the tokens and returns the user. */
    private suspend fun signIn(path: String, body: JsonElement): User = mapErrors {
        val response = client.send(HttpMethod.Post, path, SessionResponse.serializer(), body, authorized = false)
        client.tokens.tokens = AuthTokens(response.accessToken, response.refreshToken)
        response.user.model
    }

    private fun emailBody(email: String): JsonElement =
        ApiJson.encodeToJsonElement(EmailBody.serializer(), EmailBody(LocalAuthService.normalize(email)))

    private suspend fun <T> mapErrors(action: suspend () -> T): T = try {
        action()
    } catch (error: ApiException) {
        throw AuthException(error.authError)
    }
}

/** Pushes locally changed collections to `PUT /sync/{collection}` (idle while the backend is local). */
class SyncEngine(private val client: ApiClient, private val scope: DataScope, private val ledger: SyncLedger = SyncLedger(scope)) {
    val isEnabled: Boolean get() = client.isConfigured

    /** Returns the collections that were uploaded; stops at the first failure (the rest stay pending). */
    suspend fun pushPending(folder: String = scope.folder(), force: Boolean = false): List<String> {
        if (!isEnabled && !force) return emptyList()
        val uploaded = mutableListOf<String>()
        for (collection in ledger.pending(folder)) {
            val json = scope.files.readText("$folder/$collection") ?: continue
            try {
                client.sendRaw(HttpMethod.Put, "sync/${collection.removeSuffix(".json")}", json)
                ledger.markSynced(collection, folder = folder)
                uploaded += collection
            } catch (error: ApiException) {
                break
            }
        }
        return uploaded
    }
}
