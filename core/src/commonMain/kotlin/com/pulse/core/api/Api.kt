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
import com.pulse.core.util.PulseJson
import com.pulse.core.util.UuidSerializer
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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

class ApiException(
    val kind: Kind,
    val code: String? = null,
    val serverMessage: String? = null,
    val status: Int? = null,
    /** On `version_conflict`: the server's version of the document (`meta.current_version`, 0 = nothing stored). */
    val currentVersion: Int? = null
) : Exception("API $kind ${code ?: ""} ${status ?: ""}".trim()) {
    enum class Kind { NOT_CONFIGURED, OFFLINE, UNAUTHORIZED, REJECTED, SERVER, UNREADABLE }

    val isOffline: Boolean get() = kind == Kind.OFFLINE || kind == Kind.NOT_CONFIGURED

    /** A versioned write (`PUT /sync/{collection}`) based on an outdated copy. */
    val isVersionConflict: Boolean get() = code == VERSION_CONFLICT

    companion object {
        const val VERSION_CONFLICT = "version_conflict"
    }

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
                "mail_unavailable" -> AuthError.MAIL_UNAVAILABLE
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
        val data = perform(method, path, body?.let(::jsonContent), authorized)
        return runCatching { ApiJson.decodeFromJsonElement(response, data ?: JsonNull) }
            .getOrElse { throw ApiException(ApiException.Kind.UNREADABLE) }
    }

    /** For endpoints without a payload in `data`. */
    suspend fun sendEmpty(method: HttpMethod, path: String, body: JsonElement? = null, authorized: Boolean = true) {
        perform(method, path, body?.let(::jsonContent), authorized)
    }

    /** Binary upload (a meal photo to `PUT /photos/{meal_id}`); the answer is an ordinary envelope. */
    suspend fun sendBytes(method: HttpMethod, path: String, bytes: ByteArray, contentType: ContentType = ContentType.Image.JPEG) {
        perform(method, path, ByteArrayContent(bytes, contentType), authorized = true)
    }

    /** Binary download (`GET /photos/{meal_id}` answers raw JPEG); errors still come as an envelope. */
    suspend fun getBytes(path: String): ByteArray {
        val response = execute(HttpMethod.Get, path, body = null, authorized = true, accept = "image/jpeg, application/json")
        if (response.status.value !in 200..299) throw failure(response)
        return try {
            response.readRawBytes()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ApiException(ApiException.Kind.OFFLINE)
        }
    }

    private fun jsonContent(body: JsonElement) = TextContent(body.toString(), ContentType.Application.Json)

    /** Sends the request and unwraps the envelope's `data`. */
    private suspend fun perform(method: HttpMethod, path: String, body: OutgoingContent?, authorized: Boolean): JsonElement? {
        val response = execute(method, path, body, authorized)
        if (response.status.value !in 200..299) throw failure(response)
        val envelope = runCatching { ApiJson.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        if (envelope?.get("error")?.takeIf { it !is JsonNull } != null) throw failure(response.status.value, envelope)
        return envelope?.get("data")?.takeIf { it !is JsonNull }
    }

    /** One request with bearer auth; a 401 refreshes the tokens once and repeats the request. */
    private suspend fun execute(
        method: HttpMethod,
        path: String,
        body: OutgoingContent?,
        authorized: Boolean,
        retryOnUnauthorized: Boolean = authorized,
        accept: String = "application/json"
    ): HttpResponse {
        val base = baseUrl ?: throw ApiException(ApiException.Kind.NOT_CONFIGURED)
        val accessToken = if (authorized) tokens.tokens?.accessToken else null
        val response = try {
            http.request(base.trimEnd('/') + "/" + path.trimStart('/')) {
                this.method = method
                header("Accept", accept)
                body?.let(::setBody)
                accessToken?.let { header("Authorization", "Bearer $it") }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ApiException(ApiException.Kind.OFFLINE)
        }
        if (response.status.value == 401 && retryOnUnauthorized && refreshTokens(rejectedAccessToken = accessToken)) {
            return execute(method, path, body, authorized, retryOnUnauthorized = false, accept = accept)
        }
        return response
    }

    private suspend fun failure(response: HttpResponse): ApiException {
        val envelope = runCatching { ApiJson.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        return failure(response.status.value, envelope)
    }

    private fun failure(status: Int, envelope: JsonObject?): ApiException {
        val error = envelope?.get("error")?.takeIf { it !is JsonNull }?.jsonObject
        return when {
            status == 401 -> ApiException(ApiException.Kind.UNAUTHORIZED, status = status)
            error != null -> {
                val code = error["code"]?.jsonPrimitive?.contentOrNull
                val currentVersion = if (code == ApiException.VERSION_CONFLICT) {
                    (envelope["meta"] as? JsonObject)?.get("current_version")?.jsonPrimitive?.intOrNull
                } else null
                ApiException(ApiException.Kind.REJECTED, code, error["message"]?.jsonPrimitive?.contentOrNull, status, currentVersion)
            }
            else -> ApiException(ApiException.Kind.SERVER, status = status)
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
            val data = perform(HttpMethod.Post, "auth/refresh", jsonContent(body), authorized = false)
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
