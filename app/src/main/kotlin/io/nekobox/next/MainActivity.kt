package io.nekobox.next

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.nekobox.next.data.ProfileRepository
import io.nekobox.next.ui.NekoRoot
import io.nekobox.next.vpn.NekoVpnService
import io.nekobox.next.work.SubscriptionWorker
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var repo: ProfileRepository

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { repo.ensureSeeded() }
        SubscriptionWorker.schedule(this)
        setContent { NekoRoot(onConnect = ::askVpn) }
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun askVpn() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnConsent.launch(prepare) else startVpn()
    }

    private fun startVpn() {
        startForegroundService(Intent(this, NekoVpnService::class.java).setAction(NekoVpnService.ACTION_START))
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        lifecycleScope.launch {
            repo.ensureSeeded()
            val group = repo.currentSettings().selectedGroupId ?: return@launch
            when (data.scheme?.lowercase()) {
                "clash", "sn" -> {
                    val url = data.getQueryParameter("url") ?: return@launch
                    repo.addSubscription(data.host ?: "订阅", url, group)
                }
                else -> runCatching { repo.importShare(group, data.toString()) }
            }
        }
    }
}
