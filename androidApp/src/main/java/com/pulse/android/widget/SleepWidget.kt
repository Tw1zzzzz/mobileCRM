package com.pulse.android.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.pulse.android.MainActivity
import com.pulse.android.PulseApplication
import com.pulse.android.platform.PulseNotifications
import com.pulse.android.platform.SleepActionReceiver
import com.pulse.android.platform.SleepActions
import com.pulse.core.util.Formatters
import kotlin.time.Clock

/** Home-screen widget «Сон»: last night or the running session, with «Начать сон» / «Я проснулся». */
class SleepWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val core = (context.applicationContext as PulseApplication).core
        // A Glance session lives on after provideGlance: read the stores inside the composition so updates show up.
        provideContent {
            val user by core.session.user.collectAsState()
            val sleep by core.sleep.state.collectAsState()
            val start = sleep.activeStart
            val night = core.sleep.displayNight(Clock.System.now())
            Content(context, user != null, start?.let { "Сон с ${Formatters.time(it)}" },
                night?.let { "${Formatters.duration(it.totalMinutes)} · оценка ${it.score}" }, start != null)
        }
    }

    @Composable
    private fun Content(context: Context, signedIn: Boolean, tracking: String?, lastNight: String?, isSleeping: Boolean) {
        val open = Intent(context, MainActivity::class.java).putExtra(PulseNotifications.EXTRA_OPEN, "sleep")
        Column(
            GlanceModifier.fillMaxSize().background(BACKGROUND).cornerRadius(22.dp).padding(14.dp).clickable(actionStartActivity(open)),
            verticalAlignment = Alignment.Vertical.Top
        ) {
            Text("СОН", style = TextStyle(color = ColorProvider(CYAN), fontSize = 12.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(6.dp))
            when {
                !signedIn -> Text("Войди в Pulse", style = TextStyle(color = ColorProvider(Color.White), fontSize = 16.sp, fontWeight = FontWeight.Bold))
                tracking != null -> Text(tracking, style = TextStyle(color = ColorProvider(Color.White), fontSize = 18.sp, fontWeight = FontWeight.Bold))
                else -> {
                    Text(lastNight ?: "Ночей пока нет", style = TextStyle(color = ColorProvider(Color.White), fontSize = 17.sp, fontWeight = FontWeight.Bold))
                    Text("последняя ночь", style = TextStyle(color = ColorProvider(SECONDARY), fontSize = 12.sp))
                }
            }
            if (signedIn) {
                Spacer(GlanceModifier.defaultWeight())
                val action = if (isSleeping) SleepActions.STOP else SleepActions.START
                Box(
                    GlanceModifier.fillMaxWidth().height(40.dp).background(if (isSleeping) BUTTON_DARK else BLUE).cornerRadius(12.dp)
                        .clickable(actionSendBroadcast(Intent(action).setComponent(ComponentName(context, SleepActionReceiver::class.java)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (isSleeping) "☀️ Я проснулся" else "🌙 Начать сон", style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }

    companion object {
        private val BACKGROUND = Color(0xFF0B1224)
        private val CYAN = Color(0xFF22D3EE)
        private val SECONDARY = Color(0xFF8A94A8)
        private val BLUE = Color(0xFF1C6BFF)
        private val BUTTON_DARK = Color(0xFF1C2333)

        suspend fun refresh(context: Context) = runCatching { SleepWidget().updateAll(context) }
    }
}

class SleepWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SleepWidget()
}
