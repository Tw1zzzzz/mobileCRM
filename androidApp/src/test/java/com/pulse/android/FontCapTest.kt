package com.pulse.android

import android.app.Application
import android.content.ComponentName
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.theme.Metrics
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

class ProbeActivity : FontCappedActivity()

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], fontScale = 2.0f)
class FontCapTest {
    // The compose rule, not a bare activity + idleMainLooper(): an open sheet's Popup polls its window position as an
    // infinite animation, so a plain looper never goes idle (the test spun for minutes and filled the heap). The rule's
    // infinite-animation policy parks that poll.
    private val rule = createAndroidComposeRule<ProbeActivity>()

    /** ProbeActivity exists only in tests, so it is registered with Robolectric's package manager before launch. */
    private val registerProbe = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ProbeActivity::class.java))
                base.evaluate()
            }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(registerProbe).around(rule)

    @Test fun capReachesScreensAndSheets() {
        var screen = 0f
        var sheet = 0f
        rule.setContent {
            screen = LocalDensity.current.fontScale
            PulseSheet(onDismiss = {}) {
                sheet = LocalDensity.current.fontScale
                Text("Окно сна")
            }
        }
        rule.waitForIdle()
        assertEquals(Metrics.maxFontScale, screen, 0.01f, "screen")
        assertEquals(Metrics.maxFontScale, sheet, 0.01f, "sheet")
    }
}
