package com.pulse.android.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.Callout
import com.pulse.android.ui.components.ChipButton
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.components.ErrorBanner
import com.pulse.android.ui.components.FractionLabels
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseLineChart
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.components.PulseTextField
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.fit
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.state.MatchRow
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.android.ui.today.DeltaLabel
import com.pulse.core.demo.GameData
import com.pulse.core.model.FaceitProfile
import com.pulse.core.model.GameMatch
import com.pulse.core.model.GameStats
import com.pulse.core.model.GameSummary
import com.pulse.core.model.PlayerProfile
import com.pulse.core.store.FaceitClient
import com.pulse.core.util.Formatters
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.roundToIntSwift
import kotlinx.coroutines.launch

/** «Игровая статистика» of the player: ELO, averages, recent matches and detailed CS2 numbers. */
@Composable
fun GameStatsScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val game = core.game
    val user = core.session.user.value
    var period by remember { mutableIntStateOf(14) }
    LaunchedEffect(Unit) { game.refresh() }
    ScreenScaffold(header = { NavHeader("Игровая статистика", "CS2 · Faceit", onBack = { router.pop() }) }) {
        FaceitSourceCard()
        if (!game.hasData) {
            EmptyStateView("gamecontroller", "Нет игровых данных", "Подключи Faceit — матчи, ELO и статистика подтянутся автоматически.")
            return@ScreenScaffold
        }
        val player = game.profile(user)
        val matches = game.matches(user, period)
        val summary = GameSummary.of(matches)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 14, 30).forEach { days -> ChipButton("$days дней", period == days) { period = days } }
        }
        EloCard(player, period)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Матчей", "${summary.matches}", Modifier.weight(1f))
            StatTile("Win-rate", "${summary.winRate.roundToIntSwift()}%", Modifier.weight(1f))
            StatTile("K/D", Formatters.decimal(summary.kd, 2), Modifier.weight(1f))
            StatTile("ADR", "${summary.adr.toInt()}", Modifier.weight(1f))
        }
        PulseSection("Последние матчи") {
            if (matches.isEmpty()) {
                EmptyStateView("gamecontroller", "Матчей нет", "За выбранный период матчей не найдено")
            } else {
                val shown = matches.take(8)
                Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                    shown.forEachIndexed { index, match ->
                        MatchRow(match)
                        if (index < shown.size - 1) DividerLine()
                    }
                }
            }
        }
        val faceit = game.state.value.faceit
        if (faceit != null) {
            FaceitDetailsSection(faceit, matches, period)
        } else {
            PulseSection("Подробная статистика · $period дней") { PlayerGameStats(GameData.stats(player, period)) }
        }
    }
}

@Composable
private fun EloCard(player: PlayerProfile, period: Int) {
    val core = observeCore()
    val history = core.game.eloHistory(core.session.user.value, period)
    val first = history.firstOrNull()?.value?.toInt() ?: player.elo
    Column(Modifier.pulseCard(padding = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(Formatters.grouped(player.elo), color = Palette.textPrimary, autoSize = fit(34.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("ELO", color = Palette.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 5.dp).weight(1f))
            Box(Modifier.padding(bottom = 6.dp)) { DeltaLabel(player.elo - first, "за период") }
        }
        if (history.size >= 2) {
            PulseLineChart(
                history.map { it.value },
                calloutFor = { i -> Callout(Formatters.dayMonthShort(history[i].date), "${history[i].value.toInt()} ELO", Palette.cyan) },
                height = 150.dp, showsArea = false, lineWidth = 2.5.dp
            )
            val step = maxOf((history.size + 3) / 4, 1)
            FractionLabels(history.indices.filter { it % step == 0 }.map { i -> i / (history.size - 1f) to Formatters.dayMonthShort(history[i].date) })
        } else {
            Text("История ELO копится с момента подключения — загляни через пару дней.", color = Palette.textSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(Metrics.tileRadius)).background(Palette.tile).border(1.dp, Palette.stroke, RoundedCornerShape(Metrics.tileRadius))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(value, color = Palette.textPrimary, autoSize = fit(19.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, color = Palette.textSecondary, autoSize = fit(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Top of the game screens: connect Faceit, or show which account the numbers come from. */
@Composable
fun FaceitSourceCard() {
    val core = observeCore()
    val game = core.game
    val state = game.state.value
    val scope = rememberCoroutineScope()
    var connect by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    if (!game.isConnected) {
        Column(Modifier.pulseCard(padding = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FaceitLogo()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Подключи Faceit", color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.isDemo) "ELO, уровень, матчи, K/D, ADR, хедшоты, клатчи и статистика по картам. Сейчас показаны демо-данные."
                        else "ELO, уровень, матчи, K/D, ADR, хедшоты, клатчи и статистика по картам подтянутся автоматически.",
                        color = Palette.textSecondary, fontSize = 14.sp
                    )
                }
            }
            GradientButton("Подключить", icon = sfIcon("link")) { connect = true }
        }
    } else {
        Column(Modifier.pulseCard(padding = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                FaceitLogo()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Faceit · ${state.faceit?.nickname ?: state.nickname ?: ""}", color = Palette.textPrimary, autoSize = fit(16.sp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val profile = state.faceit
                    Text(
                        when {
                            state.isSyncing && profile == null -> "Загружаем данные…"
                            profile == null -> "Подключено"
                            else -> "Обновлено в ${Formatters.time(profile.fetchedAt)} · ${profile.matches.size} матчей"
                        },
                        color = Palette.textSecondary, fontSize = 13.sp
                    )
                }
                if (state.isSyncing) CircularProgressIndicator(color = Palette.orange, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Box {
                    Box(Modifier.size(36.dp).pressable { menu = true }, contentAlignment = Alignment.Center) {
                        Icon(sfIcon("ellipsis"), "Настройки Faceit", tint = Palette.textSecondary, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Обновить") }, onClick = { menu = false; scope.launch { game.refresh() } })
                        DropdownMenuItem(text = { Text("Сменить аккаунт или ключ") }, onClick = { menu = false; connect = true })
                        DropdownMenuItem(text = { Text("Отключить", color = Palette.red) }, onClick = { menu = false; game.disconnect() })
                    }
                }
            }
            state.error?.let { ErrorBanner(it) }
        }
    }
    if (connect) PulseSheet({ connect = false }) { FaceitConnectSheet { connect = false } }
}

@Composable
private fun FaceitLogo() {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFFF5500)), contentAlignment = Alignment.Center) {
        Icon(sfIcon("flame.fill"), null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/**
 * Nickname or profile link plus the user's own Faceit API key. The key goes to the Android Keystore-backed secret store
 * and is never shown again; the field stays empty to keep the saved one.
 */
@Composable
private fun FaceitConnectSheet(onClose: () -> Unit) {
    val core = observeCore()
    val game = core.game
    val state = game.state.value
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var nickname by remember { mutableStateOf(state.nickname ?: core.session.user.value?.faceitURL ?: "") }
    var apiKey by remember { mutableStateOf("") }
    SheetScaffold("Faceit", onClose) {
        PulseTextField("Ник или ссылка на профиль", nickname, "https://www.faceit.com/ru/players/…") { nickname = it }
        // The app's own key (from the build) is enough: only builds without it ask the player for one.
        if (game.hasBuiltInKey) {
            Text("ELO, уровень, матчи и статистика по картам подтянутся по нику.", color = Palette.textSecondary, fontSize = 14.sp)
        } else {
            PulseTextField(if (game.hasApiKey) "API-ключ (сохранён, можно заменить)" else "API-ключ *", apiKey, "xxxxxxxx-xxxx-…", secure = true) { apiKey = it }
            Column(Modifier.pulseCard(padding = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Где взять ключ", color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "developers.faceit.com → App Studio → создать приложение → API Keys → ключ типа Client side. Ключ хранится только на этом телефоне в защищённом хранилище Android; когда появится сервер Pulse, он будет обращаться к Faceit сам.",
                    color = Palette.textSecondary, fontSize = 13.sp
                )
                Text("Открыть портал разработчиков", color = Palette.cyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.pressable { uri.openUri("https://developers.faceit.com") })
            }
        }
        state.error?.let { ErrorBanner(it) }
        GradientButton(if (state.isSyncing) "Подключаем…" else "Подключить", icon = sfIcon("link"), isLoading = state.isSyncing) {
            scope.launch {
                game.connect(nickname, apiKey)
                apiKey = ""
                if (game.state.value.error == null) onClose()
            }
        }
    }
}

/** Real Faceit numbers: account, lifetime, the selected period from match stats, per-map results. */
@Composable
private fun FaceitDetailsSection(profile: FaceitProfile, matches: List<GameMatch>, period: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.pulseCard(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            profile.skillLevel?.let { level ->
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(levelColor(level)).clearAndSetSemantics { contentDescription = "Уровень $level" },
                    contentAlignment = Alignment.Center
                ) {
                    Text("$level", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(profile.nickname, color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(profile.country?.uppercase(), profile.region, profile.regionRank?.let { "#${Formatters.grouped(it)} в регионе" }).joinToString(" · "),
                    color = Palette.textSecondary, fontSize = 13.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Formatters.grouped(profile.elo), color = Palette.orange, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("ELO", color = Palette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        profile.lifetime?.let { lifetime ->
            PulseSection("За всё время") {
                Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CellGrid(listOfNotNull(
                        Cell("Матчей", Formatters.grouped(lifetime.matches)),
                        Cell("Победы", "${lifetime.winRate.roundToIntSwift()}%", tint = Palette.cyan),
                        Cell("K/D", Formatters.decimal(lifetime.kd, 2)),
                        Cell("Хедшоты", "${lifetime.headshots.roundToIntSwift()}%"),
                        Cell("Серия побед", "${lifetime.longestWinStreak}"),
                        Cell("Текущая серия", "${lifetime.currentWinStreak}"),
                        lifetime.adr?.let { Cell("ADR", Formatters.decimal(it)) },
                        lifetime.entrySuccessRate?.let { Cell("Успех входа", "${it.roundToIntSwift()}%") },
                        lifetime.utilityDamagePerRound?.let { Cell("Урон гранатами/раунд", Formatters.decimal(it)) },
                        lifetime.clutch1v1WinRate?.let { Cell("Клатчи 1v1", "${it.roundToIntSwift()}%") }
                    ))
                    if (lifetime.recentResults.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Последние:", color = Palette.textSecondary, fontSize = 13.sp)
                        lifetime.recentResults.forEach { won ->
                            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (won) Palette.green else Palette.red), contentAlignment = Alignment.Center) {
                                Text(if (won) "W" else "L", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        PulseSection("Подробно · $period дней") { PeriodCard(matches) }
        if (profile.maps.isNotEmpty()) PulseSection("Карты") {
            Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                profile.maps.forEachIndexed { index, map ->
                    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(map.map.removePrefix("de_").replaceFirstChar { it.uppercase() }, color = Palette.textPrimary, autoSize = fit(16.sp), fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(90.dp))
                        Text("${map.matches} матчей", color = Palette.textSecondary, autoSize = fit(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${map.winRate.roundToIntSwift()}%", color = if (map.winRate >= 50) Palette.green else Palette.red, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                        Text("K/D ${Formatters.decimal(map.kd, 2)}", color = Palette.textSecondary, autoSize = fit(13.sp), fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(70.dp))
                    }
                    if (index < profile.maps.size - 1) DividerLine()
                }
            }
        }
        Text("Данные Faceit Data API: последние ${FaceitClient.MATCH_LIMIT} матчей CS2. История ELO копится с момента подключения — Faceit отдаёт только текущее значение.",
            color = Palette.textTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun PeriodCard(matches: List<GameMatch>) {
    val details = matches.mapNotNull { it.details }
    if (details.isEmpty()) {
        Text("За этот период матчей на Faceit нет", color = Palette.textSecondary, fontSize = 15.sp, modifier = Modifier.pulseCard(16.dp))
        return
    }
    val count = maxOf(details.size, 1).toDouble()
    val rounds = maxOf(details.sumOf { it.rounds }, 1).toDouble()
    val entries = details.sumOf { it.entryCount }
    val entryWins = details.sumOf { it.entryWins }
    val headshots = StatisticsMath.mean(details.mapNotNull { it.headshotsPercent })
    Column(Modifier.pulseCard(16.dp)) {
        CellGrid(listOf(
            Cell("Хедшоты", headshots?.let { "${it.roundToIntSwift()}%" } ?: "—"),
            Cell("Первые убийства", Formatters.decimal(details.mapNotNull { it.firstKills }.sum() / count), "за матч"),
            Cell("Успех входа", if (entries > 0) "${(entryWins.toDouble() / entries * 100).roundToIntSwift()}%" else "—"),
            Cell("Клатчи", "${details.sumOf { it.clutchesWon }}", "1v1 и 1v2"),
            Cell("Мультикиллы", Formatters.decimal(details.sumOf { it.multiKills } / count), "за матч"),
            Cell("MVP", Formatters.decimal(details.mapNotNull { it.mvps }.sum() / count), "за матч"),
            Cell("Урон гранатами", Formatters.decimal(details.mapNotNull { it.utilityDamage }.sum() / rounds), "за раунд"),
            Cell("Ослеплено", Formatters.decimal(details.mapNotNull { it.enemiesFlashed }.sum() / count), "врагов за матч"),
            Cell("Со снайперки", "${details.mapNotNull { it.sniperKills }.sum()}", "убийств")
        ))
    }
}

/** Faceit level colours: grey 1, green 2–3, yellow 4–7, orange 8–9, red 10. */
private fun levelColor(level: Int): Color = when {
    level < 2 -> Color(0xFF9E9E9E)
    level <= 3 -> Palette.green
    level <= 7 -> Palette.yellow
    level <= 9 -> Palette.orange
    else -> Palette.red
}

data class Cell(val title: String, val value: String, val caption: String? = null, val tint: Color = Palette.textPrimary)

/** Three-column grid of number cells (iOS `LazyVGrid` with three flexible columns). */
@Composable
fun CellGrid(cells: List<Cell>) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        cells.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { cell ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(cell.value, color = cell.tint, autoSize = fit(19.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(cell.caption?.let { "${cell.title}, $it" } ?: cell.title, color = Palette.textSecondary, autoSize = fit(12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** K/D, analytic metrics, matches, rounds and pistol rounds (CS2) — demo detail and the staff player card. */
@Composable
fun PlayerGameStats(stats: GameStats) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StatsGroup("K/D статистика", listOf(Cell("Убийства", "${stats.kills}"), Cell("Смерти", "${stats.deaths}"), Cell("Ассисты", "${stats.assists}")))
        StatsGroup("Аналитические метрики", listOf(
            Cell("ADR", Formatters.decimal(stats.adr)),
            Cell("KPR", Formatters.decimal(stats.kpr, 2)),
            Cell("Death/round", Formatters.decimal(stats.deathsPerRound, 2)),
            Cell("AVG KR", Formatters.decimal(stats.avgKR, 2)),
            Cell("AVG KD", Formatters.decimal(stats.avgKD, 2)),
            Cell("KAST", "${Formatters.decimal(stats.kast)}%"),
            Cell("First kills", "${stats.firstKills}"),
            Cell("First deaths", "${stats.firstDeaths}"),
            Cell("UDR", Formatters.decimal(stats.udr)),
            Cell("Open duels ±", "${if (stats.openDuelDiff > 0) "+" else ""}${stats.openDuelDiff}"),
            Cell("Мультикиллы", Formatters.decimal(stats.avgMultikills)),
            Cell("Клатчи", "${stats.clutchesWon}"),
            Cell("Ослепление", "${Formatters.decimal(stats.avgFlashTime)} с")
        ))
        StatsGroup("Матчи", listOf(
            Cell("Всего", "${stats.matches}"),
            Cell("Победы", "${stats.wins}", tint = Palette.green),
            Cell("Поражения", "${stats.losses}", tint = Palette.red),
            Cell("Ничьи", "${stats.draws}"),
            Cell("Win-Rate", "${stats.winRate.toInt()}%", tint = Palette.cyan)
        ))
        StatsGroup("Раунды", listOf(
            Cell("Всего", "${stats.rounds}"),
            Cell("Выиграно", "${stats.roundsWon}", tint = Palette.green),
            Cell("Проиграно", "${stats.roundsLost}", tint = Palette.red),
            Cell("Round WR", "${stats.roundWinRate.toInt()}%", tint = Palette.cyan),
            Cell("AVG Won", Formatters.decimal(stats.avgRoundsWon)),
            Cell("AVG Lost", Formatters.decimal(stats.avgRoundsLost))
        ))
        StatsGroup("Пистолетные раунды", listOf(
            Cell("Всего", "${stats.pistolRounds}"),
            Cell("Выиграно", "${stats.pistolWon}", tint = Palette.green),
            Cell("Pistol WR", "${stats.pistolWinRate.toInt()}%", tint = Palette.cyan)
        ))
    }
}

@Composable
private fun StatsGroup(title: String, cells: List<Cell>) {
    Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        CellGrid(cells)
    }
}
