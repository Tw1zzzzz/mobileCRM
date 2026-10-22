package com.pulse.android.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pulse.android.PulseApplication
import com.pulse.android.R
import com.pulse.android.platform.SleepActions
import com.pulse.core.util.Formatters

/** Quick Settings tile «Сон»: active while sleep is tracked, a tap starts or stops it. */
class SleepTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val core = (application as PulseApplication).core
        if (core.session.user.value == null) return
        SleepActions.run(this, if (core.sleep.isSleeping) SleepActions.STOP else SleepActions.START)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val core = (application as PulseApplication).core
        val start = core.sleep.activeStart
        tile.label = "Сон"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_pulse)
        tile.state = when {
            core.session.user.value == null -> Tile.STATE_UNAVAILABLE
            start != null -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        // Android 9 has no subtitle: the active/inactive state alone tells the story there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = start?.let { "с ${Formatters.time(it)}" } ?: "Начать"
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) = runCatching { requestListeningState(context, ComponentName(context, SleepTileService::class.java)) }
    }
}
