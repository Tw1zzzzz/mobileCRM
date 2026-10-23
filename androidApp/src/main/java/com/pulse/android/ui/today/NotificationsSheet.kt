package com.pulse.android.ui.today

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.SecondaryButton
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.color
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.feature.NotificationInbox

/** Dismissed inbox cards, remembered between launches (iOS `@AppStorage("inbox.dismissed")`). */
private object DismissedInbox {
    private const val KEY = "inbox.dismissed"
    private var state: MutableState<String>? = null

    fun state(context: Context): MutableState<String> = state ?: run {
        val prefs = context.getSharedPreferences("pulse.settings", Context.MODE_PRIVATE)
        object : MutableState<String> by mutableStateOf(prefs.getString(KEY, "") ?: "") {}.also { state = it }
    }

    fun save(context: Context, value: String) {
        state(context).value = value
        context.getSharedPreferences("pulse.settings", Context.MODE_PRIVATE).edit { putString(KEY, value) }
    }
}

@Composable
fun rememberDismissed(): MutableState<String> = DismissedInbox.state(LocalContext.current)

/** In-app inbox: swipe a card left or right to remove it. */
@Composable
fun NotificationsSheet(onClose: () -> Unit) {
    val core = observeCore()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val dismissed = rememberDismissed().value
    val items = NotificationInbox.visible(core.inboxItems(), dismissed)
    SheetScaffold("Уведомления", onClose) {
        if (items.isEmpty()) {
            EmptyStateView("bell.slash", "Новых уведомлений нет", "Здесь появятся напоминания о тренировках, тестах и сне")
            SecondaryButton("Показать снова", icon = sfIcon("arrow.counterclockwise")) { DismissedInbox.save(context, "") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Смахни карточку, чтобы убрать", color = Palette.textTertiary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("Очистить всё", color = Palette.cyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.pressable {
                    DismissedInbox.save(context, items.fold(dismissed) { raw, item -> NotificationInbox.dismissing(item.id, raw) })
                })
            }
            items.forEach { item ->
                key(item.id) {
                    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                        if (value != SwipeToDismissBoxValue.Settled) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            DismissedInbox.save(context, NotificationInbox.dismissing(item.id, rememberedRaw(context)))
                        }
                        true
                    })
                    AnimatedVisibility(true, exit = shrinkVertically() + fadeOut()) {
                        SwipeToDismissBox(state, backgroundContent = {
                            Box(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(Metrics.cardRadius)).background(Palette.red.copy(alpha = 0.85f)).padding(horizontal = 20.dp),
                                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(sfIcon("trash"), null, tint = Palette.textPrimary, modifier = Modifier.size(18.dp))
                                    Text(" Убрать", color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }) { InboxRow(item) }
                    }
                }
            }
        }
    }
}

private fun rememberedRaw(context: Context): String = DismissedInbox.state(context).value

@Composable
private fun InboxRow(item: NotificationInbox.Item) {
    Row(Modifier.pulseCard(padding = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(sfIcon(item.icon), item.tint.color, 44.dp, 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(item.body, color = Palette.textSecondary, fontSize = 14.sp)
        }
        Text(item.time, color = Palette.textTertiary, fontSize = 13.sp)
    }
}
