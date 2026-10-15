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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Signed-in user and the sign-in / sign-up flow (Kotlin twin of iOS `SessionStore`). */
class SessionStore(
    private val authService: AuthService,
    private val storage: JsonFileStorage<User>?,
    private val scope: DataScope? = null
) {
    private val _user = MutableStateFlow(storage?.load())
    val user: StateFlow<User?> = _user.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Login, logout, demo switch, account deletion — the app reloads every store for the new account. */
    var onUserChange: ((User?) -> Unit)? = null

    val isStaff: Boolean get() = _user.value?.role?.isStaff ?: false
    val hasSubscription: Boolean get() = _user.value?.tariff?.hasSubscription ?: false

    fun clearError() {
        _errorMessage.value = null
    }

    suspend fun login(email: String, password: String) {
        _errorMessage.value = null
        AuthValidator.validateLogin(email, password)?.let {
            _errorMessage.value = it.message
            return
        }
        run(AuthError.SERVER) { authService.login(email, password) }
    }

    suspend fun register(form: RegistrationForm) {
        _errorMessage.value = null
        AuthValidator.validateRegistration(form)?.let {
            _errorMessage.value = it.message
            return
        }
        run(AuthError.REGISTRATION_SERVER) { authService.register(form) }
    }

    private suspend fun run(fallback: AuthError, action: suspend () -> User) {
        _isLoading.value = true
        try {
            setUser(action())
        } catch (error: AuthException) {
            _errorMessage.value = error.error.message
        } catch (error: ApiException) {
            _errorMessage.value = if (error.isOffline) AuthError.NO_CONNECTION.message else fallback.message
        } catch (error: Exception) {
            _errorMessage.value = fallback.message
        } finally {
            _isLoading.value = false
        }
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

    fun logout() {
        _user.value = null
        storage?.clear()
        onUserChange?.invoke(null)
    }

    /** Store requirement for apps with sign-up: the account and all its data on this phone are removed. */
    fun deleteAccount() {
        val user = _user.value ?: return
        if (user.isDemo) return
        authService.deleteAccount(user)
        scope?.erase(DataScope.id(user))
        logout()
    }

    private fun setUser(user: User) {
        val previous = _user.value
        val changed = previous?.id != user.id || previous.email != user.email
        _user.value = user
        storage?.save(user)
        if (changed) onUserChange?.invoke(user)
    }
}
