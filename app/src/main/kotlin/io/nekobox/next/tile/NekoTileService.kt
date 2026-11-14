package io.nekobox.next.tile

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.nekobox.next.MainActivity
import io.nekobox.next.vpn.NekoVpnService

/** Quick settings tile. If the VPN consent is missing it opens the app. */
class NekoTileService : TileService() {
    override fun onStartListening() {
        qsTile.state = Tile.STATE_INACTIVE
        qsTile.updateTile()
    }

    override fun onClick() {
        if (VpnService.prepare(this) != null) {
            val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(open)
            return
        }
        startForegroundService(Intent(this, NekoVpnService::class.java).setAction(NekoVpnService.ACTION_START))
    }
}
