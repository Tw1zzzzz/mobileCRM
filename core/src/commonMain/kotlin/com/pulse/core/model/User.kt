@file:UseSerializers(UuidSerializer::class)

package com.pulse.core.model

import com.pulse.core.util.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.uuid.Uuid

@Serializable
enum class UserRole(val title: String, /** Short profile badge. */ val badgeTitle: String, val badgeIcon: String) {
    @SerialName("soloPlayer") SOLO_PLAYER("Solo игрок", "Solo", "person"),
    @SerialName("teamPlayer") TEAM_PLAYER("Командный игрок", "Игрок", "gamecontroller"),
    @SerialName("staff") STAFF("Staff", "Стафф", "person.badge.shield.checkmark");

    val isStaff: Boolean get() = this == STAFF
}

@Serializable
enum class Tariff(val title: String) {
    @SerialName("free") FREE("FREE"),
    @SerialName("pro") PRO("PRO");

    val hasSubscription: Boolean get() = this == PRO
}

@Serializable
data class User(
    val id: Uuid,
    val nickname: String,
    val email: String,
    val role: UserRole,
    val tariff: Tariff,
    val team: String? = null,
    val streakDays: Int,
    val faceitURL: String? = null
) {
    val initials: String get() = nickname.take(2).uppercase()

    /** Demo accounts («Посмотреть демо») get seeded data in their own folder. */
    val isDemo: Boolean get() = email in DemoAccounts.emails
}

object DemoAccounts {
    val emails = setOf("player@pulse.app", "solo@pulse.app", "staff@pulse.app")
}

enum class AccessType(val title: String) { SOLO("Соло"), TEAM("Командный доступ") }

enum class TeamRole(val title: String) { PLAYER("Я игрок"), MANAGER("Я стафф") }

data class RegistrationForm(
    val nickname: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val faceitURL: String = "",
    val teamCode: String = "",
    val teamName: String = "",
    val accessType: AccessType = AccessType.SOLO,
    val teamRole: TeamRole = TeamRole.PLAYER,
    val acceptedTerms: Boolean = false,
    val acceptedPrivacy: Boolean = false
) {
    val resolvedRole: UserRole
        get() = when (accessType) {
            AccessType.SOLO -> UserRole.SOLO_PLAYER
            AccessType.TEAM -> if (teamRole == TeamRole.MANAGER) UserRole.STAFF else UserRole.TEAM_PLAYER
        }

    val isTeamPlayer: Boolean get() = accessType == AccessType.TEAM && teamRole == TeamRole.PLAYER
    val isTeamStaff: Boolean get() = accessType == AccessType.TEAM && teamRole == TeamRole.MANAGER
}

/** Errors and user-facing messages exactly as defined in the spec (раздел «Авторизация»). */
enum class AuthError(val message: String) {
    EMPTY_FIELDS("Заполните обязательные поля"),
    INVALID_CREDENTIALS("Неверный логин или пароль"),
    SERVER("Не удалось выполнить вход. Повторите попытку позже"),
    NO_CONNECTION("Не удалось установить соединение с сервером"),
    INVALID_EMAIL("Укажите корректный адрес электронной почты"),
    EMAIL_TAKEN("Пользователь с таким адресом электронной почты уже существует"),
    PASSWORD_MISMATCH("Введенные пароли не совпадают"),
    WEAK_PASSWORD("Пароль должен содержать не менее 8 символов, включая буквы и цифры"),
    TERMS_NOT_ACCEPTED("Для продолжения необходимо принять условия использования"),
    PRIVACY_NOT_ACCEPTED("Для продолжения необходимо принять политику конфиденциальности"),
    REGISTRATION_SERVER("Не удалось выполнить регистрацию. Повторите попытку позже"),
    INVALID_FACEIT("Укажите корректную ссылку на профиль Faceit"),
    TEAM_NOT_FOUND("Команда с таким кодом не найдена"),
    STAFF_NEEDS_SERVER("Доступ стаффа откроется вместе с сервером команды. Сейчас его можно посмотреть в демо.")
}

class AuthException(val error: AuthError) : Exception(error.message)

object AuthValidator {
    private val emailPattern = Regex("^[A-Z0-9a-z._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun validateLogin(email: String, password: String): AuthError? =
        if (email.isBlank() || password.isEmpty()) AuthError.EMPTY_FIELDS else null

    fun validateRegistration(form: RegistrationForm): AuthError? {
        if (form.nickname.isBlank() || form.email.isBlank() || form.password.isEmpty() || form.confirmPassword.isEmpty()) {
            return AuthError.EMPTY_FIELDS
        }
        if (form.isTeamPlayer && form.teamCode.isBlank()) return AuthError.EMPTY_FIELDS
        if (form.isTeamStaff && form.teamName.isBlank()) return AuthError.EMPTY_FIELDS
        if (!isValidEmail(form.email.trim())) return AuthError.INVALID_EMAIL
        if (!isStrongPassword(form.password)) return AuthError.WEAK_PASSWORD
        if (form.password != form.confirmPassword) return AuthError.PASSWORD_MISMATCH
        val faceit = form.faceitURL.trim()
        if (faceit.isNotEmpty() && !isValidFaceitURL(faceit)) return AuthError.INVALID_FACEIT
        if (!form.acceptedTerms) return AuthError.TERMS_NOT_ACCEPTED
        if (!form.acceptedPrivacy) return AuthError.PRIVACY_NOT_ACCEPTED
        return null
    }

    fun isValidEmail(email: String): Boolean = emailPattern.matches(email)

    /** Profile link on faceit.com (http/https), e.g. https://www.faceit.com/ru/players/nick. */
    fun isValidFaceitURL(text: String): Boolean {
        val host = WebLinks.host(text) ?: return false
        return host == "faceit.com" || host.endsWith(".faceit.com")
    }

    /** At least 8 characters with both letters and digits. */
    fun isStrongPassword(password: String): Boolean =
        password.length >= 8 && password.any { it.isLetter() } && password.any { it.isDigit() }
}
