package com.pulse.core.store

import com.pulse.core.demo.DemoData
import com.pulse.core.demo.GameData
import com.pulse.core.demo.GameMetric
import com.pulse.core.demo.ScatterPoint
import com.pulse.core.demo.TeamAnalytics
import com.pulse.core.demo.TeamFood
import com.pulse.core.demo.WellbeingFactor
import com.pulse.core.model.BalanceCategory
import com.pulse.core.model.CSMap
import com.pulse.core.model.CalendarEvent
import com.pulse.core.model.CognitiveOutcome
import com.pulse.core.model.CognitiveTestKind
import com.pulse.core.model.DaySlot
import com.pulse.core.model.DiaryScope
import com.pulse.core.model.EventTag
import com.pulse.core.model.FaceitProfile
import com.pulse.core.model.FormIndex
import com.pulse.core.model.GameGoals
import com.pulse.core.model.GameInsight
import com.pulse.core.model.GameMatch
import com.pulse.core.model.GameSummary
import com.pulse.core.model.HealthDaySummary
import com.pulse.core.model.HealthSnapshot
import com.pulse.core.model.Lineup
import com.pulse.core.model.LineupSide
import com.pulse.core.model.LineupType
import com.pulse.core.model.LineupVisibility
import com.pulse.core.model.Meal
import com.pulse.core.model.MealSource
import com.pulse.core.model.MindsetTest
import com.pulse.core.model.NutritionTotals
import com.pulse.core.model.PlayerSurvey
import com.pulse.core.model.SleepSession
import com.pulse.core.model.SleepWindow
import com.pulse.core.model.Tariff
import com.pulse.core.model.User
import com.pulse.core.model.UserRole
import com.pulse.core.model.Workout
import com.pulse.core.model.WorkoutKind
import com.pulse.core.model.pinTestZone
import com.pulse.core.platform.MemorySecretStore
import com.pulse.core.util.PulseJson
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.addingDays
import com.pulse.core.util.addingMinutes
import com.pulse.core.util.daysUntil
import com.pulse.core.util.setting
import com.pulse.core.util.startOfDay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Ported from PulseTests/StoreTests.swift, DiaryAndStaffTests.swift, DashboardTests.swift and the store parts of others. */
class SleepStoreTests {
    @BeforeTest fun zone() = pinTestZone()

    private val base = Instant.fromEpochSeconds(1_800_000_000)

    @Test fun startAndStopRecordsDuration() {
        val store = SleepStore(null, emptyList())
        store.startSleep(base)
        assertTrue(store.isSleeping)
        val session = assertNotNull(store.stopSleep(base.addingMinutes(462)))
        assertEquals(462, session.durationMinutes)
        assertTrue(session.isValid)
        assertFalse(store.isSleeping)
        assertEquals(1, store.sessions.size)
    }

    @Test fun stopWithoutStartAndSecondStart() {
        val store = SleepStore(null, emptyList())
        assertNull(store.stopSleep(base))
        assertTrue(store.sessions.isEmpty())
        store.startSleep(base)
        store.startSleep(base.addingMinutes(30))
        assertEquals(base, store.activeStart)
    }

    @Test fun shortSessionIsIncomplete() {
        val store = SleepStore(null, emptyList())
        store.startSleep(base)
        val session = assertNotNull(store.stopSleep(base.addingMinutes(20)))
        assertFalse(session.isValid)
        assertEquals("Неполная", session.statusTitle)
    }

    @Test fun averagesIgnoreIncompleteSessions() {
        val now = base.addingDays(3)
        val store = SleepStore(null, listOf(
            SleepSession(Uuid.random(), base, base.addingMinutes(400)),
            SleepSession(Uuid.random(), base.addingDays(1), base.addingDays(1).addingMinutes(500)),
            SleepSession(Uuid.random(), base.addingDays(2), base.addingDays(2).addingMinutes(10))
        ))
        assertEquals(450, store.averageMinutes(7, now))
        assertEquals(910, store.totalMinutes(7, now))
    }

    @Test fun windowWrapsMidnightAndTrackingIsReported() {
        assertEquals(480, SleepWindow(23, 0, 7, 0).durationMinutes)
        val store = SleepStore(null, emptyList())
        val events = mutableListOf<Instant?>()
        store.onTrackingChange = { start, _ -> events += start }
        store.startSleep(base)
        store.stopSleep(base.addingMinutes(400))
        assertEquals(listOf(base, null), events)
    }

    @Test fun realAccountsStartEmptyDemoAccountsSeeded() {
        val store = SleepStore(null, emptyList())
        store.reload(isDemo = false)
        assertTrue(store.sessions.isEmpty())
        assertNull(store.displayNight())
        store.reload(isDemo = true)
        assertTrue(store.sessions.isNotEmpty())
        assertNotNull(store.displayNight())
    }
}

class NutritionWellbeingCognitiveCalendarTests {
    @BeforeTest fun zone() = pinTestZone()

    @Test fun totalsAndWaterForToday() {
        val store = NutritionStore(null, MockFoodAnalyzer(Duration.ZERO), emptyList())
        store.addMeal("Овсянка", 300, 10, 5, 50, source = MealSource.MANUAL)
        store.addMeal("Курица", 500, 40, 10, 40, source = MealSource.PHOTO)
        store.addWater(250)
        store.addWater(250)
        val now = Clock.System.now()
        assertEquals(NutritionTotals(800, 50, 15, 90), store.totals(now))
        assertEquals(500, store.water(now))
        assertEquals(2, store.mealCount(1))
        store.addMeal("Ошибка", -100, protein = -1, source = MealSource.MANUAL)
        assertEquals(0, store.meals.first { it.name == "Ошибка" }.kcal)
        store.deleteMeal(store.meals.first { it.name == "Ошибка" }.id)
        assertEquals(2, store.meals.size)
    }

    @Test fun mockAnalyzerRejectsTinyImages() = runTest {
        val analyzer = MockFoodAnalyzer(Duration.ZERO)
        assertFailsWith<FoodAnalysisException> { analyzer.analyze(ByteArray(10)) }
        assertNotNull(analyzer.analyze(ByteArray(4096)))
    }

    @Test fun savingSameSlotReplacesEntryAndBalanceIsPerDay() {
        val store = WellbeingStore(null, emptyList(), emptyList())
        val day = Clock.System.now()
        store.saveMood(day, DaySlot.MORNING, 4, 5)
        store.saveMood(day, DaySlot.MORNING, 8, 7)
        store.saveMood(day, DaySlot.EVENING, 6, 3)
        assertEquals(2, store.moodEntries.size)
        assertEquals(8, store.moodEntry(day, DaySlot.MORNING)?.mood)
        assertEquals(setOf(DaySlot.MORNING, DaySlot.EVENING), store.filledSlots(day))
        assertEquals(WellbeingStore.MoodAverage(7.0, 5.0), store.averageMood(day))
        val values = BalanceCategory.entries.associateWith { 6.0 }
        store.saveBalance(day, values)
        store.saveBalance(day, values + (BalanceCategory.FINANCIAL to 9.0))
        assertEquals(1, store.balanceEntries.size)
        assertEquals(9.0, store.balanceEntry(day)?.value(BalanceCategory.FINANCIAL))
        assertNull(store.balanceEntry(day.addingDays(-1)))
    }

    @Test fun cognitiveRecordsAndAverages() {
        val store = CognitiveStore(null, emptyList(), emptyList())
        store.record(CognitiveTestKind.REACTION, CognitiveOutcome(80, "260 мс"))
        store.record(CognitiveTestKind.MEMORY, CognitiveOutcome(60, "6 цифр"))
        assertEquals(70.0, store.averageScore(Clock.System.now()))
        assertEquals(80, store.latest(CognitiveTestKind.REACTION)?.score)
        assertFalse(store.completedToday())
        CognitiveTestKind.entries.forEach { store.record(it, CognitiveOutcome(50, "")) }
        assertTrue(store.completedToday())
    }

    private fun event(title: String, day: Instant, tag: EventTag) =
        CalendarEvent(Uuid.random(), title, "", day.setting(12, 0), day.setting(13, 0), emptyList(), tag, null)

    @Test fun calendarFiltersAndUpserts() {
        val today = Clock.System.now()
        val store = CalendarStore(null, listOf(
            event("Тренировка", today, EventTag.Team), event("Пробежка", today, EventTag.Personal),
            event("Матч", today.addingDays(1), EventTag.Team)
        ))
        assertEquals(2, store.events(today, null).size)
        assertEquals(listOf("Тренировка"), store.events(today, EventTag.Team).map { it.title })
        assertTrue(store.hasEvents(today.addingDays(1), EventTag.Team))
        assertFalse(store.hasEvents(today.addingDays(1), EventTag.Personal))
        val original = store.events.first()
        store.upsert(original.copy(title = "Разбор игры", tag = EventTag.Player("Nova")))
        assertEquals("Разбор игры", store.event(original.id)?.title)
        assertEquals(EventTag.Player("Nova"), store.event(original.id)?.tag)
        // Tags keep the Swift enum JSON shape.
        val json = PulseJson.encodeToString(CalendarEvent.serializer(), store.event(original.id)!!)
        assertTrue(json.contains("""{"player":{"_0":"Nova"}}"""))
        assertEquals(EventTag.Player("Nova"), PulseJson.decodeFromString(CalendarEvent.serializer(), json).tag)
    }

    @Test fun screenTimeManualEntries() {
        val store = ScreenTimeStore(null, isDemo = false)
        val now = Clock.System.now()
        assertEquals(0, store.todayMinutes(now))
        assertFalse(store.hasDetailedData)
        assertTrue(store.showsContent)
        store.setMinutes(185, now)
        store.setMinutes(200, now) // replaces, doesn't duplicate
        store.setMinutes(240, now.addingDays(-1))
        assertEquals(200, store.todayMinutes(now))
        assertEquals(2, store.state.value.entries.size)
        assertEquals(200, store.week(now).last().minutes)
        assertEquals(2, store.daily(7, now).size)
        assertTrue(store.apps.isEmpty() && store.hourly.isEmpty())
    }
}

private class FakeHealth(override val isAvailable: Boolean = true, val snapshot: HealthSnapshot) : HealthDataProvider {
    override val name = "Health Connect"
    override suspend fun requestAuthorization() {}
    override suspend fun snapshot(now: Instant): HealthSnapshot = snapshot
}

class FitnessStoreTests {
    @BeforeTest fun zone() = pinTestZone()

    private fun snapshot(steps: Int, minutes: Int, kcal: Int): HealthSnapshot {
        val now = Clock.System.now()
        return HealthSnapshot(
            today = HealthDaySummary(steps, 5.1, kcal, minutes),
            weeklyActiveMinutes = (0 until 7).map { com.pulse.core.model.DailyValue(now.startOfDay().addingDays(it - 6), 30.0) },
            workouts = listOf(Workout(Uuid.random(), WorkoutKind.CARDIO, "Health Connect", now, 30, 250, 140)),
            heartRateZones = null, averageHeartRate = null, restingHeartRate = 58, hrv = 70, bodyMetrics = emptyList(), fetchedAt = now
        )
    }

    @Test fun demoUntilConnectedThenRealData() = runTest {
        val store = FitnessStore(null, FakeHealth(snapshot = snapshot(9000, 35, 380)))
        assertFalse(store.state.value.isHealthConnected)
        assertEquals(DemoData.BASELINE_STEPS, store.steps)
        assertNotNull(store.heartRateZones)
        store.connectHealth()
        assertTrue(store.state.value.isHealthConnected)
        assertEquals(9000, store.steps)
        assertEquals(35, store.activeMinutesToday())
        assertEquals(380, store.kcalToday())
        assertEquals(35.0, store.weeklyActiveMinutes().last().value)
        assertTrue(store.todayWorkouts().all { it.id !in DemoData.seededWorkoutIDs })
        assertTrue(store.todayWorkouts().any { it.detail == "Health Connect" })
        assertNull(store.heartRateZones) // no watch → no zones rather than fake ones
        store.disconnectHealth()
        assertEquals(DemoData.BASELINE_STEPS, store.steps)
    }

    @Test fun unavailableDeviceShowsError() = runTest {
        val store = FitnessStore(null, FakeHealth(isAvailable = false, snapshot = snapshot(1, 1, 1)))
        store.connectHealth()
        assertFalse(store.state.value.isHealthConnected)
        assertNotNull(store.state.value.healthError)
    }

    @Test fun ratingsOverrideEstimates() {
        val workout = Workout(Uuid.random(), WorkoutKind.STRENGTH, "", Clock.System.now(), 50, 300, 0)
        val store = FitnessStore(null, null, seed = listOf(workout))
        assertEquals(FitnessStore.Effort(6, true), store.effort(workout))
        store.rate(workout, 9)
        val updated = store.workouts.first { it.id == workout.id }
        assertEquals(FitnessStore.Effort(9, false), store.effort(updated))
        assertTrue(store.dailyLoad().last().value >= 450)
    }

    @Test fun realAccountIsEmpty() {
        val store = FitnessStore(null, null, isDemo = false)
        assertEquals(0, store.steps)
        assertTrue(store.todayWorkouts().isEmpty())
        assertNull(store.heartRateZones)
        assertTrue(store.loadWorkouts().isEmpty())
    }

    @Test fun healthRatingsKeepTheSwiftJsonShape() {
        val id = Uuid.parse("6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b")
        val json = PulseJson.encodeToString(FitnessStore.Saved.serializer(), FitnessStore.Saved(emptyList(), ratings = mapOf(id to 7)))
        assertTrue(json.contains(""""ratings":["6F1B6A2E-1C2D-4E5F-8A9B-0C1D2E3F4A5B",7]"""))
        assertEquals(mapOf(id to 7), PulseJson.decodeFromString(FitnessStore.Saved.serializer(), json).ratings)
    }
}

class DiaryAndGameStoreTests {
    @BeforeTest fun zone() = pinTestZone()

    private val solo = User(Uuid.random(), "Raven", "r@x.gg", UserRole.SOLO_PLAYER, Tariff.FREE, null, 0)
    private val player = User(Uuid.random(), "Nova", "n@x.gg", UserRole.TEAM_PLAYER, Tariff.PRO, "T", 0)
    private val staff = User(Uuid.random(), "Coach", "c@x.gg", UserRole.STAFF, Tariff.PRO, "T", 0)

    private fun entry(title: String, author: String, visibility: LineupVisibility, type: LineupType = LineupType.SMOKE) =
        Lineup(Uuid.random(), title, CSMap.MIRAGE, LineupSide.T, type, "детали $title", null, author, Clock.System.now(), visibility)

    private val items = listOf(
        entry("Командный смок", "Coach", LineupVisibility.TEAM),
        entry("Личная заметка Nova", "Nova", LineupVisibility.PERSONAL, LineupType.NOTE),
        entry("Общая флешка Nova", "Nova", LineupVisibility.TEAM),
        entry("Дневник Raven", "Raven", LineupVisibility.PERSONAL)
    )

    @Test fun privacyRulesPerRole() {
        assertEquals(listOf("Командный смок", "Общая флешка Nova"), LineupStore.visible(items, staff).map { it.title })
        assertEquals(listOf("Командный смок", "Личная заметка Nova", "Общая флешка Nova"), LineupStore.visible(items, player).map { it.title })
        assertEquals(listOf("Дневник Raven"), LineupStore.visible(items, solo).map { it.title })
        assertTrue(LineupStore.visible(items, null).isEmpty())
    }

    @Test fun scopeTypeSearchAndFavorites() {
        val store = LineupStore(null, items)
        assertEquals(2, store.diary(player, DiaryScope.MINE).size)
        assertEquals(2, store.diary(player, DiaryScope.TEAM).size)
        assertEquals(listOf("Личная заметка Nova"), store.diary(player, type = LineupType.NOTE).map { it.title })
        assertEquals(1, store.diary(player, query = "ФЛЕШ").size)
        val id = store.lineups.first { it.title == "Командный смок" }.id
        store.toggleFavorite(id)
        assertEquals(true, store.lineup(id)?.isFavorite)
        assertEquals(1, store.diary(player, DiaryScope.FAVORITES).size)
    }

    @Test fun legacyEntriesDecodeWithDefaults() {
        val json = """[{"id":"6F1B6A2E-1C2D-4E5F-8A9B-0C1D2E3F4A5B","title":"Старый смок","map":"mirage","side":"t","type":"smoke",
            "details":"","author":"Coach","createdAt":"2026-10-01T10:00:00Z"}]"""
        val decoded = PulseJson.decodeFromString(ListSerializer(Lineup.serializer()), json)
        assertEquals(LineupVisibility.TEAM, decoded.first().visibility)
        assertFalse(decoded.first().isFavorite)
        assertTrue(decoded.first().variations.isEmpty())
    }

    @Test fun demoSeedsGiveEveryDemoRoleADiary() {
        val store = LineupStore(null)
        assertTrue(store.diary(solo).isNotEmpty())
        assertTrue(store.diary(player).any { it.visibility == LineupVisibility.PERSONAL })
        assertTrue(store.diary(staff).all { it.visibility == LineupVisibility.TEAM })
    }

    private fun profile(elo: Int) = FaceitProfile(
        "p1", "Nova", null, "ru", "EU", elo, 10, 1500, null, emptyList(),
        listOf(GameMatch(Uuid.random(), Clock.System.now(), CSMap.MIRAGE, true, 13, 8, 22, 14, 4, 90.0, 0)), Clock.System.now()
    )

    private class FakeFaceit(val result: () -> FaceitProfile) : FaceitProvider {
        override suspend fun profile(nickname: String) = result()
    }

    @Test fun gameStoreDemoThenRealData() = runTest {
        val secrets = MemorySecretStore()
        val store = GameStore(null, secrets) { FakeFaceit { profile(3010) } }
        assertFalse(store.isConnected)
        assertEquals(GameData.profile(null).elo, store.profile(null).elo)
        store.connect("https://www.faceit.com/ru/players/Nova", " key-123 ")
        assertEquals("key-123", secrets.read(GameStore.API_KEY_ACCOUNT))
        assertEquals("Nova", store.state.value.nickname)
        assertTrue(store.isConnected)
        assertEquals(3010, store.profile(null).elo)
        assertEquals(1, store.matches(null).size)
        assertEquals(listOf(3010.0), store.eloHistory(null, 7).map { it.value })
    }

    @Test fun gameStoreErrors() = runTest {
        val noKey = GameStore(null, MemorySecretStore()) { FakeFaceit { profile(1) } }
        noKey.connect("Nova", "")
        assertNotNull(noKey.state.value.error)
        val missing = GameStore(null, MemorySecretStore()) { FakeFaceit { throw FaceitException(FaceitException.Kind.PLAYER_NOT_FOUND) } }
        missing.connect("nobody", "key")
        assertEquals(FaceitException(FaceitException.Kind.PLAYER_NOT_FOUND).description, missing.state.value.error)
        assertFalse(missing.usesRealData)
        val real = GameStore(null, MemorySecretStore()) { FakeFaceit { profile(1) } }
        real.reload(isDemo = false)
        assertFalse(real.hasData)
        assertTrue(real.matches(null).isEmpty())
    }

    @Test fun oneEloSnapshotPerDay() {
        val morning = Clock.System.now().startOfDay().addingMinutes(9 * 60)
        var list = GameStore.recording(2900, morning.addingDays(-1), emptyList())
        list = GameStore.recording(2950, morning, list)
        list = GameStore.recording(2970, morning.addingMinutes(120), list)
        assertEquals(listOf(2900, 2970), list.map { it.elo })
    }

    @Test fun eloGoalStartIsTakenWhenTargetChanges() {
        val store = GameGoalsStore(null)
        store.update(GameGoals(elo = 3200), 2900)
        assertEquals(2900, store.goals.eloStart)
        store.update(GameGoals(elo = 3200, kd = 1.2), 2950)
        assertEquals(2900, store.goals.eloStart)
        store.update(GameGoals(kd = 1.2), 2950)
        assertNull(store.goals.eloStart)
    }

    @Test fun surveyStoreFlow() {
        val store = PlayerSurveyStore(null)
        assertTrue(store.needsOnboarding)
        store.dismissOnboarding()
        assertFalse(store.needsOnboarding)
        store.saveSurvey(PlayerSurvey(goals = listOf(com.pulse.core.model.FocusGoal.TILT)))
        assertFalse(store.addMindset(mapOf("q1" to 3)))
        val all3 = MindsetTest.questions.associate { it.id to 3 }
        assertTrue(store.addMindset(all3))
        assertTrue(store.addMindset(all3))
        assertTrue(store.isComplete)
        assertNotNull(store.previousProfile)
        store.reload(DemoUsers.nova)
        assertEquals(2, store.state.value.mindset.size)
    }
}

private object DemoUsers {
    val nova = com.pulse.core.auth.DemoAuth.accounts[0].user
}

class DashboardAndStaffDataTests {
    @BeforeTest fun zone() = pinTestZone()

    private fun match(daysAgo: Int, kills: Int, deaths: Int, won: Boolean, now: Instant) = GameMatch(
        Uuid.random(), now.addingDays(-daysAgo).setting(18, 0), CSMap.MIRAGE, won, if (won) 13 else 8, if (won) 8 else 13,
        kills, deaths, 3, 80.0, if (won) 25 else -25
    )

    @Test fun formIndex() {
        val full = assertNotNull(FormIndex.compute(480, 10.0, 10.0, 100.0, 0.0))
        assertEquals(100, full.score)
        assertEquals("Отличная форма", full.label)
        assertEquals(listOf("sleep", "energy", "cognitive", "mood", "fatigue"), full.components.map { it.id })
        assertEquals(50, FormIndex.compute(240, null, null, null, null)?.score)
        assertNull(FormIndex.compute(null, null, null, null, null))
        val tired = assertNotNull(FormIndex.compute(900, 2.0, 3.0, 40.0, 9.0))
        assertTrue(tired.components.all { it.value in 0.0..100.0 })
        assertEquals("Нужен отдых", tired.label)
    }

    @Test fun summaryAndInsight() {
        val now = Clock.System.now()
        val summary = GameSummary.of(listOf(match(0, 20, 10, true, now), match(1, 10, 20, false, now)))
        assertEquals(2, summary.matches)
        assertEquals(50.0, summary.winRate)
        assertEquals(1.0, summary.kd)
        assertEquals(0.0, GameSummary.of(emptyList()).kd)
        val matches = (0 until 6).map { if (it % 2 == 0) match(it, 24, 12, true, now) else match(it, 12, 16, false, now) }
        val insight = assertNotNull(GameInsight.sleepVersusKD(matches) { date -> if (date.startOfDay().daysUntil(now.startOfDay()) % 2 == 0) 480 else 360 })
        assertTrue(insight.isPositive)
        assertTrue(insight.text.contains("2,00"))
        assertNull(GameInsight.sleepVersusKD(matches.take(2)) { 480 })
    }

    @Test fun eloHistoryEndsAtCurrentEloAndProfileFallback() {
        val player = DemoData.players[0]
        val history = GameData.eloHistory(player, 14)
        assertEquals(14, history.size)
        assertEquals(player.elo, history.last().value.toInt())
        val matches = GameData.matches(player)
        assertEquals(matches.sortedByDescending { it.date }, matches)
        assertTrue(matches.all { it.date <= Clock.System.now() && (it.roundsWon == 13 || it.roundsLost == 13) })
        assertEquals(DemoData.players[0], GameData.profile(User(Uuid.random(), "Nobody", "n@x.gg", UserRole.SOLO_PLAYER, Tariff.FREE, null, 0)))
        assertEquals("Blitz", GameData.profile(User(Uuid.random(), "Blitz", "b@x.gg", UserRole.TEAM_PLAYER, Tariff.PRO, null, 0)).nickname)
    }

    @Test fun mealsAreAttributedAndSoloFoodStaysPrivate() {
        val now = Clock.System.now()
        val photoMeal = Meal(Uuid.random(), now, "Боул", 500, 30, 10, 60, Meal.encodePhoto(byteArrayOf(1, 2, 3)), MealSource.PHOTO, "Blitz")
        val legacy = Meal(Uuid.random(), now, "Овсянка", 300, 10, 5, 50, null, MealSource.MANUAL)
        val entries = TeamFood.entries(listOf(photoMeal, legacy))
        assertTrue(entries.any { it.player == "Blitz" && it.meal.photo != null })
        assertTrue(entries.any { it.player == DemoData.players[0].nickname && it.meal.name == "Овсянка" })
        assertEquals(1, entries.count { it.player == "Blitz" })
        assertTrue(entries.any { it.player == "Echo" })
        val soloMeal = Meal(Uuid.random(), now, "Пицца", 800, 30, 35, 90, Meal.encodePhoto(byteArrayOf(1)), MealSource.PHOTO, "Raven")
        assertFalse(TeamFood.entries(listOf(soloMeal)).any { it.player == "Raven" })
    }

    @Test fun staffNumbersAreConsistent() {
        assertEquals(DemoData.sleepHistory().map { it.durationMinutes }, DemoData.sleepSessions(DemoData.players[0]).map { it.durationMinutes })
        assertEquals(30, DemoData.sleepSessions(DemoData.players[1]).size)
        val player = DemoData.players[2]
        val matches = GameData.matches(player, 30)
        val stats = GameData.stats(player, 30)
        val summary = GameSummary.of(matches)
        assertEquals(matches.size, stats.matches)
        assertEquals(summary.wins, stats.wins)
        assertTrue(abs(stats.avgKD - summary.kd) < 0.0001)
        assertEquals(stats.rounds, stats.roundsWon + stats.roundsLost)
        val snapshot = TeamAnalytics.snapshot(DemoData.players[3], 7)
        assertTrue(abs(snapshot.readiness - (StatisticsMath.mean(TeamAnalytics.readiness(DemoData.players[3], 7).map { it.value }) ?: 0.0)) < 0.0001)
        val matrix = TeamAnalytics.matrix(DemoData.players)
        assertEquals(WellbeingFactor.entries.size, matrix.size)
        assertTrue(matrix.all { it.size == GameMetric.entries.size })
        assertTrue(matrix.flatten().filterNotNull().all { it in -1.0..1.0 })
        val line = assertNotNull(TeamAnalytics.trendLine((0 until 5).map { ScatterPoint("x", it.toDouble(), 2.0 * it + 1, true) }))
        assertTrue(abs(line.first - 2) < 1e-9 && abs(line.second - 1) < 1e-9)
        assertTrue(TeamAnalytics.teamReadinessTrend(14).all { it.low <= it.average && it.average <= it.high })
    }
}
