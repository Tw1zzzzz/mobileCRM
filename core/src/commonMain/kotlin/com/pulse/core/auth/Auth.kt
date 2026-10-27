@file:UseSerializers(IsoInstantSerializer::class)

package com.pulse.core.auth

import com.pulse.core.model.AuthError
import com.pulse.core.model.AuthException
import com.pulse.core.model.DemoAccounts
import com.pulse.core.model.RegistrationForm
import com.pulse.core.model.Tariff
import com.pulse.core.model.User
import com.pulse.core.model.UserRole
import com.pulse.core.platform.SecretStore
import com.pulse.core.platform.pbkdf2Sha256
import com.pulse.core.platform.secureRandomBytes
import com.pulse.core.storage.JsonFileStorage
import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.PulseJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Result of a sign-up: signed in right away (on the phone) or waiting for the email code (Pulse server). */
sealed interface RegistrationOutcome {
    data class SignedIn(val user: User) : RegistrationOutcome
    data class VerificationSent(val email: String) : RegistrationOutcome
}

/** Backend contract for authentication: local accounts or the Pulse API — screens don't change. */
interface AuthService {
    /** Throws [AuthException] with the spec message (an unverified address is [AuthError.EMAIL_NOT_VERIFIED]). */
    suspend fun login(email: String, password: String): User
    suspend fun register(form: RegistrationForm): RegistrationOutcome
    suspend fun verifyEmail(email: String, code: String): User = throw AuthException(AuthError.SERVER)
    suspend fun resendCode(email: String) {}
    suspend fun requestPasswordReset(email: String) {}
    suspend fun confirmPasswordReset(email: String, code: String, newPassword: String): User = throw AuthException(AuthError.SERVER)
    /** Ends the session on the backend (fire-and-forget). */
    fun logout() {}
    fun updateProfile(user: User) {}
    fun deleteAccount(user: User) {}
}

/** «Посмотреть демо»: seeded accounts (credentials are documented in README, not shown in the UI). */
object DemoAuth {
    data class Account(val email: String, val password: String, val user: User)

    const val DEMO_PASSWORD = "pulse2026"

    /** Invite codes of existing teams (demo). Real codes come from the backend. */
    val teamCodes = mapOf("PULSE" to "Pulse Esports")

    val accounts: List<Account> by lazy {
        listOf(
            Account("player@pulse.app", DEMO_PASSWORD,
                User(Uuid.random(), "Nova", "player@pulse.app", UserRole.TEAM_PLAYER, Tariff.PRO, "Pulse Esports", 12)),
            Account("solo@pulse.app", DEMO_PASSWORD,
                User(Uuid.random(), "Raven", "solo@pulse.app", UserRole.SOLO_PLAYER, Tariff.FREE, null, 3)),
            Account("staff@pulse.app", DEMO_PASSWORD,
                User(Uuid.random(), "Coach", "staff@pulse.app", UserRole.STAFF, Tariff.PRO, "Pulse Esports", 27))
        )
    }

    init {
        check(accounts.all { it.email in DemoAccounts.emails })
    }

    /** Demo sign-in on the phone: null when [normalizedEmail] is not a demo address, wrong password → INVALID_CREDENTIALS. */
    fun login(normalizedEmail: String, password: String): User? {
        val demo = accounts.firstOrNull { it.email == normalizedEmail } ?: return null
        if (demo.password != password) throw AuthException(AuthError.INVALID_CREDENTIALS)
        return demo.user
    }
}

/**
 * On-device accounts for the release without a backend. Profiles live in the shared `accounts.json`,
 * password hashes (PBKDF2-SHA256, random salt) in the secure store — passwords themselves are never stored.
 */
class LocalAuthService(
    private val storage: JsonFileStorage<List<Account>>,
    private val secrets: SecretStore,
    private val now: () -> Instant = { Clock.System.now() }
) : AuthService {
    @Serializable
    data class Account(val user: User, val createdAt: Instant)

    /** Same JSON as iOS: salt and hash as base64 strings. */
    @Serializable
    data class Credential(val salt: String, val hash: String, val rounds: Int)

    private val accounts: List<Account> get() = storage.load() ?: emptyList()

    override suspend fun login(email: String, password: String): User {
        val normalized = normalize(email)
        DemoAuth.login(normalized, password)?.let { return it }
        val account = accounts.firstOrNull { it.user.email == normalized }
        val credential = credential(normalized)
        if (account == null || credential == null || !verify(password, credential)) throw AuthException(AuthError.INVALID_CREDENTIALS)
        return account.user
    }

    override suspend fun register(form: RegistrationForm): RegistrationOutcome {
        val email = normalize(form.email)
        if (email in DemoAccounts.emails || accounts.any { it.user.email == email }) throw AuthException(AuthError.EMAIL_TAKEN)
        // A coach needs the players' data, which only a server can share between phones.
        if (form.resolvedRole == UserRole.STAFF) throw AuthException(AuthError.STAFF_NEEDS_SERVER)
        val faceit = form.faceitURL.trim()
        val code = form.teamCode.trim().uppercase()
        val user = User(
            id = Uuid.random(),
            nickname = form.nickname.trim(),
            email = email,
            role = form.resolvedRole,
            tariff = Tariff.FREE,
            // The invite code is checked by the server later; until then it is kept as the team label.
            team = if (form.isTeamPlayer) "Команда $code" else null,
            streakDays = 0,
            faceitURL = faceit.ifEmpty { null }
        )
        val salt = secureRandomBytes(16)
        val credential = Credential(Base64.encode(salt), Base64.encode(hash(form.password, salt, ROUNDS)), ROUNDS)
        secrets.write(credentialKey(email), Base64.encode(PulseJson.encodeToString(Credential.serializer(), credential).encodeToByteArray()))
        storage.save(accounts + Account(user, now()))
        return RegistrationOutcome.SignedIn(user)
    }

    /** Profile edits are kept with the account so the next login returns them. */
    override fun updateProfile(user: User) {
        if (user.isDemo) return
        storage.save(accounts.map { if (it.user.id == user.id) it.copy(user = user) else it })
    }

    /** «Удалить аккаунт»: profile and password hash are removed (the data folder is erased by the session). */
    override fun deleteAccount(user: User) {
        if (user.isDemo) return
        storage.save(accounts.filter { it.user.id != user.id })
        secrets.write(credentialKey(user.email), null)
    }

    private fun credential(email: String): Credential? {
        val raw = secrets.read(credentialKey(email)) ?: return null
        return runCatching { PulseJson.decodeFromString(Credential.serializer(), Base64.decode(raw).decodeToString()) }.getOrNull()
    }

    companion object {
        const val ROUNDS = 120_000

        fun normalize(email: String): String = email.trim().lowercase()

        private fun credentialKey(email: String) = "auth.$email"

        fun hash(password: String, salt: ByteArray, rounds: Int): ByteArray = pbkdf2Sha256(password, salt, rounds, 32)

        fun verify(password: String, credential: Credential): Boolean {
            val expected = Base64.decode(credential.hash)
            val candidate = hash(password, Base64.decode(credential.salt), credential.rounds)
            if (candidate.size != expected.size) return false
            // Constant-time comparison.
            var diff = 0
            for (i in candidate.indices) diff = diff or (candidate[i].toInt() xor expected[i].toInt())
            return diff == 0
        }
    }
}
