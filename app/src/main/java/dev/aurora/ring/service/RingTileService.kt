package dev.aurora.ring.service

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.aurora.ring.data.Prefs

class RingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val settings = Prefs.load(this)
        if (settings.running) {
            Prefs.save(this, settings.copy(running = false))
            RingService.stop(this)
        } else {
            Prefs.save(this, settings.copy(running = true))
            runCatching { RingService.start(this) }
        }
        updateTile()
    }

    private fun updateTile() {
        val running = Prefs.load(this).running
        qsTile?.apply {
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Aurora Ring"
            updateTile()
        }
    }

    companion object {
        fun refresh(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, RingTileService::class.java)
                )
            }
        }
    }
}
