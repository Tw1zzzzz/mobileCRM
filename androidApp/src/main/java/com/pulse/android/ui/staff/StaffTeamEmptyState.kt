package com.pulse.android.ui.staff

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import com.pulse.android.BuildConfig
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.components.SecondaryButton
import com.pulse.android.ui.observeCore

/**
 * Where a real staff account finds its team: the website cabinet, `/staff` next to the API (`…/v1/` is the API root).
 * Null when the build has no server (local build), so there is nothing to open.
 */
fun staffCabinetUrl(apiBaseUrl: String): String? {
    val host = apiBaseUrl.trim().trimEnd('/').removeSuffix("/v1").trimEnd('/')
    return if (host.isEmpty()) null else "$host/staff"
}

/** Honest placeholder for the team screens of a real staff account: the roster and statistics live in the website cabinet. */
@Composable
fun StaffTeamEmptyState(apiBaseUrl: String = BuildConfig.API_BASE_URL) {
    val uri = LocalUriHandler.current
    val cabinet = staffCabinetUrl(apiBaseUrl)
    EmptyStateView(
        "person.3", "Данные команды — на сайте",
        "Состав, готовность, питание и статистику игроков смотри в кабинете стаффа на сайте — раздел /staff. " +
            "Вход с той же почтой и паролем, что в приложении.",
        action = cabinet?.let { url -> { SecondaryButton("Открыть кабинет") { runCatching { uri.openUri(url) } } } }
    )
}

/**
 * The seeded demo team is for demo accounts only: a real staff account gets [StaffTeamEmptyState] in place of [content]
 * (also for routes restored after a restart and for push targets).
 */
@Composable
fun TeamDemoOnly(content: @Composable () -> Unit) {
    if (observeCore().showsTeamDemo) content() else StaffTeamEmptyState()
}
