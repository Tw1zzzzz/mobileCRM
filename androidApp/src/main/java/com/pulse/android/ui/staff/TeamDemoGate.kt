package com.pulse.android.ui.staff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Palette
import com.pulse.core.PulseCore
import com.pulse.core.demo.DemoData

/** Players in the team: the seeded demo roster for demo accounts, none for a real staff account (no team data in the app yet). */
val PulseCore.teamPlayerCount: Int get() = if (showsTeamDemo) DemoData.players.size else 0

/**
 * The seeded demo team is for demo accounts only: a real staff account gets [empty] (an in-place empty state) in place of
 * [content] — also for routes restored after a restart and for push targets.
 */
@Composable
fun TeamDemoOnly(empty: @Composable () -> Unit = { TeamNoDataState() }, content: @Composable () -> Unit) {
    if (observeCore().showsTeamDemo) content() else empty()
}

/** Team analytics, correlations, team food and the weekly report of a real staff account until players fill the app. */
@Composable
fun TeamNoDataState() {
    EmptyStateView("chart.bar", "Пока нет данных", "Здесь появятся показатели игроков, когда они начнут заполнять приложение.")
}

/** «Состав» of a real staff account: no players yet, and the team's player code to hand out (staff only). */
@Composable
fun TeamEmptyRoster() {
    val code = observeCore().session.user.value?.inviteCode?.takeIf { it.isNotBlank() }
    EmptyStateView(
        "person.3", "В команде пока нет игроков", "Отправь игрокам код команды — они появятся здесь после регистрации.",
        action = code?.let { { PlayerCode(it) } }
    )
}

@Composable
private fun PlayerCode(code: String) {
    Column(Modifier.padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Код для игроков", color = Palette.textSecondary, fontSize = 13.sp)
        Text(code, color = Palette.cyan, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
    }
}
