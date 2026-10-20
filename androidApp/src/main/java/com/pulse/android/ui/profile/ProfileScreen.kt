package com.pulse.android.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.BuildConfig
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.auth.LegalDocument
import com.pulse.android.ui.components.ChipButton
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.ErrorBanner
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.HeaderIconButton
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.components.PulseTextField
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.SecondaryButton
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.StatusPill
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.AppTab
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.onboarding.PlayerPortraitSection
import com.pulse.android.ui.theme.Gradients
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.android.ui.today.RoleBadge
import com.pulse.core.demo.DemoData
import com.pulse.core.model.User
import com.pulse.core.model.UserRole
import com.pulse.core.store.LineupStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen() {
    val core = observeCore()
    val user = core.session.user.value ?: return
    var edit by remember { mutableStateOf(false) }
    var gameGoals by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var legal by remember { mutableStateOf<LegalDocument?>(null) }
    ScreenScaffold(header = {
        NavHeader("Профиль") { HeaderIconButton("pencil", "Редактировать профиль") { edit = true } }
    }) {
        Header(user)
        StatusCard(user)
        StatsRow(user)
        if (!user.role.isStaff) {
            PlayerPortraitSection()
            PulseSection("Игровые цели", "Изменить", { gameGoals = true }) { GameGoalsCard { gameGoals = true } }
            GoalsSection()
        }
        PulseSection("Настройки") { SettingsCard(user) }
        if (user.isDemo) {
            DemoModeSection(user)
        } else {
            PulseSection("Данные и конфиденциальность") {
                Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(sfIcon("lock.fill"), null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp))
                        Text("Все данные хранятся только на этом телефоне", color = Palette.textSecondary, fontSize = 14.sp)
                    }
                    DividerLine()
                    SettingRow(LegalDocument.PRIVACY.title, "hand.raised", Palette.blue, chevron = true) { legal = LegalDocument.PRIVACY }
                    DividerLine()
                    SettingRow(LegalDocument.TERMS.title, "doc.text", Palette.blue, chevron = true) { legal = LegalDocument.TERMS }
                    DividerLine()
                    SettingRow("Удалить аккаунт", "trash", Palette.red) { confirmDelete = true }
                }
            }
        }
        SecondaryButton("Выйти из аккаунта", icon = sfIcon("rectangle.portrait.and.arrow.right"), tint = Palette.red) { confirmLogout = true }
        Text("Pulse MVP · версия ${BuildConfig.VERSION_NAME}", color = Palette.textTertiary, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    if (edit) PulseSheet({ edit = false }) { EditProfileSheet { edit = false } }
    if (gameGoals) PulseSheet({ gameGoals = false }) { GameGoalsSheet { gameGoals = false } }
    legal?.let { document ->
        PulseSheet({ legal = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
                Text(document.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(document.text, color = Palette.textSecondary, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 14.dp))
            }
        }
    }
    if (confirmLogout) Confirm("Выйти из аккаунта?", null, "Выйти", { confirmLogout = false }) { core.session.logout() }
    if (confirmDelete) Confirm(
        "Удалить аккаунт и все данные?",
        "Сон, питание, тесты, тренировки, дневник и цели будут удалены с этого телефона. Отменить нельзя.",
        "Удалить безвозвратно", { confirmDelete = false }
    ) { core.session.deleteAccount() }
}

/** Destructive confirmation (iOS `confirmationDialog`). */
@Composable
private fun Confirm(title: String, message: String?, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.tile,
        title = { Text(title, color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = message?.let { { Text(it, color = Palette.textSecondary, fontSize = 15.sp) } },
        confirmButton = { TextButton({ onDismiss(); onConfirm() }) { Text(action, color = Palette.red, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена", color = Palette.textSecondary) } }
    )
}

@Composable
private fun Header(user: User) {
    Row(Modifier.pulseCard(padding = 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(80.dp).shadow(14.dp, CircleShape, ambientColor = Palette.cyan, spotColor = Palette.cyan).clip(CircleShape).background(Gradients.accentDiagonal),
            contentAlignment = Alignment.Center
        ) { Text(user.initials, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(user.nickname, color = Palette.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(user.email, color = Palette.textSecondary, fontSize = 15.sp, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoleBadge(user.role, compact = true)
                val tint = if (user.tariff.hasSubscription) Palette.green else Palette.textSecondary
                Text(user.tariff.title, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(tint.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }
}

/** Account type, team, available sections and the Faceit link. */
@Composable
private fun StatusCard(user: User) {
    val uri = LocalUriHandler.current
    Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
        InfoRow("Статус профиля", user.role.title, user.role.badgeIcon)
        DividerLine()
        InfoRow("Команда", user.team ?: "Без команды (Solo)", "person.3")
        DividerLine()
        InfoRow("Версия приложения", if (user.role.isStaff) "Staff: команда и аналитика" else "Игрок: трекинг и тесты", "square.grid.2x2")
        // Only web links are opened — the field is free text the user typed.
        user.faceitURL?.takeIf { it.startsWith("https://") }?.let { link ->
            DividerLine()
            Box(Modifier.pressable { runCatching { uri.openUri(link) } }) { InfoRow("Faceit", "Открыть профиль ↗", "link") }
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String, icon: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(sfIcon(icon), null, tint = Palette.cyan, modifier = Modifier.size(20.dp))
        Text(title, color = Palette.textSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, maxLines = 2)
    }
}

@Composable
private fun StatsRow(user: User) {
    val core = observeCore()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (user.role.isStaff) {
            Stat("${DemoData.players.size}", "игроков", "person.3.fill", Palette.cyan, Modifier.weight(1f))
            Stat("${core.calendar.events.size}", "событий", "calendar", Palette.orange, Modifier.weight(1f))
            Stat("${LineupStore.visible(core.lineups.lineups, user).size}", "раскидок", "scope", Palette.green, Modifier.weight(1f))
        } else {
            Stat("${core.streakDays()}", "дней подряд", "flame.fill", Palette.orange, Modifier.weight(1f))
            Stat("${core.cognitive.results.size}", "тестов", "brain.head.profile", Palette.green, Modifier.weight(1f))
            Stat("${core.sleep.sessions.size}", "ночей", "moon.fill", Palette.purple, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(value: String, title: String, icon: String, tint: Color, modifier: Modifier) {
    Column(modifier.pulseCard(14.dp, Metrics.tileRadius), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(sfIcon(icon), null, tint = tint, modifier = Modifier.size(20.dp))
        Text(value, color = Palette.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(title, color = Palette.textSecondary, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun SettingsCard(user: User) {
    val core = observeCore()
    val router = LocalRouter.current
    val screenTime = core.screenTime.state.value
    Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
        if (!user.role.isStaff && screenTime.isDemo) {
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SettingLabel("Доступ к экранному времени", "hourglass", Palette.cyan) }
                Switch(screenTime.isAuthorized, { core.screenTime.setAuthorized(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.blue, checkedThumbColor = Color.White))
            }
            DividerLine()
        }
        SettingRow("Календарь", "calendar", Palette.purple, chevron = true) { router.open(Route.Calendar(createNew = false), AppTab.PROFILE) }
    }
}

@Composable
private fun SettingRow(title: String, icon: String, tint: Color, chevron: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SettingLabel(title, icon, tint) }
        if (chevron) Icon(sfIcon("chevron.right"), null, tint = Palette.textTertiary, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun SettingLabel(title: String, icon: String, tint: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(sfIcon(icon), tint, 36.dp, 16.dp)
        Text(title, color = Palette.textPrimary, fontSize = 16.sp)
    }
}

/** Name and Faceit link. Goals are edited in «Профиль → Цели». */
@Composable
private fun EditProfileSheet(onClose: () -> Unit) {
    val core = observeCore()
    val user = core.session.user.value
    var nickname by remember { mutableStateOf(user?.nickname ?: "") }
    var faceit by remember { mutableStateOf(user?.faceitURL ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    SheetScaffold("Профиль", onClose) {
        PulseTextField("Имя *", nickname, "Nova") { nickname = it }
        PulseTextField("Ссылка Faceit", faceit, "https://www.faceit.com/…", KeyboardType.Uri) { faceit = it }
        if (user?.role?.isStaff == false) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(sfIcon("target"), null, tint = Palette.textTertiary, modifier = Modifier.size(16.dp))
                Text("Цели по игре, фитнесу, питанию, сну и экранному времени — в разделе «Цели» профиля.", color = Palette.textTertiary, fontSize = 13.sp)
            }
        }
        error?.let { ErrorBanner(it) }
        GradientButton("Сохранить", icon = sfIcon("checkmark")) {
            error = core.session.updateProfile(nickname, faceit)
            if (error == null) onClose()
        }
    }
}

/** Tools for showing the MVP without a server: role switch and demo data reset (test notifications arrive in step 4). */
@Composable
private fun DemoModeSection(user: User) {
    val core = observeCore()
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(notice) {
        if (notice != null) { delay(4000); notice = null }
    }
    PulseSection("Демо-режим") {
        Column(Modifier.pulseCard(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Сменить роль без выхода из аккаунта", color = Palette.textSecondary, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(UserRole.TEAM_PLAYER, UserRole.SOLO_PLAYER, UserRole.STAFF).forEach { role ->
                    ChipButton(role.badgeTitle, user.role == role) { scope.launch { core.session.switchToDemo(role) } }
                }
            }
            DividerLine()
            Row(Modifier.fillMaxWidth().pressable { confirmReset = true }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(sfIcon("arrow.counterclockwise"), null, tint = Palette.red, modifier = Modifier.width(22.dp))
                Text("Сбросить демо-данные", color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            notice?.let { StatusPill(it, Palette.cyan) }
        }
    }
    if (confirmReset) Confirm("Вернуть все демо-данные к исходным?", null, "Сбросить", { confirmReset = false }) {
        core.resetDemoData()
        notice = "Демо-данные восстановлены"
    }
}
