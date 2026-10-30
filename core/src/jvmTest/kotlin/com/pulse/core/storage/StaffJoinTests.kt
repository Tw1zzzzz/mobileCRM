package com.pulse.core.storage

import com.pulse.core.api.ApiClient
import com.pulse.core.api.RemoteAuthService
import com.pulse.core.api.TokenStore
import com.pulse.core.auth.SessionStore
import com.pulse.core.model.AccessType
import com.pulse.core.model.AuthError
import com.pulse.core.model.AuthFieldId
import com.pulse.core.model.AuthValidator
import com.pulse.core.model.RegistrationForm
import com.pulse.core.model.TeamRole
import com.pulse.core.model.UserRole
import com.pulse.core.platform.MemorySecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A helper joins an existing team as staff with the staff code (`POST /auth/register`, role `staff`). */
class StaffJoinTests {
    private val requests = ConcurrentLinkedQueue<HttpRequestData>()
    private val background = CoroutineScope(Dispatchers.Unconfined)

    private fun ok(data: String, status: Int = 200) = status to """{"success":true,"data":$data,"error":null}"""
    private fun fail(status: Int, code: String) = status to """{"success":false,"data":null,"error":{"code":"$code","message":"x"}}"""
    private val pending = ok("""{"pending_verification":true,"email":"coach@team.gg"}""", 201)

    private fun session(handler: suspend (HttpRequestData) -> Pair<Int, String>): SessionStore {
        requests.clear()
        val engine = MockEngine { request ->
            requests += request
            val (status, body) = handler(request)
            respond(body, HttpStatusCode.fromValue(status))
        }
        val client = ApiClient(HttpClient(engine), "https://api.test/v1", TokenStore(MemorySecretStore()))
        return SessionStore(RemoteAuthService(client, background), null, requireBetaTerms = true)
    }

    private fun staffForm(joins: Boolean, staffCode: String = "", teamName: String = "") = RegistrationForm(
        nickname = "Coach", email = "coach@team.gg", password = "secret123", confirmPassword = "secret123",
        accessType = AccessType.TEAM, teamRole = TeamRole.MANAGER, staffJoinsTeam = joins, staffCode = staffCode, teamName = teamName,
        acceptedTerms = true, acceptedPrivacy = true, acceptedBetaTerms = true
    )

    private fun body() = (requests.single().body as TextContent).text

    @Test fun staffRegistrationSendsStaffCode() = runTest {
        val session = session { pending }
        // A team name typed before switching to «Вступить по коду стаффа» must not travel with the code.
        session.register(staffForm(joins = true, staffCode = " k7m2xp ", teamName = "Old name"))
        val body = body()
        assertTrue(""""staff_code":"K7M2XP"""" in body, body)
        assertFalse("team_name" in body, body)
        assertFalse("team_code" in body, body)
        assertTrue(""""role":"staff"""" in body, body)
        assertEquals("coach@team.gg", session.pendingVerification.value)
    }

    @Test fun creatingATeamStillSendsTeamNameOnly() = runTest {
        val session = session { pending }
        session.register(staffForm(joins = false, teamName = " Pulse Academy ", staffCode = "K7M2XP"))
        val body = body()
        assertTrue(""""team_name":"Pulse Academy"""" in body, body)
        assertFalse("staff_code" in body, body)
    }

    @Test fun staffJoinUnknownCodeShowsTeamNotFound() = runTest {
        val session = session { fail(404, "team_not_found") }
        session.register(staffForm(joins = true, staffCode = "ZZZZZZ"))
        assertEquals(AuthError.TEAM_NOT_FOUND.message, session.errorMessage.value)
        assertEquals("Команда с таким кодом не найдена", session.errorMessage.value)
        assertNull(session.pendingVerification.value)
    }

    @Test fun staffJoinNeedsACodeNotATeamName() {
        assertEquals(AuthError.EMPTY_FIELDS, AuthValidator.validateRegistration(staffForm(joins = true, teamName = "Pulse Academy")))
        assertNull(AuthValidator.validateRegistration(staffForm(joins = true, staffCode = "K7M2XP")))
        // Creating a team still needs its name, and not a code.
        assertEquals(AuthError.EMPTY_FIELDS, AuthValidator.validateRegistration(staffForm(joins = false, staffCode = "K7M2XP")))
        // A leftover long team name does not block joining.
        assertNull(AuthValidator.validateRegistration(staffForm(joins = true, staffCode = "K7M2XP", teamName = "t".repeat(61))))
    }

    @Test fun staffFieldsFollowTheSegment() {
        assertTrue(AuthFieldId.STAFF_CODE in staffForm(joins = true).fields())
        assertFalse(AuthFieldId.TEAM_NAME in staffForm(joins = true).fields())
        assertTrue(AuthFieldId.TEAM_NAME in staffForm(joins = false).fields())
        assertFalse(AuthFieldId.STAFF_CODE in staffForm(joins = false).fields())
    }

    @Test fun sessionUserCarriesBothTeamCodesForStaff() = runTest {
        val staffUser = """{"id":"6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b","nickname":"Coach","email":"coach@team.gg","role":"staff",""" +
            """"tariff":"free","team":"Pulse Academy","team_id":"0c1d2e3f-4a5b-4e5f-8a9b-6f1b6a2e1c2d","invite_code":"ABCDEF","staff_code":"K7M2XP","streak_days":0,"faceit_url":null}"""
        val session = session { request ->
            when (request.url.encodedPath) {
                "/v1/auth/register" -> pending
                else -> ok("""{"user":$staffUser,"access_token":"A1","refresh_token":"R1"}""")
            }
        }
        session.register(staffForm(joins = true, staffCode = "K7M2XP"))
        session.verifyEmail("123456")
        val user = session.user.value!!
        assertEquals(UserRole.STAFF, user.role)
        assertEquals("Pulse Academy", user.team)
        assertEquals("ABCDEF", user.inviteCode)
        assertEquals("K7M2XP", user.staffCode)
    }

    @Test fun playerAndSoloUsersHaveNoTeamCodes() = runTest {
        val playerUser = """{"id":"6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b","nickname":"Nova","email":"coach@team.gg","role":"teamPlayer",""" +
            """"tariff":"free","team":"Pulse","team_id":"0c1d2e3f-4a5b-4e5f-8a9b-6f1b6a2e1c2d","invite_code":null,"staff_code":null,"streak_days":0,"faceit_url":null}"""
        val session = session { request ->
            if (request.url.encodedPath == "/v1/auth/register") pending else ok("""{"user":$playerUser,"access_token":"A1","refresh_token":"R1"}""")
        }
        session.register(staffForm(joins = true, staffCode = "K7M2XP"))
        session.verifyEmail("123456")
        assertNull(session.user.value?.inviteCode)
        assertNull(session.user.value?.staffCode)
    }
}
