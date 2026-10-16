package com.pulse.android.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.ScreenScaffold

/** Single place mapping routes to screens, shared by all tab stacks (iOS `RouteDestination`). */
@Composable
fun RouteScreen(route: Route) {
    when (route) {
        Route.Sleep -> com.pulse.android.ui.health.SleepScreen()
        Route.SleepRecovery -> com.pulse.android.ui.health.SleepRecoveryScreen()
        Route.Fitness -> com.pulse.android.ui.health.FitnessScreen()
        Route.FitnessWeek -> com.pulse.android.ui.health.FitnessWeekScreen()
        else -> {
            val (title, step) = placeholderInfo(route)
            ComingSoon(title, step)
        }
    }
}

@Composable
private fun ComingSoon(title: String, step: String) {
    val router = LocalRouter.current
    ScreenScaffold(spacing = 22.dp, header = { NavHeader(title, onBack = { router.pop() }) }) {
        Placeholder(title, "Экран появится на шаге $step")
    }
}

private fun placeholderInfo(route: Route): Pair<String, String> = when (route) {
    Route.Sleep -> "Сон" to "3.2"
    Route.SleepRecovery -> "Восстановление" to "3.2"
    Route.Fitness -> "Фитнес" to "3.2"
    Route.FitnessWeek -> "Активность за неделю" to "3.2"
    Route.ScreenTime -> "Экранное время" to "3.2"
    Route.ScreenTimeApps -> "Приложения" to "3.2"
    Route.Nutrition -> "Питание" to "3.2"
    Route.NutritionHistory -> "История питания" to "3.2"
    Route.Tests -> "Тесты" to "3.3"
    Route.Cognitive -> "Когнитивные тесты" to "3.3"
    Route.Mood -> "Настроение и энергия" to "3.3"
    Route.Balance -> "Колесо баланса" to "3.3"
    Route.GameTests -> "Игровые тесты" to "3.3"
    Route.Statistics -> "Статистика" to "3.4"
    Route.GameStats -> "Игра" to "3.5"
    Route.WeeklyReport -> "Итоги недели" to "3.5"
    Route.PlayerPortrait -> "Игровой профиль" to "3.5"
    is Route.Calendar -> "Календарь" to "3.6"
    is Route.Event -> "Событие" to "3.6"
    Route.Lineups -> "База знаний" to "3.6"
    is Route.Lineup -> "Раскидка" to "3.6"
    Route.Players -> "Игроки" to "3.7"
    is Route.Player -> "Игрок" to "3.7"
    Route.TeamAnalytics -> "Аналитика игроков" to "3.7"
    Route.TeamCorrelations -> "Корреляции" to "3.7"
    Route.TeamFood -> "Питание игроков" to "3.7"
}
