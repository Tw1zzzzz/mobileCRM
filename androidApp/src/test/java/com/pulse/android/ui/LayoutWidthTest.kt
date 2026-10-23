package com.pulse.android.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.pulse.core.model.UserRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** On tablets and unfolded phones (where Android 16 ignores the portrait lock) content stays a readable column. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LayoutWidthTest {
    @get:Rule val rule = createComposeRule()

    /** (content width, content centre, window width) in dp. */
    private fun measure(): Triple<Float, Float, Float> {
        rule.setMain(testCore(UserRole.TEAM_PLAYER), "today")
        val content = rule.onNodeWithTag("screenContent").fetchSemanticsNode()
        val root = rule.onRoot().fetchSemanticsNode()
        val density = root.layoutInfo.density.density
        val bounds = content.boundsInRoot
        return Triple(bounds.width / density, bounds.center.x / density, root.boundsInRoot.width / density)
    }

    @Test @Config(qualifiers = "w1000dp-h700dp-land")
    fun contentIsCappedOnWideScreens() {
        val (width, centre, window) = measure()
        assertTrue(width <= 600f, "content is ${width}dp wide on a ${window}dp window")
        assertTrue(abs(centre - window / 2) <= 1f, "content centre ${centre}dp, window centre ${window / 2}dp")
    }

    @Test @Config(qualifiers = "w360dp-h740dp")
    fun phoneUsesFullWidth() {
        val (width, _, window) = measure()
        assertEquals(window, width, 1f)
    }
}
