package com.pulse.android

import android.app.Application
import com.pulse.android.platform.HealthConnectProvider
import com.pulse.android.platform.KeystoreSecretStore
import com.pulse.android.platform.PulseNotifications
import com.pulse.android.platform.ReminderScheduler
import com.pulse.android.platform.SleepTrackingNotification
import com.pulse.android.widget.SleepTileService
import com.pulse.android.widget.SleepWidget
import com.pulse.core.PulseCore
import com.pulse.core.model.SleepWindow
import com.pulse.core.platform.JvmFileSystem
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** Owns the shared core for the whole process (Android twin of iOS `AppDependencies.shared`). */
class PulseApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var core: PulseCore
        private set

    override fun onCreate() {
        super.onCreate()
        core = PulseCore(
            files = JvmFileSystem(File(filesDir, "Pulse")),
            secrets = KeystoreSecretStore(this),
            http = HttpClient(OkHttp),
            background = appScope,
            health = HealthConnectProvider(this)
        )
        PulseNotifications.ensureChannels(this)
        // Reminders follow the signed-in account (and its role) — replanned on every sign-in, switch and sign-out.
        appScope.launch {
            core.session.user.map { it?.id to it?.role }.distinctUntilChanged().collect {
                ReminderScheduler.rescheduleAll(this@PulseApplication, core)
                SleepWidget.refresh(this@PulseApplication)
                SleepTileService.refresh(this@PulseApplication)
            }
        }
        // Whoever starts or stops sleep (app, notification, widget, tile), the shade follows.
        appScope.launch {
            var lastWindow: SleepWindow? = null
            core.sleep.state.map { it.activeStart to it.window }.distinctUntilChanged().collect { (start, window) ->
                SleepTrackingNotification.sync(this@PulseApplication, start, window)
                SleepWidget.refresh(this@PulseApplication)
                SleepTileService.refresh(this@PulseApplication)
                // A new bedtime moves «Скоро отбой».
                if (lastWindow != null && lastWindow != window) ReminderScheduler.rescheduleAll(this@PulseApplication, core)
                lastWindow = window
            }
        }
    }
}
