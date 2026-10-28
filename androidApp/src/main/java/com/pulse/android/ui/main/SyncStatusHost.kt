package com.pulse.android.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulse.android.ui.components.ScreenBackground
import com.pulse.android.ui.theme.Palette
import com.pulse.core.PulseCore
import com.pulse.core.SyncStatus

internal const val FIRST_LOAD_TITLE = "Загружаем ваши данные…"
internal const val FIRST_LOAD_FAILED_TEXT =
    "Не удалось загрузить данные с сервера. Покажем то, что есть на телефоне, и попробуем ещё раз позже."

/**
 * What the app says about the first download after sign-in, over the signed-in UI: a full-screen loader while it runs,
 * a message once it failed. Other sync rounds are silent.
 */
@Composable
fun SyncStatusHost(core: PulseCore) {
    val status by core.syncStatus.collectAsStateWithLifecycle()
    when (status) {
        SyncStatus.FirstLoad -> FirstLoadScreen()
        SyncStatus.FirstLoadFailed -> FirstLoadFailedDialog(core::acknowledgeFirstLoadFailure)
        SyncStatus.Idle, SyncStatus.Running -> Unit
    }
}

@Composable
private fun FirstLoadScreen() {
    // Covers the screens below and keeps their taps from reaching them.
    ScreenBackground(Modifier.pointerInput(Unit) {}) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(Modifier.size(36.dp), color = Palette.cyan, strokeWidth = 3.dp)
            Text(FIRST_LOAD_TITLE, color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun FirstLoadFailedDialog(onAcknowledge: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAcknowledge,
        containerColor = Palette.tile,
        text = { Text(FIRST_LOAD_FAILED_TEXT, color = Palette.textPrimary, fontSize = 16.sp) },
        confirmButton = { TextButton(onAcknowledge) { Text("Понятно", color = Palette.cyan, fontWeight = FontWeight.SemiBold) } }
    )
}
