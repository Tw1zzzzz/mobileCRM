package com.pulse.android.ui.staff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.components.ChipButton
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Palette
import com.pulse.core.PulseCore
import com.pulse.core.staff.StaffTeamState
import com.pulse.core.staff.TeamSnapshot
import com.pulse.core.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What a real staff account's screens show of the team, from the state of `core.staffTeam`. */
sealed interface RealTeamView {
    /** The first download is running and there is no copy. */
    data object Loading : RealTeamView
    /** The download failed and there is no copy. */
    data object Failed : RealTeamView
    /** No team on a server: a build without one, or access that was taken away. */
    data object NoServerTeam : RealTeamView
    /** The team ([TeamSnapshot.players] may be empty); [stale]: the copy is not from the last refresh (offline). */
    data class Loaded(val team: TeamSnapshot, val stale: Boolean) : RealTeamView
}

/** The view of the current team state. */
fun PulseCore.realTeamView(): RealTeamView = when (val state = staffTeam.state.value) {
    is StaffTeamState.Ready -> RealTeamView.Loaded(state.team, state.stale)
    StaffTeamState.Loading -> RealTeamView.Loading
    StaffTeamState.Failed -> RealTeamView.Failed
    // A server build starts the download the moment the account is activated; a local build has no team to download.
    StaffTeamState.Idle -> if (isRemote) RealTeamView.Loading else RealTeamView.NoServerTeam
}

/** Players in the team, or null while it is not known (loading, failed). */
val PulseCore.teamPlayerCount: Int?
    get() = if (showsTeamDemo) com.pulse.core.demo.DemoData.players.size else when (val view = realTeamView()) {
        is RealTeamView.Loaded -> view.team.players.size
        RealTeamView.NoServerTeam -> 0
        RealTeamView.Loading, RealTeamView.Failed -> null
    }

/** Under «Игроки»: «{team} · {N} в составе»; just the team's name while the number is not known. */
fun PulseCore.playersSubtitle(): String {
    val name = (realTeamView() as? RealTeamView.Loaded)?.team?.teamName ?: session.user.value?.team ?: "Команда"
    return teamPlayerCount?.let { "$name · $it в составе" } ?: name
}

/** Starts team downloads that outlive the screen that asked for them. */
object StaffTeamRefresh {
    /** A copy older than this is downloaded again when the «Сегодня» or «Игроки» tab opens. */
    val STALE_AFTER: Duration = 5.minutes

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    fun start(core: PulseCore): Job = scope.launch { core.staffTeam.refresh() }

    /** A copy past [STALE_AFTER], or no copy after a failure; a running download or a team that is not loaded is left alone. */
    fun isOld(state: StaffTeamState, now: Instant): Boolean = when (state) {
        is StaffTeamState.Ready -> now - state.team.fetchedAt > STALE_AFTER
        StaffTeamState.Failed -> true
        StaffTeamState.Idle, StaffTeamState.Loading -> false
    }

    fun startIfOld(core: PulseCore, now: Instant = Clock.System.now()) {
        if (!core.showsTeamDemo && isOld(core.staffTeam.state.value, now)) start(core)
    }
}

/** Pull-to-refresh state of a staff screen: whether a download is running and how to start one; null for a demo account. */
class TeamRefresh(val isRefreshing: Boolean, val refresh: () -> Unit)

@Composable
fun rememberTeamRefresh(): TeamRefresh? {
    val core = observeCore()
    var pulled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (core.showsTeamDemo || !core.isRemote) return null
    // While the first download shows its own progress card there is no second spinner on top of it.
    val firstDownload = core.staffTeam.state.value == StaffTeamState.Loading
    return TeamRefresh(pulled && !firstDownload) {
        if (!pulled) {
            pulled = true
            scope.launch { try { StaffTeamRefresh.start(core).join() } finally { pulled = false } }
        }
    }
}

/** [rememberTeamRefresh] of a screen that opens a download of an old copy when it appears (null for a demo account). */
@Composable
fun rememberRealTeamRefresh(): TeamRefresh? {
    val core = observeCore()
    LaunchedEffect(Unit) { StaffTeamRefresh.startIfOld(core) }
    return rememberTeamRefresh()
}

/** «Обновлено 14:05» (with the day when it was another one): the copy on screen is not from the last refresh. */
fun updatedAtText(fetchedAt: Instant, now: Instant = Clock.System.now()): String {
    val time = if (Formatters.dayKey(fetchedAt) == Formatters.dayKey(now)) Formatters.time(fetchedAt) else Formatters.dayMonthTime(fetchedAt)
    return "Обновлено $time"
}

@Composable
fun StaleNote(view: RealTeamView.Loaded) {
    if (!view.stale) return
    Text(updatedAtText(view.team.fetchedAt), color = Palette.textTertiary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

/** The team is being downloaded for the first time. */
@Composable
fun TeamLoadingState() {
    Row(Modifier.fillMaxWidth().pulseCard(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), color = Palette.cyan, strokeWidth = 2.dp)
        Text("Загружаем команду…", color = Palette.textSecondary, fontSize = 16.sp)
    }
}

/** No copy and no connection (or the server said no). */
@Composable
fun TeamLoadFailedState() {
    val core = observeCore()
    EmptyStateView(
        "exclamationmark.triangle.fill", "Не удалось загрузить команду", "Проверь соединение и попробуй ещё раз.",
        action = { ChipButton("Повторить", isSelected = true) { StaffTeamRefresh.start(core) } }
    )
}

/** The loaded team when it has players to list; null while loading, on failure and for an empty team. */
val RealTeamView.withPlayers: RealTeamView.Loaded? get() = (this as? RealTeamView.Loaded)?.takeIf { it.team.players.isNotEmpty() }

/** The card of a [view] that has no [withPlayers] to list: progress, the failure, or the empty team with its player code. */
@Composable
fun TeamStateCard(view: RealTeamView) {
    when (view) {
        RealTeamView.Loading -> TeamLoadingState()
        RealTeamView.Failed -> TeamLoadFailedState()
        RealTeamView.NoServerTeam -> TeamEmptyRoster()
        is RealTeamView.Loaded -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StaleNote(view)
            TeamEmptyRoster(view.team.inviteCode)
        }
    }
}
