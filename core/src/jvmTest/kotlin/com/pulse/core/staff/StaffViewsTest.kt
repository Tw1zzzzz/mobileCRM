package com.pulse.core.staff

import com.pulse.core.feature.StaffMetrics
import com.pulse.core.model.BalanceCategory
import com.pulse.core.model.CSMap
import com.pulse.core.model.GameMatch
import com.pulse.core.model.LoadStatus
import com.pulse.core.model.MindsetTrait
import com.pulse.core.model.WeekRange
import com.pulse.core.util.PulseJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val TZ = "Europe/Moscow"
private const val TODAY = "2026-10-15"
private const val UUID_1 = "11111111-1111-4111-8111-111111111111"

/** Documents of one test player, built from small JSON texts. */
private class Doc(val id: String, val docs: Map<String, String> = emptyMap(), val lastActivity: Instant? = null)

private fun json(text: String): JsonElement = PulseJson.parseToJsonElement(text)

private fun snapshot(vararg players: Doc): TeamSnapshot = TeamSnapshot(
    teamName = "Team",
    inviteCode = "ABC",
    players = players.map { StaffPlayer(it.id, it.id, it.lastActivity) },
    documents = players.associate { p -> p.id to p.docs.mapValues { json(it.value) } },
    fetchedAt = Instant.parse("2026-10-15T12:00:00Z"),
)

/** A sleep session of [minutes] ending at [end] (an ISO instant). */
private fun session(end: String, minutes: Int): String {
    val finish = Instant.parse(end)
    return """{"id":"s-$end","start":"${finish - minutes.minutes}","end":"$finish"}"""
}

private fun sleepDoc(vararg sessions: String) = """{"sessions":[${sessions.joinToString(",")}]}"""

/** A night ending on [day] at 07:00 Moscow time. */
private fun night(day: String, minutes: Int) = session("${day}T07:00:00+03:00", minutes)

private fun mood(day: String, mood: Int, energy: Int) =
    """{"id":"m-$day-$mood","day":"${day}T00:00:00Z","slot":"morning","mood":$mood,"energy":$energy}"""

private fun wellbeing(vararg moods: String, balance: String = "") =
    """{"mood":[${moods.joinToString(",")}],"balance":[$balance]}"""

private fun cognitive(day: String, kind: String, score: Int, time: String = "09:00:00") =
    """{"id":"c-$day-$kind","kind":"$kind","date":"${day}T${time}Z","score":$score,"metric":"x"}"""

private fun cognitiveDoc(vararg results: String, games: String = "") =
    """{"results":[${results.joinToString(",")}],"gameResults":[$games]}"""

private fun meal(id: String, at: String, name: String, kcal: Int, photoRef: Boolean = false, source: String = "manual") =
    """{"id":"$id","date":"$at","name":"$name","kcal":$kcal,"protein":30,"fat":20,"carbs":10,"source":"$source"${if (photoRef) ""","photoRef":true""" else ""}}"""

private fun nutrition(vararg meals: String) = """{"meals":[${meals.joinToString(",")}],"water":{},"goals":{"kcal":2400}}"""

/** A day with the four inputs of known readiness. */
private fun fullDay(day: String, sleepMinutes: Int, mood: Int, energy: Int, cog: Int): Map<String, String> = mapOf(
    "sleep" to sleepDoc(night(day, sleepMinutes)),
    "wellbeing" to wellbeing(mood(day, mood, energy)),
    "cognitive" to cognitiveDoc(cognitive(day, "reaction", cog)),
)

private fun match(at: String, kills: Int, deaths: Int, adr: Double, won: Boolean) = GameMatch(
    id = Uuid.random(), date = Instant.parse(at), map = CSMap.entries.first(), won = won,
    roundsWon = if (won) 13 else 8, roundsLost = if (won) 8 else 13,
    kills = kills, deaths = deaths, assists = 2, adr = adr, eloDelta = 0,
)

class StaffViewsTest {
    // ------------------------------------------------------------------ roster

    @Test
    fun appReadinessMatchesFixture() {
        val fixture = loadFixture()
        val rosters = fixture.getValue("rosters").jsonObject
        var checked = 0
        for (case in fixture.getValue("teamOverview").jsonArray.map { it.jsonObject }) {
            val roster = rosters.getValue(case.getValue("roster").jsonPrimitive.content).jsonArray.map { it.jsonObject }
            val ids = case.getValue("ids").let { if (it is JsonNull) null else it.jsonArray.map { id -> id.jsonPrimitive.content } }
            val chosen = if (ids == null) roster else ids.map { id -> roster.first { it.getValue("id").jsonPrimitive.content == id } }
            val team = snapshotOf(chosen)
            val rows = StaffViews.roster(team, case.getValue("today").jsonPrimitive.content, case.getValue("tz").jsonPrimitive.content)
            val byId = rows.associateBy { it.id }
            for (expected in case.getValue("expected").jsonObject.getValue("playerReadiness").jsonArray.map { it.jsonObject }) {
                val id = expected.getValue("id").jsonPrimitive.content
                val label = "${case.getValue("name").jsonPrimitive.content}/$id"
                val value = expected.getValue("readiness").let { if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull?.toInt() }
                assertEquals(value, byId.getValue(id).readiness, label)
                assertEquals(expected.getValue("status").let { statusOf(it) }, byId.getValue(id).status, label)
                checked++
            }
        }
        assertTrue(checked >= 12, "only $checked player readings were compared")
    }

    @Test
    fun appDailyInputsMatchFixtureThroughRoster() {
        val cases = loadFixture().getValue("dailyInputs").jsonArray.map { it.jsonObject }
        for (case in cases) {
            val docs = case.getValue("docs").jsonObject
            val team = TeamSnapshot("T", null, listOf(StaffPlayer("p", "p")), mapOf("p" to docs), Instant.parse("2026-10-15T12:00:00Z"))
            val row = StaffViews.roster(team, case.getValue("day").jsonPrimitive.content, case.getValue("tz").jsonPrimitive.content).single()
            val expected = case.getValue("expected").jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            assertEquals(number(expected["sleepHours"]), row.sleepHours, "$name sleep")
            assertEquals(number(expected["mood"]), row.mood, "$name mood")
            assertEquals(number(expected["cog"]), row.cognitive, "$name cog")
        }
    }

    @Test
    fun rosterKeepsPlayersWithoutDataAndSortsThem() {
        val team = snapshot(
            Doc("empty"),
            Doc("low", fullDay(TODAY, 450, 2, 2, 20)),
            Doc("high", fullDay(TODAY, 480, 8, 8, 80), Instant.parse("2026-10-15T08:00:00Z")),
        )
        val rows = StaffViews.roster(team, TODAY, TZ)
        assertEquals(listOf("high", "low", "empty"), rows.map { it.id })
        assertEquals(94, rows[0].readiness)
        assertEquals(StaffMetrics.ReadinessStatus.READY, rows[0].status)
        assertEquals(8.0, rows[0].sleepHours)
        assertEquals(8.0, rows[0].mood)
        assertEquals(80.0, rows[0].cognitive)
        assertEquals(Instant.parse("2026-10-15T08:00:00Z"), rows[0].lastActivity)
        assertEquals(40, rows[1].readiness)
        assertEquals(StaffMetrics.ReadinessStatus.RISK, rows[1].status)
        assertNull(rows[2].lastActivity)
    }

    @Test
    fun rosterAndDocumentsMayDisagree() {
        // A player in the roster whose documents entry is missing altogether still gets a row.
        val team = snapshot(Doc("a")).copy(documents = emptyMap())
        val row = StaffViews.roster(team, TODAY, TZ).single()
        assertNull(row.readiness)
        assertNull(row.sleepHours)
    }

    @Test
    fun staffTimeZoneDecidesWhichDayASleepBelongsTo() {
        // Ends 22:30 UTC on the 14th = 01:30 on the 15th in Moscow.
        val team = snapshot(Doc("a", mapOf("sleep" to sleepDoc(session("2026-10-14T22:30:00Z", 480)))))
        assertEquals(8.0, StaffViews.roster(team, "2026-10-15", "Europe/Moscow").single().sleepHours)
        assertNull(StaffViews.roster(team, "2026-10-15", "UTC").single().sleepHours)
        assertEquals(8.0, StaffViews.roster(team, "2026-10-14", "UTC").single().sleepHours)
    }

    // ------------------------------------------------------------------ today

    @Test
    fun todayOverviewMeansOnlyThePlayersWithData() {
        val team = snapshot(
            Doc("low", fullDay(TODAY, 450, 2, 2, 20)),
            Doc("short", fullDay(TODAY, 360, 9, 9, 100)),
            Doc("fine", fullDay(TODAY, 480, 8, 8, 80)),
            Doc("empty"),
        )
        val view = StaffViews.todayOverview(team, TODAY, TZ)
        assertEquals(4, view.playersCount)
        assertEquals(75, view.teamReadiness) // (40 + 91 + 94) / 3
        assertEquals(StaffMetrics.ReadinessStatus.READY, view.teamStatus)
        assertEquals(StaffMetrics.teamOverview(team.players.map { StaffMetrics.PlayerDocuments(it.id, team.documents.getValue(it.id)) }, TODAY, TZ, "team"), view.overview)
    }

    @Test
    fun attentionReasons() {
        val team = snapshot(
            Doc("fine", fullDay(TODAY, 480, 8, 8, 80)),
            Doc("low", fullDay(TODAY, 450, 2, 2, 20)),
            Doc("short", fullDay(TODAY, 360, 9, 9, 100)),
            Doc("silent", mapOf("sleep" to sleepDoc(night("2026-10-10", 480)))),
            Doc("twoDays", mapOf("sleep" to sleepDoc(night("2026-10-13", 480)))),
            Doc("threeDays", mapOf("sleep" to sleepDoc(night("2026-10-12", 480)))),
            Doc("empty"),
        )
        val attention = StaffViews.todayOverview(team, TODAY, TZ).attention
        assertEquals(listOf("low", "short", "silent", "threeDays"), attention.map { it.playerId })
        val byId = attention.associateBy { it.playerId }
        assertEquals(listOf("Готовность 40"), byId.getValue("low").reasons.map { it.text })
        assertEquals(listOf(StaffViews.AttentionKind.LOW_READINESS), byId.getValue("low").reasons.map { it.kind })
        assertEquals(listOf("Мало сна: 6 ч 0 м"), byId.getValue("short").reasons.map { it.text })
        assertEquals(listOf("Нет данных 5 дн."), byId.getValue("silent").reasons.map { it.text })
        assertEquals(listOf("Нет данных 3 дн."), byId.getValue("threeDays").reasons.map { it.text })
        assertEquals(StaffViews.AttentionKind.NO_DATA, byId.getValue("silent").reasons.single().kind)
        assertEquals("Готовность 40", byId.getValue("low").text)
    }

    @Test
    fun freshTeamHasNoAttentionList() {
        val team = snapshot(Doc("a"), Doc("b", mapOf("sleep" to sleepDoc())))
        assertTrue(StaffViews.todayOverview(team, TODAY, TZ).attention.isEmpty())
        assertEquals(2, StaffViews.roster(team, TODAY, TZ).size) // they are still on the roster, with «нет данных»
    }

    @Test
    fun silentForThreeDaysIsListedButTwoIsNot() {
        val team = snapshot(
            Doc("two", mapOf("cognitive" to cognitiveDoc(cognitive("2026-10-13", "aim", 70)))),
            Doc("three", mapOf("nutrition" to nutrition(meal(UUID_1, "2026-10-12T10:00:00Z", "Суп", 300)))),
        )
        val attention = StaffViews.todayOverview(team, TODAY, TZ).attention
        assertEquals(listOf("three"), attention.map { it.playerId })
        assertEquals("Нет данных 3 дн.", attention.single().text)
    }

    @Test
    fun anyRecordKeepsAPlayerFromBeingSilent() {
        val records = mapOf(
            "workouts" to mapOf("fitness-state" to """{"workouts":[{"id":"w","kind":"cardio","date":"@T10:00:00Z","minutes":30}]}"""),
            "balance" to mapOf("wellbeing" to wellbeing(balance = """{"id":"b","day":"@T00:00:00Z","values":{}}""")),
            "mood" to mapOf("wellbeing" to wellbeing(mood("@", 5, 5))),
            "game test" to mapOf("cognitive" to cognitiveDoc(games = """{"id":"g","date":"@T10:00:00Z","phase":"afterTraining","focus":5,"fatigue":5,"readiness":5}""")),
            "screen time" to mapOf("screen-time" to """{"entries":[{"date":"@T00:00:00Z","minutes":120}]}"""),
            "meal without kcal" to mapOf("nutrition" to """{"meals":[{"id":"m","date":"@T10:00:00Z","name":"x"}]}"""),
        )
        fun team(day: String) = snapshot(*records.map { (name, docs) -> Doc(name, docs.mapValues { it.value.replace("@", day) }) }.toTypedArray())
        assertTrue(StaffViews.todayOverview(team("2026-10-14"), TODAY, TZ).attention.isEmpty())
        val silent = StaffViews.todayOverview(team("2026-10-10"), TODAY, TZ).attention
        assertEquals(records.keys, silent.map { it.playerId }.toSet())
        assertTrue(silent.all { it.text == "Нет данных 5 дн." })
    }

    @Test
    fun attentionOneReasonPerCauseCombined() {
        // 4h30 of sleep and nothing else: both a low reading and a short night.
        val team = snapshot(Doc("a", mapOf("sleep" to sleepDoc(night(TODAY, 270)))))
        val item = StaffViews.todayOverview(team, TODAY, TZ).attention.single()
        assertEquals(listOf(StaffViews.AttentionKind.LOW_READINESS, StaffViews.AttentionKind.SHORT_SLEEP), item.reasons.map { it.kind })
        assertEquals("Готовность 0; Мало сна: 4 ч 30 м", item.text)
    }

    @Test
    fun sleepThresholdIsSixPointSixHours() {
        val team = snapshot(
            Doc("exactly", mapOf("sleep" to sleepDoc(night(TODAY, 396)))), // 6.6 h
            Doc("under", mapOf("sleep" to sleepDoc(night(TODAY, 395)))), // 6.58 h
        )
        assertEquals(listOf("under"), StaffViews.todayOverview(team, TODAY, TZ).attention.map { it.playerId })
    }

    // ------------------------------------------------------------------ no data

    @Test
    fun noDataIsNull() {
        val team = snapshot(Doc("a"))
        val row = StaffViews.roster(team, TODAY, TZ).single()
        assertEquals(listOf(null, null, null, null, null), listOf(row.readiness, row.status, row.sleepHours, row.mood, row.cognitive))

        val today = StaffViews.todayOverview(team, TODAY, TZ)
        assertNull(today.teamReadiness)
        assertNull(today.teamStatus)

        val card = assertNotNull(StaffViews.playerCard(team, "a", TODAY, TZ, 7))
        assertNull(card.kpis.score.value)
        assertNull(card.kpis.state.value)
        assertNull(card.kpis.score.change)
        assertEquals(StaffViews.HighDays(0, 0), card.kpis.focusHigh)
        assertNull(card.sleep.averageHours)
        assertTrue(card.sleep.rows.isEmpty())
        assertTrue(card.tests.rows.isEmpty())
        assertNull(card.load)
        assertNull(card.archetype)
        assertNull(card.faceitPlayerId)
        assertNull(card.food.averageKcalPerDay)
        assertEquals(7, card.wheel.size)
        assertTrue(card.wheel.all { it.values == null })

        val analytics = StaffViews.teamAnalytics(team, TODAY, TZ, 7)
        assertNull(analytics.readiness.value)
        assertNull(analytics.sleep.delta)
        assertEquals(StaffViews.Kpi(null, null, null), analytics.winRate)
        assertEquals(0, analytics.matches)
        assertTrue(analytics.trend.all { it.mean == null && it.min == null && it.max == null })
        assertNull(analytics.table.single().readiness)
        assertNull(analytics.table.single().status)

        assertTrue(StaffViews.teamFood(team, TODAY, TZ, 7).isEmpty())

        val week = StaffViews.weeklyTeam(team, WeekRange(Instant.parse("2026-10-11T21:00:00Z"), Instant.parse("2026-10-18T21:00:00Z")), TZ)
        assertNull(week.readiness)
        assertNull(week.sleepHours)
        assertNull(week.mood)
        assertTrue(week.rows.single().risks.isEmpty())
    }

    // ------------------------------------------------------------------ player card

    private fun novaDocs(): Map<String, String> = mapOf(
        "sleep" to sleepDoc(
            session("2026-10-14T23:30:00Z", 240), // 02:30 on the 15th, 4 h
            session("2026-10-15T04:30:00Z", 210), // 07:30 on the 15th, 3.5 h
            session("2026-10-14T01:30:00Z", 330), // 5.5 h, ends on the 14th
            session("2026-10-13T10:00:00Z", 30), // a nap: not sleep
        ),
        "wellbeing" to wellbeing(
            mood("2026-10-15", 6, 5), mood("2026-10-15", 8, 7), mood("2026-10-14", 4, 4),
            balance = """{"id":"b1","day":"2026-10-15T00:00:00Z","values":{"physical":8,"emotional":7.5}}""",
        ),
        "cognitive" to cognitiveDoc(
            cognitive("2026-10-15", "reaction", 60),
            cognitive("2026-10-15", "stroop", 80, "12:00:00"),
            cognitive("2026-10-14", "reaction", 50),
            games = """{"id":"g1","date":"2026-10-15T15:00:00Z","phase":"beforeTraining","focus":9,"fatigue":3,"readiness":7}""",
        ),
        "nutrition" to nutrition(
            meal(UUID_1, "2026-10-15T09:30:00Z", "Омлет", 600, photoRef = true, source = "photo"),
            meal("m2", "2026-10-15T15:00:00Z", "Салат", 300, photoRef = true),
            meal("22222222-2222-4222-8222-222222222222", "2026-10-14T08:00:00Z", " ", -5),
            meal("33333333-3333-4333-8333-333333333333", "2026-10-01T08:00:00Z", "Старое", 100),
        ),
        "fitness-state" to """
            {"workouts":[
              {"id":"w1","kind":"strength","date":"2026-10-15T10:00:00Z","minutes":60,"averageHeartRate":120,"rpe":7},
              {"id":"w2","kind":"hiit","date":"2026-10-14T10:00:00Z","minutes":30},
              {"id":"AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA","kind":"cardio","date":"2026-10-13T10:00:00Z","minutes":40}],
             "ratings":["aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",5]}""",
        "player-survey" to """
            {"mindset":[
              {"id":"r1","date":"2026-10-01T10:00:00Z","scores":{"composure":90,"resilience":60,"focus":60,"teamwork":60,"leadership":60,"drive":60}},
              {"id":"r2","date":"2026-10-10T10:00:00Z","scores":{"composure":10,"resilience":10,"focus":10,"teamwork":10,"leadership":10}}]}""",
        "faceit" to """{"nickname":"nova-fc","playerId":"fc-1","snapshots":[]}""",
    )

    @Test
    fun unknownPlayerHasNoCard() {
        assertNull(StaffViews.playerCard(snapshot(Doc("a")), "zzz", TODAY, TZ, 7))
    }

    @Test
    fun cardKpisAreDayMeansOfTheDaysWithData() {
        val card = assertNotNull(StaffViews.playerCard(snapshot(Doc("nova", novaDocs())), "nova", TODAY, TZ, 7))
        assertEquals("nova", card.player.nickname)
        assertEquals("2026-10-09", card.period.from)
        assertEquals(60.0, card.kpis.score.value) // days 70 and 50
        assertEquals(20.0, card.kpis.score.change) // 70 - 50
        assertEquals(62.5, card.kpis.state.value) // 45 on the 14th, 80 on the 15th
        assertEquals(35.0, card.kpis.state.change)
        assertEquals(StaffViews.HighDays(1, 1), card.kpis.focusHigh)
        assertEquals(StaffViews.HighDays(0, 1), card.kpis.fatigueHigh)
        assertEquals(80.0, card.series.readiness.getValue(TODAY))
        assertEquals("fc-1", card.faceitPlayerId)
    }

    @Test
    fun cardTestsListCognitiveAndGameTestsNewestFirst() {
        val card = assertNotNull(StaffViews.playerCard(snapshot(Doc("nova", novaDocs())), "nova", TODAY, TZ, 7))
        val rows = card.tests.rows
        assertEquals(listOf("До тренировки", "Тест Струпа", "Скорость реакции", "Скорость реакции"), rows.map { it.title })
        assertEquals(listOf(true, false, false, false), rows.map { it.game })
        assertEquals(listOf(null, 80.0, 60.0, 50.0), rows.map { it.score })
        assertEquals(9.0, rows[0].focus)
        assertEquals(3.0, rows[0].fatigue)
        assertEquals(80, rows[0].stateIndex)
        assertEquals(45, rows[3].stateIndex)
        assertEquals("2026-10-15", rows[0].day)
        assertEquals(mapOf("2026-10-14" to 50.0, "2026-10-15" to 70.0), card.tests.scoreByDay)
        assertEquals(mapOf("2026-10-14" to 45.0, "2026-10-15" to 80.0), card.tests.stateByDay)
    }

    @Test
    fun cardTestsStayInsideThePeriod() {
        val docs = mapOf("cognitive" to cognitiveDoc(cognitive("2026-10-15", "aim", 70), cognitive("2026-10-08", "aim", 90)))
        val card = assertNotNull(StaffViews.playerCard(snapshot(Doc("a", docs)), "a", TODAY, TZ, 7))
        assertEquals(listOf(70.0), card.tests.rows.map { it.score })
        val wider = assertNotNull(StaffViews.playerCard(snapshot(Doc("a", docs)), "a", TODAY, TZ, 30))
        assertEquals(listOf(70.0, 90.0), wider.tests.rows.map { it.score })
    }

    @Test
    fun cardSleepTableMergesTheSessionsOfOneNight() {
        val card = assertNotNull(StaffViews.playerCard(snapshot(Doc("nova", novaDocs())), "nova", TODAY, TZ, 7))
        val rows = card.sleep.rows
        assertEquals(listOf("2026-10-15", "2026-10-14"), rows.map { it.day })
        assertEquals(Instant.parse("2026-10-14T19:30:00Z"), rows[0].start)
        assertEquals(Instant.parse("2026-10-15T04:30:00Z"), rows[0].end)
        assertEquals(7.5, rows[0].hours)
        assertFalse(rows[0].short)
        assertEquals(5.5, rows[1].hours)
        assertTrue(rows[1].short)
        assertEquals(6.5, card.sleep.averageHours)
        assertEquals(1, card.sleep.shortNights)
        assertEquals(2, card.sleep.nightsWithData)
    }

    @Test
    fun cardLoadIsTheRatioOfTheLastTwentyEightDays() {
        val card = assertNotNull(StaffViews.playerCard(snapshot(Doc("nova", novaDocs())), "nova", TODAY, TZ, 7))
        val load = assertNotNull(card.load)
        assertEquals(620.0, load.acute) // 60 x 7 + 40 x 5 (rating from the document); the unrated HIIT workout adds nothing
        assertEquals(155.0, load.chronicWeekly)
        assertEquals(4.0, load.ratio)
        assertEquals(LoadStatus.HIGH, load.status)
    }

    @Test
    fun workoutsNobodyRatedGiveNoLoadAndNoRisk() {
        // A pulse and a type are there, but staff never estimate an effort (it needs the player's age).
        val docs = mapOf("fitness-state" to """{"workouts":[
            {"id":"w1","kind":"hiit","date":"2026-10-14T10:00:00Z","minutes":60,"averageHeartRate":185},
            {"id":"w2","kind":"strength","date":"2026-10-15T10:00:00Z","minutes":90}]}""")
        assertNull(assertNotNull(StaffViews.playerCard(snapshot(Doc("a", docs)), "a", TODAY, TZ, 7)).load)
        val week = WeekRange(Instant.parse("2026-10-11T21:00:00Z"), Instant.parse("2026-10-18T21:00:00Z"))
        assertTrue(StaffViews.weeklyTeam(snapshot(Doc("a", docs)), week, TZ).rows.single().risks.isEmpty())
    }

    @Test
    fun cardLoadIgnoresWorkoutsOutsideTheWindow() {
        val docs = mapOf("fitness-state" to """{"workouts":[{"id":"w","kind":"cardio","date":"2026-08-01T10:00:00Z","minutes":40,"rpe":5}]}""")
        assertNull(assertNotNull(StaffViews.playerCard(snapshot(Doc("a", docs)), "a", TODAY, TZ, 7)).load)
    }

    @Test
    fun cardFoodRowsCarryMealIdsAndPhotoFlags() {
        val card = assertNotNull(StaffViews.playerCard(snapshot(Doc("nova", novaDocs())), "nova", TODAY, TZ, 7))
        val food = card.food
        assertEquals(3, food.total)
        assertEquals(listOf("Салат", "Омлет", null), food.meals.map { it.name })
        assertEquals(listOf("m2", UUID_1, "22222222-2222-4222-8222-222222222222"), food.meals.map { it.mealId })
        assertEquals(listOf(false, true, false), food.meals.map { it.hasPhoto }) // "m2" is not an id the photo store holds
        assertEquals(listOf(300.0, 600.0, null), food.meals.map { it.kcal })
        assertEquals(30.0, food.meals[1].protein)
        assertEquals(com.pulse.core.model.MealSource.PHOTO, food.meals[1].source)
        assertEquals(900.0, food.averageKcalPerDay)
        assertEquals("nova", food.meals[0].playerId)
    }

    @Test
    fun cardWheelHasSevenDaysWithTheAppsDefaultForMissingKeys() {
        val card = assertNotNull(StaffViews.playerCard(snapshot(Doc("nova", novaDocs())), "nova", TODAY, TZ, 7))
        assertEquals(listOf("2026-10-15", "2026-10-14", "2026-10-13", "2026-10-12", "2026-10-11", "2026-10-10", "2026-10-09"), card.wheel.map { it.day })
        val today = assertNotNull(card.wheel[0].values)
        assertEquals(BalanceCategory.entries, today.map { it.category })
        assertEquals(listOf(8.0, 7.5), today.take(2).map { it.value })
        assertTrue(today.drop(2).all { it.value == 5.0 })
        assertNull(card.wheel[1].values)
    }

    @Test
    fun cardArchetypeIsTheLatestFinishedRun() {
        val archetype = assertNotNull(StaffViews.playerCard(snapshot(Doc("nova", novaDocs())), "nova", TODAY, TZ, 7)?.archetype)
        assertEquals("Хладнокровие", archetype.profile.archetype.title)
        assertEquals(listOf(MindsetTrait.COMPOSURE), archetype.profile.strengths)
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), archetype.date) // the later run lacks a trait: unfinished
    }

    // ------------------------------------------------------------------ team analytics

    private fun analyticsTeam() = snapshot(
        Doc("A", mapOf(
            "sleep" to sleepDoc(night("2026-10-14", 480), night("2026-10-15", 420), night("2026-10-08", 360)),
            "cognitive" to cognitiveDoc(cognitive("2026-10-14", "reaction", 70), cognitive("2026-10-15", "reaction", 80)),
        )),
        Doc("B", mapOf(
            "sleep" to sleepDoc(night("2026-10-15", 360)),
            "cognitive" to cognitiveDoc(cognitive("2026-10-15", "reaction", 60)),
        )),
    )

    @Test
    fun teamAnalyticsKpisCompareWithThePreviousPeriod() {
        val view = StaffViews.teamAnalytics(analyticsTeam(), TODAY, TZ, 7)
        assertEquals("2026-10-09", view.period.from)
        assertEquals(StaffViews.Kpi(80.0, 43.0, 37.0), view.readiness) // days 91 and 69 against 43
        assertEquals(StaffViews.Kpi(70.0, null, null), view.score)
        assertEquals(StaffViews.Kpi(7.25, 6.0, 1.25), view.sleep)
        assertEquals(StaffViews.Kpi(null, null, null), view.winRate) // no matches given
    }

    @Test
    fun teamAnalyticsWinRateIsTheShareOfWonMatches() {
        val matches = mapOf(
            "A" to listOf(
                match("2026-10-14T16:00:00Z", 10, 10, 80.0, won = true),
                match("2026-10-15T16:00:00Z", 10, 10, 80.0, won = false),
                match("2026-10-08T16:00:00Z", 10, 10, 80.0, won = false), // the period before
                match("2026-09-01T16:00:00Z", 10, 10, 80.0, won = true), // long before
            ),
            "B" to listOf(match("2026-10-15T09:00:00Z", 10, 10, 80.0, won = true)),
        )
        val view = StaffViews.teamAnalytics(analyticsTeam(), TODAY, TZ, 7, matches)
        assertEquals(3, view.matches)
        assertEquals(StaffViews.Kpi(66.67, 0.0, 66.67), view.winRate)
    }

    @Test
    fun teamAnalyticsTrendIsTheDailySpreadOfThePlayers() {
        val trend = StaffViews.teamAnalytics(analyticsTeam(), TODAY, TZ, 7).trend
        assertEquals(7, trend.size)
        assertEquals("2026-10-09", trend.first().day)
        assertNull(trend[4].mean)
        assertEquals(StaffViews.TrendPoint("2026-10-14", 91.0, 91.0, 91.0), trend[5])
        assertEquals(StaffViews.TrendPoint("2026-10-15", 69.0, 56.0, 82.0), trend[6])
    }

    @Test
    fun teamAnalyticsBarsAndTable() {
        val view = StaffViews.teamAnalytics(analyticsTeam(), TODAY, TZ, 7)
        assertEquals(listOf("A", "B"), view.playerReadiness.map { it.id })
        assertEquals(86.5, view.playerReadiness[0].readiness)
        assertEquals(StaffMetrics.ReadinessStatus.READY, view.playerReadiness[0].status)
        assertEquals(StaffMetrics.ReadinessStatus.DOUBT, view.playerReadiness[1].status)
        val a = view.table.first { it.id == "A" }
        assertEquals(75.0, a.score)
        assertEquals(7.5, a.sleepHours)
        assertNull(a.mood)
    }

    @Test
    fun teamAnalyticsBarsPutPlayersWithoutDataLast() {
        val team = snapshot(Doc("none"), Doc("some", mapOf("sleep" to sleepDoc(night(TODAY, 480)))))
        val view = StaffViews.teamAnalytics(team, TODAY, TZ, 7)
        assertEquals(listOf("some", "none"), view.playerReadiness.map { it.id })
        assertNull(view.playerReadiness[1].readiness)
    }

    // ------------------------------------------------------------------ correlations

    private fun correlationTeam(): Pair<TeamSnapshot, Map<String, List<GameMatch>>> {
        val days = listOf("2026-10-11", "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15")
        val hours = listOf(5, 6, 7, 8, 9)
        val kills = listOf(8, 10, 12, 14, 16)
        val sleep = sleepDoc(*days.indices.map { night(days[it], hours[it] * 60) }.toTypedArray())
        val team = snapshot(Doc("A", mapOf("sleep" to sleep)), Doc("B"))
        val matches = days.indices.map { match("${days[it]}T16:00:00Z", kills[it], 10, 80.0, won = kills[it] >= 12) }
        return team to mapOf("A" to matches)
    }

    @Test
    fun correlationsPoolStateAndMatchDays() {
        val (team, matches) = correlationTeam()
        val view = StaffViews.teamCorrelations(team, matches, TODAY, TZ, 7)
        assertEquals(listOf("mood", "energy", "sleep", "screen", "cog", "balance", "kcal"), view.factors.map { it.id })
        assertEquals(listOf("elo", "kd", "adr", "wr"), view.games.map { it.id })
        val sleepRow = view.rows.first { it.factor.id == "sleep" }
        val kd = sleepRow.cells[view.games.indexOfFirst { it.id == "kd" }]
        assertTrue(abs(assertNotNull(kd.r) - 1.0) < 1e-9)
        assertEquals("Сильная", kd.strength.label)
        assertEquals("direct", kd.strength.direction)
        assertEquals(5, kd.days)
        assertNull(sleepRow.cells[view.games.indexOfFirst { it.id == "adr" }].r) // ADR never changes: no correlation
        assertNull(sleepRow.cells[view.games.indexOfFirst { it.id == "elo" }].r) // no ELO snapshots
        assertEquals("мало данных", sleepRow.cells[0].strength.label)
        assertTrue(view.rows.first { it.factor.id == "mood" }.cells.all { it.r == null && it.days == 0 })
    }

    @Test
    fun correlationsListTheStrongestLinksFirst() {
        val (team, matches) = correlationTeam()
        val links = StaffViews.teamCorrelations(team, matches, TODAY, TZ, 7).links
        assertEquals(listOf("sleep" to "kd", "sleep" to "wr"), links.map { it.a.id to it.b.id })
        assertTrue(abs(links[1].r - 0.8660254) < 1e-6)
        val split = assertNotNull(links[0].split)
        assertEquals(7.0, split.median)
        assertEquals(1.4, split.hiMean, 1e-9)
        assertEquals(0.9, split.loMean, 1e-9)
    }

    @Test
    fun sleepKdScatterPairsEachMatchWithTheNightEndingThatDay() {
        val (team, matches) = correlationTeam()
        val extra = matches.getValue("A") + match("2026-10-10T16:00:00Z", 12, 10, 80.0, won = true) // no sleep that day
        val scatter = StaffViews.teamCorrelations(team, mapOf("A" to extra), TODAY, TZ, 7).sleepKd
        assertEquals(listOf(5.0, 6.0, 7.0, 8.0, 9.0), scatter.points.map { it.sleepHours })
        assertEquals(listOf(0.8, 1.0, 1.2, 1.4, 1.6), scatter.points.map { Math.round(it.kd * 10) / 10.0 })
        assertEquals(listOf(false, false, true, true, true), scatter.points.map { it.won })
        assertEquals("A", scatter.points.first().playerId)
        assertTrue(abs(assertNotNull(scatter.r) - 1.0) < 1e-9)
        assertTrue(StaffViews.teamCorrelations(team, matches, TODAY, TZ, 7, only = "B").sleepKd.points.isEmpty())
        assertNull(StaffViews.teamCorrelations(team, matches, TODAY, TZ, 7, only = "B").sleepKd.r)
    }

    @Test
    fun correlationsCanBeNarrowedToOnePlayer() {
        val (team, matches) = correlationTeam()
        assertTrue(StaffViews.teamCorrelations(team, matches, TODAY, TZ, 7, only = "B").links.isEmpty())
        assertEquals(2, StaffViews.teamCorrelations(team, matches, TODAY, TZ, 7, only = "A").links.size)
    }

    @Test
    fun correlationsUseSnapshotsForElo() {
        val days = (11..15).map { "2026-10-$it" }
        val elo = days.mapIndexed { i, d -> """{"date":"${d}T12:00:00Z","elo":${1000 + i * 10}}""" }.joinToString(",")
        val sleep = sleepDoc(*days.mapIndexed { i, d -> night(d, (5 + i) * 60) }.toTypedArray())
        val team = snapshot(Doc("A", mapOf("sleep" to sleep, "faceit" to """{"nickname":"n","playerId":"p","snapshots":[$elo]}""")))
        val view = StaffViews.teamCorrelations(team, emptyMap(), TODAY, TZ, 7)
        val cell = view.rows.first { it.factor.id == "sleep" }.cells[0]
        assertTrue(abs(assertNotNull(cell.r) - 1.0) < 1e-9)
        assertEquals("elo", view.links.single().b.id)
    }

    // ------------------------------------------------------------------ food

    @Test
    fun teamFoodIsNewestFirstInsideTheWindow() {
        val team = snapshot(
            Doc("A", mapOf("nutrition" to nutrition(
                meal(UUID_1, "2026-10-15T12:00:00Z", "Паста", 700, photoRef = true),
                meal("22222222-2222-4222-8222-222222222222", "2026-10-14T08:00:00Z", "Каша", 400),
                meal("33333333-3333-4333-8333-333333333333", "2026-10-13T08:00:00Z", "Вне окна", 400),
                meal("44444444-4444-4444-8444-444444444444", "2026-10-16T08:00:00Z", "Из будущего", 400),
            ))),
            Doc("B", mapOf("nutrition" to nutrition(meal("55555555-5555-4555-8555-555555555555", "2026-10-15T15:00:00Z", "Суп", 350)))),
        )
        val entries = StaffViews.teamFood(team, TODAY, TZ, 2)
        assertEquals(listOf("Суп", "Паста", "Каша"), entries.map { it.name })
        assertEquals(listOf("B", "A", "A"), entries.map { it.playerId })
        assertEquals(listOf(false, true, false), entries.map { it.hasPhoto })
        assertEquals(listOf("2026-10-15", "2026-10-15", "2026-10-14"), entries.map { it.day })
        assertEquals(700.0, entries[1].kcal)
        assertEquals(UUID_1, entries[1].mealId)
        assertEquals(1, StaffViews.teamFood(team, TODAY, TZ, 1).count { it.playerId == "A" })
    }

    @Test
    fun mealsWithoutAnIdOrAnInstantAreSkipped() {
        val broken = """{"meals":[{"date":"2026-10-15T12:00:00Z","name":"без id","kcal":1},{"id":"x","date":"nope","name":"без даты","kcal":1}]}"""
        assertTrue(StaffViews.teamFood(snapshot(Doc("A", mapOf("nutrition" to broken))), TODAY, TZ, 7).isEmpty())
    }

    // ------------------------------------------------------------------ weekly

    @Test
    fun weeklyTeamRowsCompareWithThePreviousWeek() {
        val week = WeekRange(Instant.parse("2026-10-11T21:00:00Z"), Instant.parse("2026-10-18T21:00:00Z")) // Mon 12 .. Sun 18, Moscow
        val team = snapshot(
            Doc("A", mapOf("sleep" to sleepDoc(night("2026-10-13", 300), night("2026-10-14", 360), night("2026-10-08", 480)))),
            Doc("B", mapOf(
                "sleep" to sleepDoc(night("2026-10-14", 480)),
                "wellbeing" to wellbeing(mood("2026-10-14", 8, 8)),
                "fitness-state" to """{"workouts":[{"id":"w","kind":"strength","date":"2026-10-14T10:00:00Z","minutes":60,"rpe":10}]}""",
            )),
            Doc("C"),
        )
        val report = StaffViews.weeklyTeam(team, week, TZ)
        assertEquals(listOf("B", "A", "C"), report.rows.map { it.id })
        val b = report.rows[0]
        assertEquals(95.0, b.readiness) // sleep 8 h, mood 8 and energy 8 on the 14th: 70.25 of 85, scaled to 115
        assertEquals(8.0, b.mood)
        assertNull(b.previousReadiness)
        assertNull(b.readinessDelta)
        assertEquals(listOf("перегруз (ACWR 4,00)"), b.risks)
        val a = report.rows[1]
        assertEquals(28.5, a.readiness) // 14 and 43
        assertEquals(5.5, a.sleepHours)
        assertEquals(100.0, a.previousReadiness)
        assertEquals(-71.5, a.readinessDelta)
        assertEquals(8.0, a.previousSleepHours)
        assertEquals(-2.5, a.sleepDelta)
        assertEquals(listOf("готовность 29", "сон 5 ч 30 м"), a.risks)
        assertTrue(report.rows[2].risks.isEmpty())
        assertNull(report.rows[2].readiness)

        assertEquals(61.75, report.readiness)
        assertEquals(100.0, report.previousReadiness)
        assertEquals(-38.25, report.readinessDelta)
        assertEquals(6.75, report.sleepHours)
        assertEquals(2, report.atRisk)
        assertEquals(listOf("2026-10-12", "2026-10-18"), listOf(report.days.first(), report.days.last()))
    }

    // ------------------------------------------------------------------ helpers

    private fun statusOf(value: JsonElement): StaffMetrics.ReadinessStatus? = when ((value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content) {
        "ready" -> StaffMetrics.ReadinessStatus.READY
        "doubt" -> StaffMetrics.ReadinessStatus.DOUBT
        "risk" -> StaffMetrics.ReadinessStatus.RISK
        null -> null
        else -> fail("unknown status $value")
    }

    private fun number(value: JsonElement?): Double? = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

    private fun snapshotOf(roster: List<JsonObject>): TeamSnapshot = TeamSnapshot(
        teamName = "Fixture",
        inviteCode = null,
        players = roster.map { StaffPlayer(it.getValue("id").jsonPrimitive.content, it.getValue("id").jsonPrimitive.content) },
        documents = roster.associate { it.getValue("id").jsonPrimitive.content to it.getValue("docs").jsonObject.toMap() },
        fetchedAt = Instant.parse("2026-10-15T12:00:00Z"),
    )

    private fun loadFixture(): JsonObject {
        val relative = "server/test/fixtures/staff-metrics.json"
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val file = generateSequence(start) { it.parentFile }.map { File(it, relative) }.firstOrNull { it.isFile }
            ?: fail("Cannot find $relative from $start or any parent directory")
        return PulseJson.parseToJsonElement(file.readText()).jsonObject
    }
}
