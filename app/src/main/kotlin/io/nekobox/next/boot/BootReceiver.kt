package io.nekobox.next.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import dagger.hilt.android.AndroidEntryPoint
import io.nekobox.next.data.ProfileRepository
import io.nekobox.next.session.LogBuffer
import io.nekobox.next.vpn.NekoVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var repo: ProfileRepository
    @Inject lateinit var logs: LogBuffer

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = repo.currentSettings()
                if (!settings.bootStart) return@launch
                if (VpnService.prepare(context) != null) {
                    logs.add("开机自启需要先在系统里授权 VPN")
                    return@launch
                }
                context.startForegroundService(Intent(context, NekoVpnService::class.java).setAction(NekoVpnService.ACTION_START))
            } finally {
                pending.finish()
            }
        }
    }
}
