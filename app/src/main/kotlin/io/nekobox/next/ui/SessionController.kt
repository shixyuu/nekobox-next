package io.nekobox.next.ui

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.nekobox.next.data.ProfileRepository
import io.nekobox.next.engine.config.SingBoxConfigGenerator
import io.nekobox.next.engine.model.ByeDpiSpec
import io.nekobox.next.session.Phase
import io.nekobox.next.session.SessionStore
import io.nekobox.next.vpn.ClashClient
import io.nekobox.next.vpn.NekoVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ProfileRepository,
    private val session: SessionStore,
) {
    fun connect() {
        context.startForegroundService(Intent(context, NekoVpnService::class.java).setAction(NekoVpnService.ACTION_START))
    }

    fun disconnect() {
        context.startService(Intent(context, NekoVpnService::class.java).setAction(NekoVpnService.ACTION_STOP))
    }

    suspend fun select(groupId: String, nodeId: String) {
        repo.selectNode(groupId, nodeId)
        if (session.state.value.phase != Phase.Connected || !session.state.value.nativeCore) return
        val spec = repo.decodeNode(nodeId) ?: return
        if (spec is ByeDpiSpec) {
            context.startService(Intent(context, NekoVpnService::class.java).setAction(NekoVpnService.ACTION_RELOAD))
            return
        }
        val settings = repo.currentSettings()
        withContext(Dispatchers.IO) {
            runCatching {
                ClashClient(settings.clashPort, settings.clashSecret)
                    .select(SingBoxConfigGenerator.groupTag(groupId), SingBoxConfigGenerator.nodeTag(nodeId))
            }
        }
    }

    suspend fun measure(groupId: String, urlTest: Boolean) {
        val nodes = repo.snapshotNodes(groupId)
        val settings = repo.currentSettings()
        val connected = session.state.value.phase == Phase.Connected && session.state.value.nativeCore
        nodes.forEach { node ->
            val ms = withContext(Dispatchers.IO) {
                runCatching {
                    if (urlTest && connected) {
                        ClashClient(settings.clashPort, settings.clashSecret)
                            .delay(SingBoxConfigGenerator.nodeTag(node.id), settings.testUrl)
                    } else {
                        repo.tcpPing(node.id)
                    }
                }.getOrNull()
            }
            if (urlTest && connected && ms != null) repo.setLatency(node.id, ms)
        }
    }
}
