package com.pulse.android.ui.staff

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.pulse.core.model.UserRole
import com.pulse.core.staff.StaffTeamState
import com.pulse.core.staff.StaffViews
import com.pulse.core.util.Formatters
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** The staff card of a player of a real team: every section from the downloaded documents, Faceit from the app's own client. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RealPlayerCardTest {
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

    private fun session(id: String, endAgo: kotlin.time.Duration, length: kotlin.time.Duration) =
        """{"id":"$id","start":"${now - endAgo - length}","end":"${now - endAgo}"}"""

    private fun cognitive(id: String, kind: String, ago: kotlin.time.Duration, score: Int) =
        """{"id":"$id","kind":"$kind","date":"${now - ago}","score":$score,"metric":"x"}"""

    private fun meal(id: String, name: String, ago: kotlin.time.Duration, kcal: Int, photo: Boolean) =
        """{"id":"$id","date":"${now - ago}","name":"$name","kcal":$kcal,"protein":30,"fat":20,"carbs":10,"source":"${if (photo) "photo" else "manual"}"${if (photo) ""","photoRef":true""" else ""}}"""

    private fun faceitMatch(map: String, kills: Int, deaths: Int, won: Boolean, ago: kotlin.time.Duration) =
        """{"stats":{"Kills":"$kills","Deaths":"$deaths","Assists":"3","Result":"${if (won) 1 else 0}","Score":"${if (won) "13 / 7" else "5 / 13"}","Map":"$map","ADR":"80","Match Finished At":${(now - ago).toEpochMilliseconds()}}}"""

    /** A player with everything the card draws. */
    private fun fullDocs(): Map<String, String> {
        val day = StaffMetrics.dayOf(now, tz)
        return mapOf(
            "sleep" to """{"sessions":[${session("s1", 0.hours, 8.hours)},${session("s2", 24.hours, 5.hours)},${session("s3", 48.hours, 7.hours + 30.minutes)}]}""",
            "wellbeing" to """{"mood":[{"id":"m1","day":"${day}T00:00:00Z","slot":"morning","mood":8,"energy":7}],"balance":[{"id":"b1","day":"${day}T00:00:00Z","values":{"physical":8,"emotional":6}}]}""",
            "cognitive" to """{"results":[${cognitive("c1", "reaction", 1.hours, 72)}],"gameResults":[{"id":"g1","date":"${now - 2.hours}","phase":"beforeTraining","focus":9,"fatigue":3,"readiness":7}]}""",
            "nutrition" to """{"meals":[${meal(omeletId, "Омлет", 3.hours, 600, true)},${meal(saladId, "Салат", 5.hours, 300, false)}]}""",
            "faceit" to """{"nickname":"orion-fc","playerId":"$faceitId","snapshots":[]}""",
            "player-survey" to """{"mindset":[{"id":"r1","date":"${now - 3.days}","scores":{"composure":90,"resilience":60,"focus":60,"teamwork":60,"leadership":60,"drive":60}}]}"""
        )
    }

    private fun server(
        documents: Map<String, Map<String, String>> = mapOf(orion to fullDocs()),
        photos: Map<String, ByteArray> = emptyMap(),
        faceit: Map<String, String> = emptyMap()
    ) = StaffServerFixture(
        inviteCode = "ABCDEF",
        players = linkedMapOf(orion to "Орион", mirage to "Мираж", vector to "Вектор"),
        documents = documents, photos = photos, faceitMatches = faceit
    )

    private fun team(core: PulseCore) = (core.staffTeam.state.value as StaffTeamState.Ready).team

    private fun open(core: PulseCore, id: String) {
        rule.setContent { ProvideCore(core, AppRouter()) { RouteScreen(Route.Player(Uuid.parse(id))) } }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
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

    private fun jpeg(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it)
    }.toByteArray()

    private fun minutesOf(hours: Double) = (hours * 60).roundToInt()

    @Test fun cardShowsRealSleepAndTests() {
        val core = remoteStaffCore(server = server())
        val card = requireNotNull(StaffViews.playerCard(team(core), orion, StaffMetrics.dayOf(now, tz), tz, 30))
        open(core, orion)
        waitForText("Дневник сна")
        rule.onAllNodesWithText("Орион").assertCountEquals(2) // the header and the profile row
        // Sleep: every night of the document with its hours.
        assertEquals(3, card.sleep.rows.size)
        card.sleep.rows.forEach { night ->
            rule.onAllNodesWithText(Formatters.duration(minutesOf(night.hours)), substring = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "night ${night.day}") }
        }
        rule.onNodeWithText(Formatters.duration(minutesOf(requireNotNull(card.sleep.averageHours))), substring = true).assertExists()
        // Tests: the cognitive test and the before-training test of the document.
        rule.onAllNodesWithText("Скорость реакции").assertCountEquals(1)
        rule.onAllNodesWithText("До тренировки").assertCountEquals(1)
        rule.onAllNodesWithText("72", substring = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        // KPIs from the same card.
        rule.onNodeWithText("Средний score").assertExists()
        rule.onNodeWithText("Индекс состояния").assertExists()
        val focus = card.kpis.focusHigh
        rule.onAllNodesWithText("${focus.days} дн.").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        assertNoDemoPlayers()
        assertNoWebsitePointer()
    }

    @Test fun cardShowsArchetypeFoodWheelAndNoDataForLoad() {
        open(remoteStaffCore(server = server()), orion)
        waitForText("Дневник сна")
        rule.onNodeWithText("Профиль игрока", ignoreCase = true).assertExists()
        assertTrue(rule.onAllNodesWithText("Хладнокровие", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Как работать с игроком", ignoreCase = true).assertExists()
        rule.onNodeWithText("Омлет").assertExists()
        rule.onNodeWithText("Салат").assertExists()
        rule.onNodeWithText("Колесо баланса", ignoreCase = true).assertExists()
        rule.onNodeWithText("Тренировочная нагрузка", ignoreCase = true).assertExists()
        // No workouts rated: the load is «нет данных», not a zero.
        rule.onAllNodesWithText("ACWR").assertCountEquals(0)
        rule.onAllNodesWithText(noData, substring = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    @Test fun cardFoodPhotoLoadsFromServer() {
        val server = server(photos = mapOf("$orion/$omeletId" to jpeg()))
        open(remoteStaffCore(server = server), orion)
        waitForDescription("Фото: Омлет")
        assertEquals(listOf("staff/players/$orion/photos/$omeletId"), server.requests.filter { "/photos/" in it })
        rule.onAllNodesWithContentDescription("Фото: Салат").assertCountEquals(0)
    }

    @Test fun cardFoodWithoutAStoredPhotoKeepsThePlaceholder() {
        val server = server() // the meal says it has a photo, the server has none (404)
        open(remoteStaffCore(server = server), orion)
        waitForText("Омлет")
        rule.waitUntil(timeoutMillis = 10_000) { server.requests.any { "/photos/" in it } }
        rule.waitForIdle()
        rule.onNodeWithText("Омлет").assertExists()
        rule.onAllNodesWithContentDescription("Фото: Омлет").assertCountEquals(0)
    }

    @Test fun cardGameUsesFaceitMatches() {
        val items = """{"items":[${faceitMatch("de_mirage", 24, 12, true, 1.days)},${faceitMatch("de_inferno", 9, 18, false, 2.days)},${faceitMatch("de_nuke", 30, 5, true, 60.days)}]}"""
        val server = server(faceit = mapOf(faceitId to items))
        open(remoteStaffCore(server = server, faceitKey = "app-key"), orion)
        waitForText("Mirage")
        rule.onNodeWithText("Mirage", substring = true).assertExists()
        rule.onNodeWithText("Inferno", substring = true).assertExists()
        rule.onAllNodesWithText("Nuke", substring = true).assertCountEquals(0) // 60 days ago: outside the 30-day period
        assertEquals(listOf("data/v4/players/$faceitId/games/cs2/stats"), server.faceitRequests)
        // Two matches in the period: one win.
        rule.onNodeWithText("50%").assertExists()
        rule.onNodeWithText("Игровая статистика · 30 дней", ignoreCase = true).assertExists()
    }

    @Test fun cardGameFollowsThePeriod() {
        val items = """{"items":[${faceitMatch("de_mirage", 24, 12, true, 1.days)},${faceitMatch("de_inferno", 9, 18, false, 10.days)}]}"""
        val server = server(faceit = mapOf(faceitId to items))
        open(remoteStaffCore(server = server, faceitKey = "app-key"), orion)
        waitForText("Inferno")
        rule.onNodeWithText("7 дней").performClick()
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithText("Inferno", substring = true).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Mirage", substring = true).assertExists()
        assertEquals(1, server.faceitRequests.size, "the period is a filter: Faceit is not asked again")
    }

    @Test fun cardCorrelationsLinkSleepToKd() {
        val hours = listOf(6, 7, 8, 9, 10)
        val sleeps = hours.mapIndexed { d, h -> session("n$d", d.days, h.hours) }
        val matches = hours.mapIndexed { d, h -> faceitMatch("de_mirage", 10 + 4 * (h - 6), 10, true, d.days) }
        val docs = mapOf("sleep" to """{"sessions":[${sleeps.joinToString(",")}]}""", "faceit" to """{"playerId":"$faceitId"}""")
        val server = server(documents = mapOf(orion to docs), faceit = mapOf(faceitId to """{"items":[${matches.joinToString(",")}]}"""))
        open(remoteStaffCore(server = server, faceitKey = "app-key"), orion)
        waitForText("Сон → K/D")
        rule.onNodeWithText("Сон → K/D").assertExists()
        rule.onNodeWithText("+1,00").assertExists()
    }

    @Test fun cardWithoutFaceitSaysNoData() {
        val server = server(documents = mapOf(mirage to fullDocs().filterKeys { it != "faceit" }))
        open(remoteStaffCore(server = server, faceitKey = "app-key"), mirage)
        waitForText("Последние матчи")
        rule.waitForIdle()
        assertEquals(emptyList(), server.faceitRequests)
        rule.onNodeWithText("Игровая статистика · 30 дней", ignoreCase = true).assertExists()
        rule.onAllNodesWithText(noData).fetchSemanticsNodes().let { assertTrue(it.size >= 2, "matches and statistics say «нет данных»") }
        rule.onAllNodesWithText("Mirage", substring = true).assertCountEquals(0)
    }

    @Test fun cardWhenFaceitFailsSaysNoDataAndKeepsTheRest() {
        val server = server() // the id is linked, Faceit knows nothing of it (404)
        open(remoteStaffCore(server = server, faceitKey = "app-key"), orion)
        waitForText("Дневник сна")
        rule.waitUntil(timeoutMillis = 10_000) { server.faceitRequests.isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithText("Омлет").assertExists()
        rule.onAllNodes(hasText(noData)).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    @Test fun cardWithoutAKeyNeverAsksFaceit() {
        val server = server(faceit = mapOf(faceitId to """{"items":[]}"""))
        open(remoteStaffCore(server = server), orion)
        waitForText("Последние матчи")
        rule.waitForIdle()
        assertEquals(emptyList(), server.faceitRequests)
    }

    @Test fun demoIdOnRealAccountIsNotFound() {
        val core = remoteStaffCore(server = server())
        open(core, DemoData.players[0].id.toString())
        rule.onNodeWithText("Игрок не найден").assertExists()
        assertNoDemoPlayers()
        assertNoWebsitePointer()
        rule.onAllNodesWithText("Дневник сна", ignoreCase = true).assertCountEquals(0)
    }

    @Test fun anIdOfNoOneInTheTeamIsNotFoundEvenInAnEmptyTeam() {
        open(remoteStaffCore(), orion)
        rule.onNodeWithText("Игрок не найден").assertExists()
    }

    @Test fun aPlayerWithoutAnyDataStillHasACardWithNoData() {
        open(remoteStaffCore(server = server()), vector)
        waitForText("Дневник сна")
        rule.onAllNodesWithText("Вектор").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        rule.onAllNodesWithText(noData, substring = true).fetchSemanticsNodes().let { assertTrue(it.size >= 6) }
        rule.onAllNodesWithText("0 дн.").assertCountEquals(0)
    }

    @Test fun cardListsAreCappedLikeTheWebsite() {
        val results = (0 until 12).flatMap { d -> (0 until 3).map { k -> cognitive("c$d-$k", "stroop", d.days + k.hours, 50 + k) } }
        val nights = (0 until 14).map { d -> session("n$d", d.days, 8.hours) }
        val meals = (0 until 40).map { i -> meal("%08d-0000-4000-8000-000000000000".format(i), "Обед $i", (i * 10).minutes, 500, false) }
        val docs = mapOf(
            "cognitive" to """{"results":[${results.joinToString(",")}],"gameResults":[]}""",
            "sleep" to """{"sessions":[${nights.joinToString(",")}]}""",
            "nutrition" to """{"meals":[${meals.joinToString(",")}]}"""
        )
        open(remoteStaffCore(server = server(documents = mapOf(vector to docs))), vector)
        waitForText("Дневник сна")
        rule.onAllNodesWithText("Тест Струпа", substring = true).assertCountEquals(30)
        rule.onAllNodes(hasText("→", substring = true)).assertCountEquals(10)
        rule.onAllNodes(hasText("Обед", substring = true)).assertCountEquals(30)
    }

    @Test fun testTypeFilterSeparatesCognitiveFromGameTests() {
        open(remoteStaffCore(server = server()), orion)
        waitForText("Дневник сна")
        rule.onNodeWithText("Все тесты").performClick()
        rule.onNodeWithText("Игровые").performClick()
        rule.onAllNodesWithText("Скорость реакции").assertCountEquals(0)
        rule.onAllNodesWithText("До тренировки").assertCountEquals(1)
    }

    @Test fun testTypeOffersNoMoodOptionOnARealCard() {
        open(remoteStaffCore(server = server()), orion)
        waitForText("Дневник сна")
        rule.onNodeWithText("Все тесты").performClick()
        listOf("Все тесты", "Когнитивные", "Игровые").forEach { assertTrue(rule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty(), it) }
        rule.onAllNodesWithText("Настроение").assertCountEquals(0)
    }

    @Test fun demoCardKeepsTheMoodTestOption() {
        rule.setMain(testCore(UserRole.STAFF), "player")
        rule.onNodeWithText("Все тесты").performClick()
        rule.onNodeWithText("Настроение").assertExists()
    }

    @Test fun gameSourceOffersOnlyAllAndFaceit() {
        open(remoteStaffCore(server = server()), orion)
        waitForText("Дневник сна")
        rule.onNodeWithText("Источник: Все").performClick()
        rule.onNodeWithText("Faceit").assertExists()
        listOf("Приложение", "Уведомления", "Веб", "Пракки").forEach { rule.onAllNodesWithText(it).assertCountEquals(0) }
    }

    @Test fun demoStaffKeepsTheDemoCard() {
        rule.setMain(testCore(UserRole.STAFF), "player")
        rule.onAllNodesWithText(DemoData.players[0].nickname, substring = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        rule.onNodeWithText("Игрок не найден").assertDoesNotExist()
        rule.onNodeWithText("Средний score").assertExists()
    }

    @Test fun cardFitsAtTheLargestFontAndIsAccessible() {
        val items = """{"items":[${faceitMatch("de_mirage", 24, 12, true, 1.days)}]}"""
        val core = remoteStaffCore(server = server(photos = mapOf("$orion/$omeletId" to jpeg()), faceit = mapOf(faceitId to items)), faceitKey = "app-key")
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, Metrics.maxFontScale)) {
                ProvideCore(core, AppRouter()) { RouteScreen(Route.Player(Uuid.parse(orion))) }
            }
        }
        waitForText("Mirage")
        waitForDescription("Фото: Омлет")
        rule.waitForIdle()
        rule.assertTextFits()
        rule.assertAccessible()
    }
}
