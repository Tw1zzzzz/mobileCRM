package com.pulse.core.model

import com.pulse.core.util.CorrelationStrength
import com.pulse.core.util.Formatters
import com.pulse.core.util.PulseJson
import com.pulse.core.util.StatisticsMath
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Ported from PulseTests/FaceitTests.swift, MathAndFormattingTests.swift and ValidationTests.swift. */
class FaceitParsingTests {
    @Test fun nicknameFromLinks() {
        assertEquals("s1mple", FaceitMapper.nickname("https://www.faceit.com/ru/players/s1mple"))
        assertEquals("Nova_99", FaceitMapper.nickname("https://faceit.com/en/players/Nova_99/stats/cs2"))
        assertEquals("donk", FaceitMapper.nickname("  donk "))
        assertNull(FaceitMapper.nickname("https://example.com/players/x"))
        assertNull(FaceitMapper.nickname(""))
    }

    @Test fun decodesPlayer() {
        val json = """{"player_id":"abc-1","nickname":"Nova","avatar":"https://a/b.png","country":"ru","faceit_url":"https://www.faceit.com/{lang}/players/Nova",
            "games":{"cs2":{"faceit_elo":2840,"skill_level":10,"region":"EU","game_player_name":"Nova"}}}"""
        val player = PulseJson.decodeFromString(FaceitPlayer.serializer(), json)
        assertEquals("abc-1", player.playerId)
        assertEquals(2840, player.cs2?.faceitElo)
        assertEquals(10, player.cs2?.skillLevel)
        assertEquals("EU", player.cs2?.region)
    }

    @Test fun lifetimeAndMapsFromStringValues() {
        val json = """{"player_id":"abc-1","game_id":"cs2",
         "lifetime":{"Matches":"412","Win Rate %":"54","Average K/D Ratio":"1.18","Average Headshots %":"47",
                     "Longest Win Streak":"9","Current Win Streak":"2","Recent Results":["1","0","1","1","0"],
                     "ADR":"84.3","Entry Success Rate":"0.52","Utility Damage per Round":"7.4","1v1 Win Rate":"0.61"},
         "segments":[{"label":"Mirage","mode":"5v5","type":"Map","stats":{"Matches":"120","Win Rate %":"58","Average K/D Ratio":"1.25"}},
                     {"label":"Anubis","mode":"5v5","type":"Map","stats":{"Matches":"0","Win Rate %":"0","Average K/D Ratio":"0"}},
                     {"label":"Inferno","mode":"5v5","type":"Map","stats":{"Matches":"80","Win Rate %":"45","Average K/D Ratio":"1.02"}}]}"""
        val stats = PulseJson.decodeFromString(FaceitPlayerStats.serializer(), json)
        val lifetime = FaceitMapper.lifetime(stats.lifetime)
        assertEquals(412, lifetime.matches)
        assertTrue(abs(lifetime.kd - 1.18) < 0.0001)
        assertEquals(listOf(true, false, true, true, false), lifetime.recentResults)
        assertEquals(52.0, lifetime.entrySuccessRate)
        assertEquals(61.0, lifetime.clutch1v1WinRate)
        assertEquals(listOf("Mirage", "Inferno"), FaceitMapper.maps(stats.segments ?: emptyList()).map { it.map })
    }

    @Test fun matchStatsBecomeGameMatches() {
        val json = """{"items":[{"stats":{"Match Id":"1-6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b","Map":"de_inferno","Result":"1","Score":"13 / 9",
           "Kills":"24","Deaths":"15","Assists":"5","ADR":"96.4","Headshots %":"50","MVPs":"4","First Kills":"3",
           "Entry Count":"5","Entry Wins":"3","1v1Wins":"1","1v2Wins":"1","Double Kills":"3","Triple Kills":"1",
           "Utility Damage":"180","Enemies Flashed":"7","Sniper Kills":"2","Rounds":"22","Match Finished At":1760000000000}},
          {"stats":{"Map":"de_nuke","Result":"0"}}]}"""
        val list = PulseJson.decodeFromString(FaceitMatchStatsList.serializer(), json)
        val matches = list.items.mapNotNull { FaceitMapper.match(it.stats) }
        assertEquals(1, matches.size) // the item without kills/deaths is skipped
        val match = matches.first()
        assertEquals(CSMap.INFERNO, match.map)
        assertTrue(match.won)
        assertTrue(match.roundsWon == 13 && match.roundsLost == 9)
        assertTrue(match.kills == 24 && match.deaths == 15)
        assertEquals(Instant.fromEpochSeconds(1_760_000_000), match.date)
        assertEquals(2, match.details?.clutchesWon)
        assertEquals(4, match.details?.multiKills)
        assertEquals(22, match.details?.rounds)
        assertEquals("6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b", match.id.toString())
    }

    @Test fun lossTakesTheLowerScore() {
        val stats: FaceitStats = mapOf(
            "Result" to JsonPrimitive("0"), "Score" to JsonPrimitive("13 / 7"),
            "Kills" to JsonPrimitive("10"), "Deaths" to JsonPrimitive("18")
        )
        val match = assertNotNull(FaceitMapper.match(stats))
        assertFalse(match.won)
        assertTrue(match.roundsWon == 7 && match.roundsLost == 13)
    }
}

class StatisticsAndScoringTests {
    @Test fun pearsonPerfectCorrelations() {
        val xs = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertTrue(abs(StatisticsMath.pearson(xs, xs.map { it * 2 + 1 })!! - 1) < 1e-9)
        assertTrue(abs(StatisticsMath.pearson(xs, xs.map { -it })!! + 1) < 1e-9)
    }

    @Test fun meanAndPercentChange() {
        assertEquals(4.0, StatisticsMath.mean(listOf(2.0, 4.0, 6.0)))
        assertNull(StatisticsMath.percentChange(0.0, 10.0))
        assertEquals(-15.0, StatisticsMath.percentChange(200.0, 170.0))
    }

    @Test fun progressIsClamped() {
        assertEquals(1.0, StatisticsMath.clampedProgress(150.0, 100.0))
        assertEquals(0.0, StatisticsMath.clampedProgress(-5.0, 100.0))
        assertEquals(0.0, StatisticsMath.clampedProgress(5.0, 0.0))
        assertEquals(0.8, StatisticsMath.clampedProgress(48.0, 60.0))
    }

    @Test fun correlationStrengthBuckets() {
        assertEquals(CorrelationStrength.STRONG, CorrelationStrength.of(0.75))
        assertEquals(CorrelationStrength.MODERATE, CorrelationStrength.of(-0.4))
        assertEquals(CorrelationStrength.WEAK, CorrelationStrength.of(0.1))
    }

    @Test fun cognitiveScores() {
        assertEquals(100, CognitiveScoring.reactionScore(200.0))
        assertEquals(0, CognitiveScoring.reactionScore(500.0))
        assertEquals(0, CognitiveScoring.reactionScore(900.0))
        assertEquals(50, CognitiveScoring.reactionScore(350.0))
        assertEquals(0, CognitiveScoring.memoryScore(3))
        assertEquals(100, CognitiveScoring.memoryScore(9))
        assertEquals(75, CognitiveScoring.accuracyScore(15, 20))
        assertEquals(0, CognitiveScoring.accuracyScore(1, 0))
    }

    @Test fun durationsAndNumbers() {
        assertEquals("24 мин", Formatters.duration(24))
        assertEquals(Formatters.DurationParts(7, 42), Formatters.durationParts(462))
        assertEquals("01:02:05", Formatters.timer(3725.0))
        assertEquals("74,2", Formatters.decimal(74.2))
        assertEquals("+0,1", Formatters.signedDecimal(0.1))
        assertEquals("0", Formatters.liters(0))
    }
}

class ValidatorTests {
    private fun validForm() = RegistrationForm(
        nickname = "Nova", email = "nova@team.gg", password = "secret123", confirmPassword = "secret123",
        acceptedTerms = true, acceptedPrivacy = true
    )

    @Test fun loginRequiresBothFields() {
        assertEquals(AuthError.EMPTY_FIELDS, AuthValidator.validateLogin("", "x"))
        assertEquals(AuthError.EMPTY_FIELDS, AuthValidator.validateLogin("a@b.cc", ""))
        assertEquals(AuthError.EMPTY_FIELDS, AuthValidator.validateLogin("   ", "x"))
        assertNull(AuthValidator.validateLogin("a@b.cc", "x"))
    }

    @Test fun registrationErrorsMatchSpec() {
        assertNull(AuthValidator.validateRegistration(validForm()))
        assertEquals(AuthError.EMPTY_FIELDS, AuthValidator.validateRegistration(validForm().copy(nickname = " ")))
        assertEquals(AuthError.INVALID_EMAIL, AuthValidator.validateRegistration(validForm().copy(email = "nova@")))
        assertEquals(AuthError.WEAK_PASSWORD, AuthValidator.validateRegistration(validForm().copy(password = "short1", confirmPassword = "short1")))
        assertEquals(AuthError.PASSWORD_MISMATCH, AuthValidator.validateRegistration(validForm().copy(confirmPassword = "secret124")))
        assertEquals(AuthError.TERMS_NOT_ACCEPTED, AuthValidator.validateRegistration(validForm().copy(acceptedTerms = false)))
        assertEquals(AuthError.PRIVACY_NOT_ACCEPTED, AuthValidator.validateRegistration(validForm().copy(acceptedPrivacy = false)))
    }

    @Test fun passwordStrength() {
        mapOf("password" to false, "12345678" to false, "abc123" to false, "abcd1234" to true, "Пароль2026" to true)
            .forEach { (password, expected) -> assertEquals(expected, AuthValidator.isStrongPassword(password), password) }
    }

    @Test fun teamFieldsAreRequiredPerRole() {
        val player = validForm().copy(accessType = AccessType.TEAM, teamRole = TeamRole.PLAYER)
        assertEquals(AuthError.EMPTY_FIELDS, AuthValidator.validateRegistration(player))
        assertNull(AuthValidator.validateRegistration(player.copy(teamCode = "PULSE")))
        val staff = validForm().copy(accessType = AccessType.TEAM, teamRole = TeamRole.MANAGER)
        assertEquals(AuthError.EMPTY_FIELDS, AuthValidator.validateRegistration(staff))
        assertNull(AuthValidator.validateRegistration(staff.copy(teamName = "Pulse Academy")))
    }

    @Test fun faceitLinks() {
        mapOf(
            "https://www.faceit.com/ru/players/nova" to true, "https://faceit.com/en/players/x" to true,
            "https://evil.com/faceit.com" to false, "faceit.com/players/x" to false, "ftp://faceit.com/x" to false
        ).forEach { (link, expected) ->
            assertEquals(expected, AuthValidator.isValidFaceitURL(link), link)
            assertEquals(expected, AuthValidator.validateRegistration(validForm().copy(faceitURL = link)) == null, link)
        }
    }

    @Test fun registrationRoleResolution() {
        assertEquals(UserRole.SOLO_PLAYER, validForm().resolvedRole)
        assertEquals(UserRole.TEAM_PLAYER, validForm().copy(accessType = AccessType.TEAM).resolvedRole)
        assertEquals(UserRole.STAFF, validForm().copy(accessType = AccessType.TEAM, teamRole = TeamRole.MANAGER).resolvedRole)
    }

    private val start = Instant.fromEpochSeconds(1_800_000_000)
    private fun plus(minutes: Int) = Instant.fromEpochSeconds(1_800_000_000L + minutes * 60)

    @Test fun eventValidation() {
        assertEquals(EventValidationError.EMPTY_REQUIRED_FIELDS, EventValidator.validate("  ", start, plus(60), emptyList()))
        assertEquals(EventValidationError.END_BEFORE_START, EventValidator.validate("Тренировка", start, plus(-5), emptyList()))
        assertEquals(EventValidationError.END_BEFORE_START, EventValidator.validate("Тренировка", start, start, emptyList()))
        assertEquals(EventValidationError.INVALID_LINK, EventValidator.validate("Матч", start, plus(60), listOf("not a link")))
        assertEquals(EventValidationError.INVALID_LINK, EventValidator.validate("Матч", start, plus(60), listOf("ftp://x.com")))
        assertNull(EventValidator.validate("Матч", start, plus(90), listOf("https://faceit.com/match/1")))
        assertEquals(listOf("https://a.com", "https://b.com", "https://c.com"),
            EventValidator.parseLinks("https://a.com, https://b.com\n\n https://c.com "))
    }

    @Test fun messagesMatchSpecWording() {
        assertEquals("Неверный логин или пароль", AuthError.INVALID_CREDENTIALS.message)
        assertEquals("Не удалось установить соединение с сервером", AuthError.NO_CONNECTION.message)
    }
}
