package com.pulse.android

import android.app.Application
import android.util.Log
import com.pulse.android.platform.HealthConnectProvider
import com.pulse.android.platform.KeystoreSecretStore
import com.pulse.android.platform.PulseNotifications
import com.pulse.android.platform.ReminderScheduler
import com.pulse.android.platform.SleepTrackingNotification
import com.pulse.android.platform.SyncScheduler
import com.pulse.android.widget.SleepTileService
import com.pulse.android.widget.SleepWidget
import com.pulse.core.PulseCore
import com.pulse.core.api.Backend
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
            health = HealthConnectProvider(this),
            faceitKey = BuildConfig.FACEIT_API_KEY,
            backend = if (BuildConfig.API_BASE_URL.isNotEmpty()) Backend.Remote(BuildConfig.API_BASE_URL) else Backend.Local
        )
        // Sync failures in logcat: collection, error kind, code and HTTP status only (no contents, tokens or emails).
        core.log = { message -> Log.w("PulseSync", message) }
        PulseNotifications.ensureChannels(this)
        // Data sync: after local changes, when the app goes to the background and at every start with a real account.
        SyncScheduler.attach(this, core)
        // Reminders follow the signed-in account (and its role) — replanned on every sign-in, switch and sign-out.
        appScope.launch {
            var previous: Any? = null
            core.session.user.map { it?.id to it?.role }.distinctUntilChanged().collect { (id, _) ->
                // The sleep notification of the new account (if it is tracking) is re-posted by the sleep collector below.
                if (previous != null && previous != id) PulseNotifications.onAccountChanged(this@PulseApplication)
                previous = id
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
