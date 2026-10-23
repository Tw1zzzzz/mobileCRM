package com.pulse.android

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import com.pulse.android.ui.theme.Metrics

/**
 * Follows the system font scale up to [Metrics.maxFontScale] — for the activity and every sheet, dialog and menu it
 * opens (they take their density from this context). Capping the configuration, not Compose's LocalDensity, keeps
 * Android 14's non-linear scaling of large text.
 */
open class FontCappedActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        if (newBase.resources.configuration.fontScale > Metrics.maxFontScale) {
            applyOverrideConfiguration(Configuration().apply { fontScale = Metrics.maxFontScale })
        }
    }
}
