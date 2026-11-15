package com.screentrans

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 下拉快捷开关，比悬浮球更省事，也不占屏幕。 */
class QsTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        val prefs = Prefs.get(this)
        prefs.enabled = !prefs.enabled
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (Prefs.get(this).enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.updateTile()
    }
}
