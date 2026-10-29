package com.pulse.core.model

import com.pulse.core.auth.AuthService
import com.pulse.core.auth.RegistrationOutcome
import com.pulse.core.auth.SessionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Same limits as the server (`nickname` 1-40, `team_name` 1-60), checked before anything is sent. */
class LengthLimitsTest {
    private fun form(nickname: String = "Nova", teamName: String = "") = RegistrationForm(
        nickname = nickname, email = "nova@team.gg", password = "secret123", confirmPassword = "secret123",
        teamName = teamName, accessType = if (teamName.isEmpty()) AccessType.SOLO else AccessType.TEAM,
        teamRole = TeamRole.MANAGER, acceptedTerms = true, acceptedPrivacy = true
    )

    @Test fun nicknameUpTo40Characters() {
        assertEquals(40, AuthValidator.NICKNAME_MAX_LENGTH)
        assertNull(AuthValidator.validateRegistration(form(nickname = "a".repeat(40))))
        // Surrounding spaces are trimmed, as on the server.
        assertNull(AuthValidator.validateRegistration(form(nickname = " " + "a".repeat(40) + " ")))
        assertEquals(AuthError.NICKNAME_TOO_LONG, AuthValidator.validateRegistration(form(nickname = "a".repeat(41))))
        assertEquals("Не больше 40 символов", AuthError.NICKNAME_TOO_LONG.message)
    }

    @Test fun teamNameUpTo60Characters() {
        assertEquals(60, AuthValidator.TEAM_NAME_MAX_LENGTH)
        assertNull(AuthValidator.validateRegistration(form(teamName = "t".repeat(60))))
        assertEquals(AuthError.TEAM_NAME_TOO_LONG, AuthValidator.validateRegistration(form(teamName = "t".repeat(61))))
        assertEquals("Не больше 60 символов", AuthError.TEAM_NAME_TOO_LONG.message)
    }

    @Test fun profileEditKeepsTheNicknameLimit() {
        val user = User(kotlin.uuid.Uuid.random(), "Nova", "nova@team.gg", UserRole.SOLO_PLAYER, Tariff.FREE, streakDays = 0)
        val session = SessionStore(SignedInAs(user), null)
        kotlinx.coroutines.test.runTest { session.login("nova@team.gg", "secret123") }
        assertEquals("Не больше 40 символов", session.updateProfile("a".repeat(41), ""))
        assertEquals("Nova", session.user.value?.nickname)
        assertNull(session.updateProfile("a".repeat(40), ""))
    }

    private class SignedInAs(private val user: User) : AuthService {
        override suspend fun login(email: String, password: String): User = user
        override suspend fun register(form: RegistrationForm): RegistrationOutcome = RegistrationOutcome.SignedIn(user)
    }
}
