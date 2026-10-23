package com.pulse.android

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.theme.Metrics
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals

class ProbeActivity : FontCappedActivity()

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], fontScale = 2.0f)
class FontCapTest {
    @Test fun capReachesScreensAndSheets() {
        var screen = 0f
        var sheet = 0f
        val activity = Robolectric.buildActivity(ProbeActivity::class.java).setup().get()
        activity.setContent {
            screen = LocalDensity.current.fontScale
            PulseSheet(onDismiss = {}) {
                sheet = LocalDensity.current.fontScale
                Text("Окно сна")
            }
        }
        ShadowLooper.idleMainLooper()
        assertEquals(Metrics.maxFontScale, screen, 0.01f, "screen")
        assertEquals(Metrics.maxFontScale, sheet, 0.01f, "sheet")
    }
}
