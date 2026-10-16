package com.pulse.android.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseTile
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon

/** Actions behind the central «+» (players only — staff have no «+»). */
@Composable
fun QuickAddSheet(onClose: () -> Unit) {
    val core = observeCore()
    val router = LocalRouter.current
    fun go(action: () -> Unit) {
        onClose()
        action()
    }
    val actions = listOf(
        Triple(if (core.sleep.isSleeping) "Завершить сон" else "Начать сон", "moon.zzz", Palette.purple) to {
            go { core.sleep.toggleSleep(); router.show(listOf(Route.Sleep), AppTab.STATE) }
        },
        Triple("Фото еды", "camera", Palette.cyan) to { go { router.pendingFoodCapture = true; router.show(listOf(Route.Nutrition), AppTab.STATE) } },
        Triple("Настроение", "face.smiling", Palette.yellow) to { go { router.show(listOf(Route.Tests, Route.Mood), AppTab.STATE) } },
        Triple("Когн. тест", "brain.head.profile", Palette.green) to { go { router.show(listOf(Route.Tests, Route.Cognitive), AppTab.STATE) } },
        Triple("Событие", "calendar.badge.plus", Palette.orange) to { go { router.show(listOf(Route.Calendar(createNew = true)), AppTab.STATE) } },
        Triple("Тренировка", "dumbbell", Palette.pink) to { go { router.show(listOf(Route.Fitness), AppTab.STATE) } }
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Добавить", color = Palette.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        actions.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (info, action) ->
                    QuickAction(info.first, info.second, info.third, Modifier.weight(1f), action)
                }
            }
        }
    }
}

@Composable
private fun QuickAction(title: String, icon: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.pressable(onClick = onClick).pulseTile(padding = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(sfIcon(icon), tint, 40.dp, 18.dp)
        Text(title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}
