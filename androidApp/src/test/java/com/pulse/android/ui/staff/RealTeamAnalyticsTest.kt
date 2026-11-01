package com.pulse.android.ui.staff

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.pulse.android.ui.ProvideCore
import com.pulse.android.ui.StaffServerFixture
import com.pulse.android.ui.assertAccessible
import com.pulse.android.ui.assertTextFits
import com.pulse.android.ui.main.AppRouter
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.main.RouteScreen
import com.pulse.android.ui.remoteStaffCore
import com.pulse.android.ui.setMain
import com.pulse.android.ui.testCore
import com.pulse.android.ui.theme.Metrics
import com.pulse.core.PulseCore
import com.pulse.core.demo.DemoData
import com.pulse.core.feature.StaffMetrics
import com.pulse.core.model.GameMatch
import com.pulse.core.model.UserRole
import com.pulse.core.model.WeekRange
import com.pulse.core.staff.StaffTeamState
import com.pulse.core.staff.StaffViews
import com.pulse.core.util.Formatters
import com.pulse.core.util.roundToIntSwift
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** «Аналитика» (team, correlations, food) and the weekly team report of a real staff account: every number from the downloaded team. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RealTeamAnalyticsTest {
    @get:Rule val rule = createComposeRule()

    private val tz = TimeZone.currentSystemDefault().id
    private val orion = "11111111-1111-4111-8111-111111111111"
    private val mirage = "22222222-2222-4222-8222-222222222222"
    private val vector = "33333333-3333-4333-8333-333333333333"
    private val faceitId = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val omeletId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val saladId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val noData = "нет данных"
    private val websiteWords = listOf("на сайте", "кабинет", "/staff")
    private val now = Clock.System.now()
    private val today = StaffMetrics.dayOf(now, tz)

    private fun session(id: String, endAgo: Duration, length: Duration) =
        """{"id":"$id","start":"${now - endAgo - length}","end":"${now - endAgo}"}"""

    private fun meal(id: String, name: String, ago: Duration, kcal: Int, photo: Boolean) =
        """{"id":"$id","date":"${now - ago}","name":"$name","kcal":$kcal,"protein":30,"fat":20,"carbs":10,"source":"${if (photo) "photo" else "manual"}"${if (photo) ""","photoRef":true""" else ""}}"""

    private fun faceitMatch(kills: Int, deaths: Int, won: Boolean, ago: Duration) =
        """{"stats":{"Kills":"$kills","Deaths":"$deaths","Assists":"3","Result":"${if (won) 1 else 0}","Score":"13 / 7","Map":"de_mirage","ADR":"80","Match Finished At":${(now - ago).toEpochMilliseconds()}}}"""

    /** Sleep of 6..11 hours on six nights, a match each day whose K/D grows with that night's sleep: sleep → K/D is +1,00. */
    private val nights = (0..5).map { d -> d to 6 + d }

    private fun orionDocs(): Map<String, String> = mapOf(
        "sleep" to """{"sessions":[${nights.joinToString(",") { (d, h) -> session("n$d", d.days, h.hours) }}]}""",
        "wellbeing" to """{"mood":[{"id":"m1","day":"${today}T00:00:00Z","slot":"morning","mood":8,"energy":7}],"balance":[]}""",
        "cognitive" to """{"results":[{"id":"c1","kind":"reaction","date":"${now - 1.hours}","score":72,"metric":"x"}],"gameResults":[]}""",
        "nutrition" to """{"meals":[${meal(omeletId, "Омлет", 3.hours, 600, true)},${meal(saladId, "Салат", 5.hours, 300, false)}]}""",
        "faceit" to """{"nickname":"orion-fc","playerId":"$faceitId","snapshots":[{"date":"${now - 3.days}","elo":1900},{"date":"${now - 1.days}","elo":2010}]}"""
    )

    private val faceitItems = """{"items":[${nights.joinToString(",") { (d, h) -> faceitMatch(10 + 4 * (h - 6), 10, d % 2 == 0, d.days) }}]}"""

    private fun server(
        documents: Map<String, Map<String, String>> = mapOf(
            orion to orionDocs(),
            mirage to mapOf("sleep" to """{"sessions":[${session("s9", 0.hours, 5.hours)}]}""")
        ),
        photos: Map<String, ByteArray> = emptyMap(),
        faceit: Map<String, String> = mapOf(faceitId to faceitItems)
    ) = StaffServerFixture(
        inviteCode = "ABCDEF",
        players = linkedMapOf(orion to "Орион", mirage to "Мираж", vector to "Вектор"),
        documents = documents, photos = photos, faceitMatches = faceit
    )

    private fun team(core: PulseCore) = (core.staffTeam.state.value as StaffTeamState.Ready).team

    private fun matchesOf(core: PulseCore): Map<String, List<GameMatch>> =
        mapOf(orion to requireNotNull(runBlocking { core.game.matchesOf(faceitId) }) { "the fixture serves Faceit matches" })

    private fun waitForText(text: String, substring: Boolean = false) {
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithText(text, substring = substring, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForDescription(text: String) {
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithContentDescription(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertNoDemoPlayers() {
        DemoData.players.forEach { rule.onAllNodesWithText(it.nickname, substring = true).assertCountEquals(0) }
    }

    private fun assertNoWebsitePointer() {
        websiteWords.forEach { rule.onAllNodes(hasText(it, substring = true, ignoreCase = true)).assertCountEquals(0) }
    }

    private fun openRoute(core: PulseCore, route: Route) {
        rule.setContent { ProvideCore(core, AppRouter()) { RouteScreen(route) } }
    }

    private fun jpeg(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it)
    }.toByteArray()

    // ---------------------------------------------------------------------- «Команда»

    @Test fun realAnalyticsKpis() {
        val core = remoteStaffCore(server = server(), faceitKey = "app-key")
        val view = StaffViews.teamAnalytics(team(core), today, tz, 7, matchesOf(core))
        val readiness = requireNotNull(view.readiness.value) { "the fixture team has a readiness" }
        val winRate = requireNotNull(view.winRate.value) { "the fixture team has matches" }
        rule.setMain(core, "analytics")
        waitForText("${winRate.roundToIntSwift()}% · ${Formatters.plural(view.matches, "игра", "игры", "игр")}")
        rule.onNodeWithText("Готовность команды").assertExists()
        rule.onNodeWithText("${readiness.roundToInt()} / 100").assertExists()
        rule.onNodeWithText("Средний score").assertExists()
        assertTrue(rule.onAllNodesWithText("${requireNotNull(view.score.value).roundToInt()}").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Средний сон").assertExists()
        assertTrue(rule.onAllNodesWithText(Formatters.duration((requireNotNull(view.sleep.value) * 60).roundToInt())).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Win-rate команды").assertExists()
        assertNoDemoPlayers()
        assertNoWebsitePointer()
    }

    @Test fun realAnalyticsListsEveryPlayerWithNoDataWhereThereIsNone() {
        val core = remoteStaffCore(server = server(), faceitKey = "app-key")
        val view = StaffViews.teamAnalytics(team(core), today, tz, 7, matchesOf(core))
        rule.setMain(core, "analytics")
        waitForText("Готовность по игрокам")
        // The readiness bars and the table both list all three players, the one without any record too.
        listOf("Орион", "Мираж", "Вектор").forEach { name -> assertTrue(rule.onAllNodesWithText(name).fetchSemanticsNodes().size >= 2, "$name listed twice") }
        val gaps = view.table.sumOf { row -> listOf(row.readiness, row.score, row.sleepHours).count { it == null } }
        assertTrue(gaps >= 3, "the fixture has gaps")
        assertTrue(rule.onAllNodesWithText(noData, useUnmergedTree = true).fetchSemanticsNodes().size >= gaps, "«нет данных» for each gap")
        // Readiness is 0..100 with the status names of the roster.
        val best = requireNotNull(view.playerReadiness.first().readiness)
        assertTrue(rule.onAllNodesWithText("${best.roundToInt()}").fetchSemanticsNodes().isNotEmpty(), "best readiness")
        rule.onAllNodes(SemanticsMatcher("old 0..10 scale") { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.endsWith("/ 10") } == true }).assertCountEquals(0)
    }

    @Test fun realAnalyticsWithoutAKeyHasNoWinRate() {
        val server = server()
        val core = remoteStaffCore(server = server)
        rule.setMain(core, "analytics")
        waitForText("Win-rate команды")
        rule.waitForIdle()
        assertEquals(emptyList(), server.faceitRequests)
        rule.onAllNodesWithText("%", substring = true).assertCountEquals(0)
        assertTrue(rule.onAllNodesWithText(noData).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Готовность команды").assertExists()
    }

    @Test fun realAnalyticsKeepsTheRestWhenFaceitFails() {
        val server = server(faceit = emptyMap()) // the id is linked, Faceit knows nothing of it (404)
        val core = remoteStaffCore(server = server, faceitKey = "app-key")
        rule.setMain(core, "analytics")
        waitForText("Win-rate команды")
        rule.waitUntil(timeoutMillis = 10_000) { server.faceitRequests.isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithText("Готовность по игрокам", ignoreCase = true).assertExists()
        rule.onAllNodesWithText("%", substring = true).assertCountEquals(0)
    }

    @Test fun realAnalyticsPeriodSwitchComputesAgain() {
        val core = remoteStaffCore(server = server(), faceitKey = "app-key")
        val matches = matchesOf(core)
        val week = StaffViews.teamAnalytics(team(core), today, tz, 7, matches)
        val month = StaffViews.teamAnalytics(team(core), today, tz, 30, matches)
        rule.setMain(core, "analytics")
        waitForText("${requireNotNull(week.winRate.value).roundToIntSwift()}% · ${Formatters.plural(week.matches, "игра", "игры", "игр")}")
        rule.onNodeWithText("30 дней").performClick()
        waitForText("${requireNotNull(month.winRate.value).roundToIntSwift()}% · ${Formatters.plural(month.matches, "игра", "игры", "игр")}")
    }

    @Test fun realAnalyticsAsksFaceitOncePerPlayerForTheScreensLife() {
        val server = server()
        val core = remoteStaffCore(server = server, faceitKey = "app-key")
        rule.setMain(core, "analytics")
        waitForText("Win-rate команды")
        rule.waitUntil(timeoutMillis = 10_000) { server.faceitRequests.isNotEmpty() }
        rule.onNodeWithText("30 дней").performClick()
        rule.onNodeWithText("Корреляции").performClick()
        rule.waitForIdle()
        assertEquals(listOf("data/v4/players/$faceitId/games/cs2/stats"), server.faceitRequests)
    }

    @Test fun realAnalyticsShowsEloAndTheTable() {
        val core = remoteStaffCore(server = server(), faceitKey = "app-key")
        rule.setMain(core, "analytics")
        waitForText("ELO и K/D")
        rule.onNodeWithText("Таблица", ignoreCase = true).assertExists()
        listOf("Готовность", "Score", "Сон", "ELO").forEach { assertTrue(rule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty(), it) }
        // The current ELO of the latest snapshot of the document.
        waitForText("ELO ${Formatters.grouped(2010)}", substring = true)
    }

    @Test fun realAnalyticsShowsUpdatedAtOffline() {
        val server = server()
        val core = remoteStaffCore(server = server)
        server.offline = true
        runBlocking { core.staffTeam.refresh() }
        rule.setMain(core, "analytics")
        waitForText("Обновлено ${Formatters.time(team(core).fetchedAt)}")
        waitForText("Готовность по игрокам")
        listOf("Орион", "Мираж", "Вектор").forEach { name -> assertTrue(rule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty(), name) }
    }

    // ---------------------------------------------------------------------- «Корреляции»

    @Test fun realCorrelationsFromStatistics() {
        val core = remoteStaffCore(server = server(), faceitKey = "app-key")
        val correlations = StaffViews.teamCorrelations(team(core), matchesOf(core), today, tz, 30)
        val scatter = correlations.sleepKd
        assertEquals(6, scatter.points.size)
        val sleepKd = correlations.rows.first { it.factor.id == "sleep" }.cells[correlations.games.indexOfFirst { it.id == "kd" }]
        assertEquals(1.0, requireNotNull(sleepKd.r), 1e-9)
        rule.setMain(core, "analytics")
        waitForText("Корреляции")
        rule.onNodeWithText("Корреляции").performClick()
        waitForText("Карта связей")
        // The matrix: every factor and game metric of the statistics page, the coefficient of sleep and K/D.
        correlations.factors.forEach { assertTrue(rule.onAllNodesWithText(it.label).fetchSemanticsNodes().isNotEmpty(), it.label) }
        correlations.games.forEach { assertTrue(rule.onAllNodesWithText(it.label).fetchSemanticsNodes().isNotEmpty(), it.label) }
        assertTrue(rule.onAllNodesWithText(signedCoefficient(1.0)).fetchSemanticsNodes().isNotEmpty())
        // «Сон → K/D»: one dot per match, Pearson over them.
        rule.onNodeWithText("Сон → K/D").assertExists()
        rule.onNodeWithText("r = ${signedCoefficient(requireNotNull(scatter.r))} · ${Formatters.plural(scatter.points.size, "матч", "матча", "матчей")}").assertExists()
        // The strongest links.
        val link = correlations.links.first()
        assertTrue(rule.onAllNodesWithText("${link.a.label} → ${link.b.label}").fetchSemanticsNodes().isNotEmpty())
        assertNoDemoPlayers()
        assertNoWebsitePointer()
    }

    @Test fun realCorrelationsNarrowToOnePlayer() {
        val core = remoteStaffCore(server = server(), faceitKey = "app-key")
        val onlyMirage = StaffViews.teamCorrelations(team(core), matchesOf(core), today, tz, 30, only = mirage)
        assertEquals(0, onlyMirage.sleepKd.points.size)
        rule.setMain(core, "analytics")
        rule.onNodeWithText("Корреляции").performClick()
        waitForText("Карта связей")
        rule.onNodeWithText("Вся команда").assertExists()
        rule.onNodeWithText("Мираж").performClick()
        waitForText("0 матчей", substring = true)
        rule.onAllNodesWithText("r = ${signedCoefficient(1.0)}", substring = true).assertCountEquals(0)
    }

    @Test fun realCorrelationsWithoutGamesSayNoData() {
        val core = remoteStaffCore(server = server(), faceitKey = null)
        rule.setMain(core, "analytics")
        rule.onNodeWithText("Корреляции").performClick()
        waitForText("Карта связей")
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText(noData).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("r = $noData", substring = true).assertExists()
    }

    // ---------------------------------------------------------------------- «Питание игроков»

    @Test fun realTeamFoodWithPhotos() {
        val server = server(photos = mapOf("$orion/$omeletId" to jpeg()))
        val core = remoteStaffCore(server = server)
        val food = StaffViews.teamFood(team(core), today, tz, 7)
        assertEquals(2, food.size)
        openRoute(core, Route.TeamFood)
        waitForDescription("Фото: Омлет")
        rule.onNodeWithText("Омлет").assertExists()
        rule.onNodeWithText("Салат").assertExists()
        rule.onAllNodesWithContentDescription("Фото: Салат").assertCountEquals(0)
        assertEquals(listOf("staff/players/$orion/photos/$omeletId"), server.requests.filter { "/photos/" in it })
        // The count line, the player the meal belongs to, its calories.
        rule.onNodeWithText("2 записи · 1 с фото", substring = true).assertExists()
        assertTrue(rule.onAllNodesWithText("Орион").fetchSemanticsNodes().size >= 3)
        rule.onNodeWithText("600 ккал", substring = true).assertExists()
        assertNoDemoPlayers()
        assertNoWebsitePointer()
    }

    @Test fun realTeamFoodFiltersByPlayerAndPhoto() {
        val server = server(photos = mapOf("$orion/$omeletId" to jpeg()))
        openRoute(remoteStaffCore(server = server), Route.TeamFood)
        waitForText("Омлет")
        rule.onNodeWithText("Только с фото").performClick()
        rule.onAllNodesWithText("Салат").assertCountEquals(0)
        rule.onNodeWithText("Омлет").assertExists()
        rule.onNodeWithText("Только с фото").performClick()
        rule.onNodeWithText("Мираж").performClick()
        rule.onAllNodesWithText("Омлет").assertCountEquals(0)
        rule.onNodeWithText("Нет записей", substring = true).assertExists()
    }

    @Test fun realTeamFoodOpensTheMealSheet() {
        val server = server(photos = mapOf("$orion/$omeletId" to jpeg()))
        openRoute(remoteStaffCore(server = server), Route.TeamFood)
        waitForDescription("Фото: Омлет")
        rule.onNodeWithText("Омлет").performClick()
        waitForText("Белки")
        rule.onNodeWithText("Ккал").assertExists()
        rule.onNodeWithText("AI-анализ фото", substring = true).assertExists()
    }

    @Test fun realTeamFoodIsCappedAt30Meals() {
        val meals = (0 until 40).map { i -> meal("%08d-0000-4000-8000-000000000000".format(i), "Обед $i", (i * 3).minutes, 500, false) }
        val docs = mapOf(vector to mapOf("nutrition" to """{"meals":[${meals.joinToString(",")}]}"""))
        openRoute(remoteStaffCore(server = server(documents = docs)), Route.TeamFood)
        waitForText("Обед", substring = true)
        rule.onAllNodes(hasText("Обед", substring = true)).assertCountEquals(30)
        rule.onNodeWithText("40 записей", substring = true).assertExists()
    }

    @Test fun realTeamFoodWithoutMealsSaysSo() {
        val docs = mapOf(orion to mapOf("sleep" to """{"sessions":[]}"""))
        openRoute(remoteStaffCore(server = server(documents = docs)), Route.TeamFood)
        waitForText("Записей о питании пока нет")
        assertNoDemoPlayers()
    }

    @Test fun foodSegmentOfThePlayersTabShowsRealMeals() {
        rule.setMain(remoteStaffCore(server = server()), "players")
        waitForText("Питание")
        rule.onNodeWithText("Питание").performClick()
        waitForText("Омлет")
        rule.onNodeWithText("Салат").assertExists()
        assertNoDemoPlayers()
    }

    // ---------------------------------------------------------------------- «Сводка команды»

    @Test fun realWeeklyTeamReport() {
        val core = remoteStaffCore(server = server())
        val week = WeekRange.containing(now)
        val report = StaffViews.weeklyTeam(team(core), week, tz)
        val readiness = requireNotNull(report.readiness) { "the fixture team has a readiness" }
        rule.setMain(core, "weekly")
        waitForText("Готовность")
        rule.onNodeWithText("Сводка команды").assertExists()
        rule.onNodeWithText(week.title).assertExists()
        rule.onNodeWithText("${readiness.roundToInt()} / 100").assertExists()
        rule.onNodeWithText("Средний сон").assertExists()
        rule.onNodeWithText("В зоне риска").assertExists()
        rule.onNodeWithText("${report.atRisk} из ${report.rows.size}").assertExists()
        // Every player of the team has a row, the best readiness first.
        report.rows.forEach { row -> assertTrue(rule.onAllNodesWithText(row.nickname, substring = true).fetchSemanticsNodes().isNotEmpty(), row.nickname) }
        // The players with risks are listed with the reasons the core gave.
        report.rows.filter { it.risks.isNotEmpty() }.forEach { row ->
            assertTrue(rule.onAllNodesWithText(row.risks.joinToString(", "), substring = true).fetchSemanticsNodes().isNotEmpty(), row.nickname)
        }
        assertNoDemoPlayers()
        assertNoWebsitePointer()
        rule.onAllNodesWithText("Демо", substring = true).assertCountEquals(0)
    }

    @Test fun realWeeklyReportNavigatesBetweenWeeks() {
        val core = remoteStaffCore(server = server())
        rule.setMain(core, "weekly")
        val week = WeekRange.containing(now)
        waitForText(week.title)
        rule.onNodeWithText("Текущая неделя").assertExists()
        rule.onNodeWithContentDescription("Предыдущая неделя").performClick()
        waitForText(week.previous.title)
        rule.onNodeWithText("Завершённая неделя").assertExists()
        // A week without records: every player is still listed, with «нет данных».
        waitForText("Орион", substring = true)
        assertTrue(rule.onAllNodesWithText(noData).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun weeklyCardSubtitleFromRealData() {
        val core = remoteStaffCore(server = server())
        val week = WeekRange.containing(now)
        val report = StaffViews.weeklyTeam(team(core), week, tz)
        val readiness = requireNotNull(report.readiness)
        rule.setMain(core)
        waitForText("${week.title} · готовность ${readiness.roundToInt()} · рисков: ${report.atRisk}")
        rule.onNodeWithText("Сводка команды за неделю").assertExists()
    }

    @Test fun weeklyCardOfATeamWithoutRecordsSaysNoData() {
        val core = remoteStaffCore(server = server(documents = emptyMap()))
        rule.setMain(core)
        waitForText("${WeekRange.containing(now).title} · нет данных")
        rule.onAllNodes(hasText("рисков", substring = true)).assertCountEquals(0)
    }

    // ---------------------------------------------------------------------- everything together

    private fun noDemoNamesOn(route: Route) {
        val server = server(photos = mapOf("$orion/$omeletId" to jpeg()))
        openRoute(remoteStaffCore(server = server, faceitKey = "app-key"), route)
        waitForText("Орион", substring = true)
        rule.waitForIdle()
        assertNoDemoPlayers()
        assertNoWebsitePointer()
        rule.onAllNodesWithText("Пока нет данных").assertCountEquals(0)
    }

    @Test fun noDemoNamesOnAnalytics() = noDemoNamesOn(Route.TeamAnalytics)
    @Test fun noDemoNamesOnCorrelations() = noDemoNamesOn(Route.TeamCorrelations)
    @Test fun noDemoNamesOnTeamFood() = noDemoNamesOn(Route.TeamFood)
    @Test fun noDemoNamesOnTheWeeklyReport() = noDemoNamesOn(Route.WeeklyReport)

    @Test fun anEmptyTeamKeepsTheNoDataState() {
        openRoute(remoteStaffCore(), Route.TeamAnalytics)
        rule.onNodeWithText("Пока нет данных").assertExists()
        assertNoDemoPlayers()
    }

    @Test fun demoStaffKeepsTheDemoScreens() {
        rule.setMain(testCore(UserRole.STAFF), "analytics")
        rule.onAllNodesWithText(noData).assertCountEquals(0)
        rule.onNodeWithText("Готовность команды").assertExists()
        assertTrue(rule.onAllNodesWithText("/ 10", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    private fun fitsAtTheLargestFont(route: Route) {
        val core = remoteStaffCore(server = server(photos = mapOf("$orion/$omeletId" to jpeg())), faceitKey = "app-key")
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, Metrics.maxFontScale)) {
                ProvideCore(core, AppRouter()) { RouteScreen(route) }
            }
        }
        waitForText("Орион", substring = true)
        rule.waitForIdle()
        rule.assertTextFits()
        rule.assertAccessible()
    }

    @Test fun analyticsFitsAtTheLargestFontAndIsAccessible() = fitsAtTheLargestFont(Route.TeamAnalytics)
    @Test fun correlationsFitAtTheLargestFontAndAreAccessible() = fitsAtTheLargestFont(Route.TeamCorrelations)
    @Test fun teamFoodFitsAtTheLargestFontAndIsAccessible() = fitsAtTheLargestFont(Route.TeamFood)
    @Test fun weeklyReportFitsAtTheLargestFontAndIsAccessible() = fitsAtTheLargestFont(Route.WeeklyReport)
}
