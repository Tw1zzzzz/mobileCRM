package com.pulse.core.auth

import com.pulse.core.api.ApiException
import com.pulse.core.model.AuthError
import com.pulse.core.model.AuthException
import com.pulse.core.model.AuthValidator
import com.pulse.core.model.RegistrationForm
import com.pulse.core.model.User
import com.pulse.core.model.UserRole
import com.pulse.core.storage.DataScope
import com.pulse.core.storage.JsonFileStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Signed-in user and the sign-in / sign-up flow (Kotlin twin of iOS `SessionStore`). */
class SessionStore(
    private val authService: AuthService,
    private val storage: JsonFileStorage<User>?,
    private val scope: DataScope? = null,
    /** Sign-up on the Pulse server also asks for the beta-testing terms. */
    val requireBetaTerms: Boolean = false
) {
    private val _user = MutableStateFlow(storage?.load())
    val user: StateFlow<User?> = _user.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _pendingVerification = MutableStateFlow<String?>(null)
    /** Email waiting for its 6-digit code (after sign-up or a sign-in with an unverified address). */
    val pendingVerification: StateFlow<String?> = _pendingVerification.asStateFlow()

    private val _pendingReset = MutableStateFlow<String?>(null)
    /** Email the password-reset code was requested for. */
    val pendingReset: StateFlow<String?> = _pendingReset.asStateFlow()

    /** Login, logout, demo switch, account deletion — the app reloads every store for the new account. */
    var onUserChange: ((User?) -> Unit)? = null

    /** Runs before «Выйти», for at most [LOGOUT_SYNC_TIMEOUT]: the core sends the account's unsent changes. */
    var beforeLogout: (suspend () -> Unit)? = null

    val isStaff: Boolean get() = _user.value?.role?.isStaff ?: false
    val hasSubscription: Boolean get() = _user.value?.tariff?.hasSubscription ?: false

    fun clearError() {
        _errorMessage.value = null
    }

    suspend fun login(email: String, password: String) {
        _errorMessage.value = null
        AuthValidator.validateLogin(email, password)?.let { return show(it) }
        call { authService.login(email, password) }
            .onSuccess(::setUser)
            .onFailure { error ->
                // The server has already emailed a code: the screen switches to the code step without an error.
                if ((error as? AuthException)?.error == AuthError.EMAIL_NOT_VERIFIED) {
                    _pendingVerification.value = LocalAuthService.normalize(email)
                } else {
                    showFailure(error, AuthError.SERVER)
                }
            }
    }

    suspend fun register(form: RegistrationForm) {
        _errorMessage.value = null
        AuthValidator.validateRegistration(form, requireBetaTerms)?.let { return show(it) }
        call { authService.register(form) }
            .onSuccess { outcome ->
                when (outcome) {
                    is RegistrationOutcome.SignedIn -> setUser(outcome.user)
                    is RegistrationOutcome.VerificationSent -> _pendingVerification.value = outcome.email
                }
            }
            .onFailure { showFailure(it, AuthError.REGISTRATION_SERVER) }
    }

    /** Confirms [pendingVerification] with the emailed code; a code that is not 6 digits never reaches the server. */
    suspend fun verifyEmail(code: String) {
        _errorMessage.value = null
        val email = _pendingVerification.value ?: return
        val trimmed = code.trim()
        if (!AuthValidator.isValidCode(trimmed)) return show(AuthError.INVALID_CODE)
        call { authService.verifyEmail(email, trimmed) }
            .onSuccess(::setUser)
            .onFailure { showFailure(it, AuthError.SERVER) }
    }

    suspend fun resendCode() {
        _errorMessage.value = null
        val email = _pendingVerification.value ?: return
        call { authService.resendCode(email) }.onFailure { showFailure(it, AuthError.SERVER) }
    }

    /** «Изменить почту»: back to the sign-up / sign-in form. */
    fun cancelVerification() {
        _pendingVerification.value = null
        _errorMessage.value = null
    }

    /** «Получить код». The server never says whether the address has an account, so the code step always opens. */
    suspend fun requestPasswordReset(email: String) {
        _errorMessage.value = null
        val normalized = LocalAuthService.normalize(email)
        if (normalized.isEmpty()) return show(AuthError.EMPTY_FIELDS)
        if (!AuthValidator.isValidEmail(normalized)) return show(AuthError.INVALID_EMAIL)
        call { authService.requestPasswordReset(normalized) }
            .onSuccess { _pendingReset.value = normalized }
            .onFailure { showFailure(it, AuthError.SERVER) }
    }

    /** «Сменить пароль»: checks the fields locally, then signs in with the new password. */
    suspend fun confirmPasswordReset(code: String, password: String, confirm: String) {
        _errorMessage.value = null
        val email = _pendingReset.value ?: return
        val trimmed = code.trim()
        val invalid = when {
            trimmed.isEmpty() || password.isEmpty() || confirm.isEmpty() -> AuthError.EMPTY_FIELDS
            !AuthValidator.isValidCode(trimmed) -> AuthError.INVALID_CODE
            !AuthValidator.isStrongPassword(password) -> AuthError.WEAK_PASSWORD
            password != confirm -> AuthError.PASSWORD_MISMATCH
            else -> null
        }
        invalid?.let { return show(it) }
        call { authService.confirmPasswordReset(email, trimmed, password) }
            .onSuccess(::setUser)
            .onFailure { showFailure(it, AuthError.SERVER) }
    }

    /** «Назад ко входу». */
    fun cancelReset() {
        _pendingReset.value = null
        _errorMessage.value = null
    }

    /** Profile editing. Returns an error message or null on success. */
    fun updateProfile(nickname: String, faceitURL: String): String? {
        val user = _user.value ?: return null
        val name = nickname.trim()
        val faceit = faceitURL.trim()
        if (name.isEmpty()) return AuthError.EMPTY_FIELDS.message
        if (faceit.isNotEmpty() && !AuthValidator.isValidFaceitURL(faceit)) return AuthError.INVALID_FACEIT.message
        val updated = user.copy(nickname = name, faceitURL = faceit.ifEmpty { null })
        _user.value = updated
        storage?.save(updated)
        authService.updateProfile(updated)
        return null
    }

    /** Demo mode: instant switch between seeded accounts without typing credentials. */
    suspend fun switchToDemo(role: UserRole) {
        val account = DemoAuth.accounts.firstOrNull { it.user.role == role } ?: return
        login(account.email, account.password)
    }

    /**
     * «Выйти». Unsent changes get [LOGOUT_SYNC_TIMEOUT] to reach the server; the account signs out regardless (also
     * when the caller is cancelled meanwhile). The account's folder stays on the phone.
     */
    suspend fun logout() {
        try {
            beforeLogout?.let { push -> withTimeoutOrNull(LOGOUT_SYNC_TIMEOUT) { push() } }
        } finally {
            signOutNow()
        }
    }

    /** Signs out without sending anything first (a stored session that has no server tokens). */
    internal fun signOutNow() {
        authService.logout()
        endSession()
    }

    /** Store requirement for apps with sign-up: the account and all its data on this phone are removed. */
    fun deleteAccount() {
        val user = _user.value ?: return
        if (user.isDemo) return
        // The server forgets the account (and its sessions) itself; a separate logout would race the deletion.
        authService.deleteAccount(user)
        scope?.erase(DataScope.id(user))
        endSession()
    }

    private fun endSession() {
        _user.value = null
        storage?.clear()
        onUserChange?.invoke(null)
    }

    private suspend fun <T> call(action: suspend () -> T): Result<T> {
        _isLoading.value = true
        return try {
            Result.success(action())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            _isLoading.value = false
        }
    }

    private fun show(error: AuthError) {
        _errorMessage.value = error.message
    }

    private fun showFailure(error: Throwable, fallback: AuthError) {
        _errorMessage.value = when (error) {
            is AuthException -> error.error.message
            is ApiException -> if (error.isOffline) AuthError.NO_CONNECTION.message else fallback.message
            else -> fallback.message
        }
    }

    private fun setUser(user: User) {
        val previous = _user.value
        val changed = previous?.id != user.id || previous.email != user.email
        _pendingVerification.value = null
        _pendingReset.value = null
        _user.value = user
        storage?.save(user)
        if (changed) onUserChange?.invoke(user)
    }

    companion object {
        val LOGOUT_SYNC_TIMEOUT = 5.seconds
    }
}
