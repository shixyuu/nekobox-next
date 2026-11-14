package io.nekobox.next.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.nekobox.next.engine.model.PerAppMode
import io.nekobox.next.engine.model.RunMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("nekobox_settings")

data class AppSettings(
    val mode: RunMode = RunMode.VPN,
    val tunStack: String = "mixed",
    val mtu: Int = 9000,
    val ipv6: Boolean = true,
    val dnsRemote: String = "https://1.1.1.1/dns-query",
    val dnsDirect: String = "223.5.5.5",
    val fakeDns: Boolean = false,
    val mixedPort: Int = 2080,
    val mixedUsername: String = "",
    val mixedPassword: String = "",
    val clashPort: Int = 9090,
    val clashSecret: String = "",
    val logLevel: String = "info",
    val perAppMode: PerAppMode = PerAppMode.OFF,
    val globalOverride: String = "",
    val byeDpiPort: Int = 18765,
    val byeDpiUsername: String = "",
    val byeDpiPassword: String = "",
    val selectedGroupId: String? = null,
    val selectedNodeId: String? = null,
    val bootStart: Boolean = false,
    val dynamicColor: Boolean = false,
    val testUrl: String = "https://www.gstatic.com/generate_204",
)

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.dataStore
    val flow: Flow<AppSettings> = store.data.map { it.toSettings() }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = block(prefs.toSettings())
            prefs[Keys.mode] = next.mode.name
            prefs[Keys.tunStack] = next.tunStack
            prefs[Keys.mtu] = next.mtu
            prefs[Keys.ipv6] = next.ipv6
            prefs[Keys.dnsRemote] = next.dnsRemote
            prefs[Keys.dnsDirect] = next.dnsDirect
            prefs[Keys.fakeDns] = next.fakeDns
            prefs[Keys.mixedPort] = next.mixedPort
            prefs[Keys.mixedUsername] = next.mixedUsername
            prefs[Keys.mixedPassword] = next.mixedPassword
            prefs[Keys.clashPort] = next.clashPort
            prefs[Keys.clashSecret] = next.clashSecret
            prefs[Keys.logLevel] = next.logLevel
            prefs[Keys.perAppMode] = next.perAppMode.name
            prefs[Keys.globalOverride] = next.globalOverride
            prefs[Keys.byeDpiPort] = next.byeDpiPort
            prefs[Keys.byeDpiUsername] = next.byeDpiUsername
            prefs[Keys.byeDpiPassword] = next.byeDpiPassword
            if (next.selectedGroupId == null) prefs.remove(Keys.selectedGroupId) else prefs[Keys.selectedGroupId] = next.selectedGroupId
            if (next.selectedNodeId == null) prefs.remove(Keys.selectedNodeId) else prefs[Keys.selectedNodeId] = next.selectedNodeId
            prefs[Keys.bootStart] = next.bootStart
            prefs[Keys.dynamicColor] = next.dynamicColor
            prefs[Keys.testUrl] = next.testUrl
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        mode = RunMode.entries.firstOrNull { it.name == this[Keys.mode] } ?: RunMode.VPN,
        tunStack = this[Keys.tunStack] ?: "mixed",
        mtu = this[Keys.mtu] ?: 9000,
        ipv6 = this[Keys.ipv6] ?: true,
        dnsRemote = this[Keys.dnsRemote] ?: "https://1.1.1.1/dns-query",
        dnsDirect = this[Keys.dnsDirect] ?: "223.5.5.5",
        fakeDns = this[Keys.fakeDns] ?: false,
        mixedPort = this[Keys.mixedPort] ?: 2080,
        mixedUsername = this[Keys.mixedUsername].orEmpty(),
        mixedPassword = this[Keys.mixedPassword].orEmpty(),
        clashPort = this[Keys.clashPort] ?: 9090,
        clashSecret = this[Keys.clashSecret].orEmpty(),
        logLevel = this[Keys.logLevel] ?: "info",
        perAppMode = PerAppMode.entries.firstOrNull { it.name == this[Keys.perAppMode] } ?: PerAppMode.OFF,
        globalOverride = this[Keys.globalOverride].orEmpty(),
        byeDpiPort = this[Keys.byeDpiPort] ?: 18765,
        byeDpiUsername = this[Keys.byeDpiUsername].orEmpty(),
        byeDpiPassword = this[Keys.byeDpiPassword].orEmpty(),
        selectedGroupId = this[Keys.selectedGroupId],
        selectedNodeId = this[Keys.selectedNodeId],
        bootStart = this[Keys.bootStart] ?: false,
        dynamicColor = this[Keys.dynamicColor] ?: false,
        testUrl = this[Keys.testUrl] ?: "https://www.gstatic.com/generate_204",
    )

    private object Keys {
        val mode = stringPreferencesKey("mode")
        val tunStack = stringPreferencesKey("tun_stack")
        val mtu = intPreferencesKey("mtu")
        val ipv6 = booleanPreferencesKey("ipv6")
        val dnsRemote = stringPreferencesKey("dns_remote")
        val dnsDirect = stringPreferencesKey("dns_direct")
        val fakeDns = booleanPreferencesKey("fake_dns")
        val mixedPort = intPreferencesKey("mixed_port")
        val mixedUsername = stringPreferencesKey("mixed_user")
        val mixedPassword = stringPreferencesKey("mixed_pass")
        val clashPort = intPreferencesKey("clash_port")
        val clashSecret = stringPreferencesKey("clash_secret")
        val logLevel = stringPreferencesKey("log_level")
        val perAppMode = stringPreferencesKey("per_app_mode")
        val globalOverride = stringPreferencesKey("global_override")
        val byeDpiPort = intPreferencesKey("byedpi_port")
        val byeDpiUsername = stringPreferencesKey("byedpi_user")
        val byeDpiPassword = stringPreferencesKey("byedpi_pass")
        val selectedGroupId = stringPreferencesKey("selected_group")
        val selectedNodeId = stringPreferencesKey("selected_node")
        val bootStart = booleanPreferencesKey("boot_start")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val testUrl = stringPreferencesKey("test_url")
    }
}
