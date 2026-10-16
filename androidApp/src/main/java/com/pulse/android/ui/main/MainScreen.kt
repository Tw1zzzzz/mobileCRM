package com.pulse.android.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.ProvideCore
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.state.StateHubScreen
import com.pulse.android.ui.today.NotificationsSheet
import com.pulse.android.ui.today.TodayScreen
import com.pulse.android.ui.components.ScreenBackground
import com.pulse.android.ui.components.SecondaryButton
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.theme.Gradients
import com.pulse.android.ui.theme.Palette
import com.pulse.core.PulseCore
import com.pulse.core.model.User

/** Signed-in shell: role-specific tabs, a back stack per tab, the custom bottom bar with the «+» hump. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(core: PulseCore, user: User, debugRoute: String? = null) {
    val router = remember { AppRouter() }
    LaunchedEffect(debugRoute) {
        debugRoute?.let { DebugRoutes.resolve(it, user.role.isStaff) }?.let { (tab, routes) -> router.show(routes, tab) }
    }
    val tabs = AppTab.tabs(user.role)
    var showQuickAdd by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    LaunchedEffect(user.role) { if (router.selectedTab !in tabs) router.selectedTab = AppTab.TODAY }
    BackHandler(enabled = router.path(router.selectedTab).isNotEmpty()) { router.pop() }

    ProvideCore(core, router) { ScreenBackground {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                val route = router.path(router.selectedTab).lastOrNull()
                when {
                    route != null -> RouteScreen(route)
                    router.selectedTab == AppTab.TODAY && !user.role.isStaff -> TodayScreen { showNotifications = true }
                    router.selectedTab == AppTab.STATE -> StateHubScreen()
                    else -> TabRoot(router.selectedTab, core, user, router)
                }
            }
            PulseTabBar(tabs, router.selectedTab, onSelect = router::select,
                onPlus = if (AppTab.showsQuickAdd(user.role)) ({ showQuickAdd = true }) else null)
        }
    }
    if (showQuickAdd) {
        PulseSheet({ showQuickAdd = false }) {
            QuickAddSheet { showQuickAdd = false }
        }
    }
    if (showNotifications) {
        PulseSheet({ showNotifications = false }) {
            NotificationsSheet { showNotifications = false }
        }
    } }
}

@Composable
private fun TabRoot(tab: AppTab, core: PulseCore, user: User, router: AppRouter) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Text(tab.title, color = Palette.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
        when (tab) {
            AppTab.TODAY -> Placeholder("«Сегодня»", "Форма дня, игра, восстановление, итоги недели и план — шаг 3.1")
            AppTab.STATE -> Placeholder("«Состояние»", "Здоровье, тесты, игра и аналитика — шаги 3.1–3.5")
            AppTab.DIARY -> Placeholder("«Дневник CS2»", "Раскидки, скрипты и заметки — шаг 3.6")
            AppTab.PLAYERS -> Placeholder("«Игроки»", "Состав и питание команды — шаг 3.7")
            AppTab.ANALYTICS -> Placeholder("«Аналитика»", "Дашборд команды, сводка недели и корреляции — шаг 3.7")
            AppTab.LINEUPS -> Placeholder("«База знаний»", "Раскидки, скрипты и заметки команды — шаг 3.6")
            AppTab.PROFILE -> ProfilePlaceholder(core, user)
        }
        Text(
            "Каркас Android-версии (шаг 2). Данные и расчёты — общее ядро, те же, что на iPhone.",
            color = Palette.textTertiary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProfilePlaceholder(core: PulseCore, user: User) {
    Column(Modifier.pulseCard(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(user.nickname, color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(user.email, color = Palette.textSecondary, fontSize = 15.sp)
        Text("${user.role.title}${user.team?.let { " · $it" } ?: ""}${if (user.isDemo) " · демо" else ""}", color = Palette.cyan, fontSize = 14.sp)
        Text("Записей сна: ${core.sleep.sessions.size} · приёмов пищи: ${core.nutrition.meals.size}", color = Palette.textTertiary, fontSize = 13.sp)
    }
    SecondaryButton("Выйти из аккаунта", icon = Icons.AutoMirrored.Outlined.Logout, tint = Palette.red) { core.session.logout() }
}


@Composable
fun Placeholder(title: String, detail: String) {
    Row(Modifier.pulseCard(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(Icons.Outlined.Construction, Palette.cyan, size = 46.dp, iconSize = 22.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Palette.textSecondary, fontSize = 14.sp)
        }
    }
}

/** Bottom bar from the mockups: 4 tabs + elevated gradient «+» in a raised hump; staff get a flat bar. */
@Composable
private fun PulseTabBar(tabs: List<AppTab>, selected: AppTab, onSelect: (AppTab) -> Unit, onPlus: (() -> Unit)?) {
    val hump = if (onPlus != null) 14.dp else 0.dp
    Box(Modifier.fillMaxWidth()) {
        Canvas(Modifier.matchParentSize().offset(y = -hump).padding(bottom = 0.dp)) {
            val humpPx = hump.toPx()
            val path = Path().apply {
                moveTo(0f, humpPx)
                if (humpPx > 0) {
                    val center = size.width / 2
                    val half = 58.dp.toPx()
                    lineTo(center - half - 24.dp.toPx(), humpPx)
                    cubicTo(center - half, humpPx, center - half * 0.7f, 0f, center, 0f)
                    cubicTo(center + half * 0.7f, 0f, center + half, humpPx, center + half + 24.dp.toPx(), humpPx)
                }
                lineTo(size.width, humpPx)
                lineTo(size.width, size.height + humpPx)
                lineTo(0f, size.height + humpPx)
                close()
            }
            drawPath(path, Palette.tabBar)
            drawPath(path, Palette.divider, style = Stroke(1.dp.toPx()))
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp).padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            if (onPlus != null) {
                tabs.take(2).forEach { TabItem(it, it == selected, onSelect) }
                Box(Modifier.weight(1f).offset(y = (-8).dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(62.dp).shadow(14.dp, CircleShape, ambientColor = Palette.cyan, spotColor = Palette.cyan)
                            .clip(CircleShape).background(Gradients.accentDiagonal).pressable(onClick = onPlus)
                            .semantics { contentDescription = "Быстрое добавление" },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Add, null, tint = Palette.textPrimary, modifier = Modifier.size(30.dp)) }
                }
                tabs.drop(2).forEach { TabItem(it, it == selected, onSelect) }
            } else {
                tabs.forEach { TabItem(it, it == selected, onSelect) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(tab: AppTab, isSelected: Boolean, onSelect: (AppTab) -> Unit) {
    val color = if (isSelected) Palette.cyan else Palette.textSecondary
    Column(Modifier.weight(1f).pressable { onSelect(tab) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(tab.icon, null, tint = color, modifier = Modifier.size(26.dp))
        Text(tab.title, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, textAlign = TextAlign.Center)
    }
}
