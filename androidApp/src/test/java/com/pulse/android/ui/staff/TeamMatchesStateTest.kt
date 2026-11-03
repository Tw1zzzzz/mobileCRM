package com.pulse.android.ui.staff

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.pulse.core.model.GameMatch
import com.pulse.core.staff.StaffPlayer
import com.pulse.core.staff.TeamSnapshot
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.time.Clock

/** The team's Faceit matches on a screen: a player Faceit failed for is asked again when the screen is pulled to refresh. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TeamMatchesStateTest {
    @get:Rule val rule = createComposeRule()

    private val team = TeamSnapshot(
        "T", null, listOf(StaffPlayer("p", "P")),
        mapOf("p" to mapOf("faceit" to Json.parseToJsonElement("""{"playerId":"f"}"""))), Clock.System.now()
    )

    private fun waitForText(text: String) {
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun pullToRefreshAsksAFailedPlayerAgainEvenWhenTheTeamIsTheSame() {
        val answer = java.util.concurrent.atomic.AtomicReference<List<GameMatch>?>(null)
        val asked = AtomicInteger()
        val matches = TeamMatches({ asked.incrementAndGet(); answer.get() })
        rule.setContent {
            val state by rememberTeamMatchesState(matches, team)
            Text(state?.let { faceitFailedText(it.failed.size) ?: "ok" } ?: "loading")
        }
        waitForText("Faceit не ответил для 1 игрока")
        assertEquals(1, asked.get())

        answer.set(emptyList())
        matches.refresh()

        waitForText("ok")
        assertEquals(2, asked.get())
    }
}
