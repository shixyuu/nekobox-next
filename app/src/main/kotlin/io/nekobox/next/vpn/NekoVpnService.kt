package io.nekobox.next.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.nekobox.next.R
import io.nekobox.next.core.CorePlatform
import io.nekobox.next.core.byeDpiJson
import io.nekobox.next.core.createCoreBridge
import io.nekobox.next.data.ProfileRepository
import io.nekobox.next.session.LogBuffer
import io.nekobox.next.session.Phase
import io.nekobox.next.session.SessionStore
import io.nekobox.next.session.SessionUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.net.InetSocketAddress
import javax.inject.Inject

@AndroidEntryPoint
class NekoVpnService : android.net.VpnService() {
    @Inject lateinit var repo: ProfileRepository
    @Inject lateinit var session: SessionStore
    @Inject lateinit var logs: LogBuffer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var traffic: Job? = null
    private val bridge = createCoreBridge()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                scope.launch { reload() }
                return START_STICKY
            }
            else -> startInForeground()
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_vpn), NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, javaClass).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_connecting))
            .addAction(0, getString(R.string.action_disconnect), stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        session.update { it.copy(phase = Phase.Connecting, detail = "") }
        scope.launch { bringUp() }
    }

    private suspend fun bringUp() {
        try {
            val generated = repo.buildConfig()
            val platform = platform()
            val bye = byeDpiJson(generated.byeDpi)
            val base = filesDir.absolutePath
            if (bridge.native) {
                bridge.start(generated.singBoxJson, bye, base, base, cacheDir.absolutePath, platform)
            } else {
                if (generated.singBoxJson.contains("\"tun\"")) {
                    tun = TunOpener.openFromConfig(this, generated.singBoxJson)
                    val fd = tun
                    scope.launch(Dispatchers.IO) { drain(fd) }
                }
                bridge.start(generated.singBoxJson, bye, base, base, cacheDir.absolutePath, platform)
            }
            session.update { it.copy(phase = Phase.Connected, nativeCore = bridge.native, uploadBps = 0, downloadBps = 0) }
            logs.add(if (bridge.native) "sing-box ${bridge.version()} 已启动" else "核心未链接，TUN 已建立但流量不会被转发")
            pollTraffic()
        } catch (e: Exception) {
            logs.add("启动失败: ${e.message}")
            session.update { SessionUi(phase = Phase.Idle, detail = e.message.orEmpty()) }
            stopSelf()
        }
    }

    private suspend fun reload() {
        val generated = repo.buildConfig()
        if (bridge.native) {
            bridge.reload(generated.singBoxJson, byeDpiJson(generated.byeDpi))
            logs.add("配置已重载")
        }
    }

    private fun pollTraffic() {
        traffic?.cancel()
        if (!bridge.native) return
        traffic = scope.launch {
            val settings = repo.currentSettings()
            val clash = ClashClient(settings.clashPort, settings.clashSecret)
            while (isActive) {
                val sample = runCatching { clash.trafficOnce() }.getOrNull()
                if (sample != null) {
                    session.update { it.copy(uploadBps = sample.first, downloadBps = sample.second) }
                }
                delay(1000)
            }
        }
    }

    private fun platform() = object : CorePlatform {
        override fun protect(fd: Int) = this@NekoVpnService.protect(fd)
        override fun openTun(spec: String): Int = TunOpener.openSpec(this@NekoVpnService, spec)
        override fun log(message: String) = logs.add(message)
        override fun findProcess(protocol: Int, src: String, srcPort: Int, dst: String, dstPort: Int): String {
            if (Build.VERSION.SDK_INT < 29) return ""
            return runCatching {
                val cm = getSystemService(ConnectivityManager::class.java)
                val uid = cm.getConnectionOwnerUid(protocol, InetSocketAddress(src, srcPort), InetSocketAddress(dst, dstPort))
                val pkgs = packageManager.getPackagesForUid(uid)?.joinToString("\n").orEmpty()
                "$uid\n$pkgs"
            }.getOrDefault("")
        }
        override fun defaultInterface(): String {
            val cm = getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork ?: return ""
            return cm.getLinkProperties(network)?.interfaceName.orEmpty()
        }
    }

    override fun onDestroy() {
        traffic?.cancel()
        runCatching { bridge.stop() }
        runCatching { tun?.close() }
        tun = null
        session.update { SessionUi() }
        scope.cancel()
        super.onDestroy()
    }

    private fun drain(pfd: ParcelFileDescriptor?) {
        if (pfd == null) return
        runCatching {
            FileInputStream(pfd.fileDescriptor).use { input ->
                val buf = ByteArray(1500)
                while (true) {
                    if (input.read(buf) < 0) break
                }
            }
        }
    }

    companion object {
        const val ACTION_START = "io.nekobox.next.START"
        const val ACTION_STOP = "io.nekobox.next.STOP"
        const val ACTION_RELOAD = "io.nekobox.next.RELOAD"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 7
    }
}
