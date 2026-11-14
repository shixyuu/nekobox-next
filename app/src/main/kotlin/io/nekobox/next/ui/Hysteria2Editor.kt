package io.nekobox.next.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.nekobox.next.R
import io.nekobox.next.engine.model.HYSTERIA2_UTLS_FINGERPRINTS
import io.nekobox.next.engine.model.HYSTERIA_DEFAULT_STUN_SERVERS
import io.nekobox.next.engine.model.Hysteria2Access
import io.nekobox.next.engine.model.Hysteria2Links
import io.nekobox.next.engine.model.Hysteria2Spec

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Hysteria2Editor(
    spec: Hysteria2Spec,
    name: String,
    peers: List<Pair<String, String>>,
    selfId: String,
    issues: List<String>,
    onChange: (Hysteria2Spec) -> Unit,
) {
    var advanced by remember { mutableStateOf(issues.isNotEmpty()) }
    var copied by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val direct = stringResource(R.string.hy2_mode_direct)
    val realm = stringResource(R.string.hy2_mode_realm)
    val obfsOff = stringResource(R.string.hy2_obfs_off)
    val netBoth = stringResource(R.string.hy2_net_both)
    val bbrDefault = stringResource(R.string.hy2_bbr_default)
    val ipBoth = stringResource(R.string.hy2_ip_both)
    val omit = stringResource(R.string.hy2_omit)
    val on = stringResource(R.string.hy2_on)
    val off = stringResource(R.string.hy2_off)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        issues.forEach { code ->
            Text(issueText(code), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }) {
            toggleableItem(
                checked = spec.access == Hysteria2Access.DIRECT,
                label = direct,
                onCheckedChange = { onChange(spec.asDirect()) },
            )
            toggleableItem(
                checked = spec.access == Hysteria2Access.REALM,
                label = realm,
                onCheckedChange = { onChange(spec.asRealm()) },
            )
        }
        if (spec.access == Hysteria2Access.DIRECT) {
            textField(spec.server, { onChange(spec.copy(server = it.trim())) }, R.string.hy2_server, R.string.hy2_help_server)
            textField(
                if (spec.serverPorts.isNotEmpty()) "" else spec.serverPort.takeIf { it > 0 }?.toString().orEmpty(),
                { raw -> onChange(spec.copy(serverPort = raw.toIntOrNull() ?: 0, serverPorts = emptyList())) },
                R.string.hy2_port,
                R.string.hy2_help_port,
                enabled = spec.serverPorts.isEmpty(),
                number = true,
            )
            Text(stringResource(R.string.hy2_ports), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.hy2_help_ports), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            spec.serverPorts.forEachIndexed { index, range ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = range.substringBefore(':'),
                        onValueChange = { onChange(spec.withRange(index, it.filter { ch -> ch.isDigit() }, range.substringAfter(':', ""))) },
                        label = { Text(stringResource(R.string.hy2_range_start)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = range.substringAfter(':', ""),
                        onValueChange = { onChange(spec.withRange(index, range.substringBefore(':'), it.filter { ch -> ch.isDigit() })) },
                        label = { Text(stringResource(R.string.hy2_range_end)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    TextButton(onClick = { onChange(spec.withoutRange(index)) }) { Text(stringResource(R.string.hy2_remove)) }
                }
            }
            TextButton(onClick = {
                onChange(spec.copy(serverPorts = spec.serverPorts + ":", serverPort = 0))
            }) { Text(stringResource(R.string.hy2_add_range)) }
        } else {
            textField(spec.realmServerUrl, { onChange(spec.copy(realmServerUrl = it.trim())) }, R.string.hy2_realm_url, R.string.hy2_help_realm_url, since114 = true)
            textField(spec.realmToken, { onChange(spec.copy(realmToken = it)) }, R.string.hy2_realm_token, R.string.hy2_help_realm_token, since114 = true)
            textField(spec.realmId, { onChange(spec.copy(realmId = it.trim())) }, R.string.hy2_realm_id, R.string.hy2_help_realm_id, since114 = true)
            textField(
                spec.stunServers.joinToString("\n"),
                { raw -> onChange(spec.copy(stunServers = raw.lines().map { it.trim() }.filter { it.isNotEmpty() })) },
                R.string.hy2_stun,
                R.string.hy2_help_stun,
                since114 = true,
                singleLine = false,
            )
            TextButton(onClick = { onChange(spec.copy(stunServers = HYSTERIA_DEFAULT_STUN_SERVERS)) }) {
                Text(stringResource(R.string.hy2_stun_reset))
            }
        }
        textField(spec.password, { onChange(spec.copy(password = it)) }, R.string.hy2_password, R.string.hy2_help_password)
        Text(stringResource(R.string.hy2_obfs), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.hy2_help_obfs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }) {
            toggleableItem(checked = spec.obfs.isEmpty(), label = obfsOff, onCheckedChange = { onChange(spec.copy(obfs = "", obfsMinPacketSize = 0, obfsMaxPacketSize = 0)) })
            toggleableItem(checked = spec.obfs == "salamander", label = "salamander", onCheckedChange = { onChange(spec.copy(obfs = "salamander", obfsMinPacketSize = 0, obfsMaxPacketSize = 0)) })
            toggleableItem(checked = spec.obfs == "gecko", label = "gecko", onCheckedChange = { onChange(spec.copy(obfs = "gecko")) })
        }
        if (spec.obfs.isNotBlank()) {
            textField(spec.obfsPassword, { onChange(spec.copy(obfsPassword = it)) }, R.string.hy2_obfs_password, R.string.hy2_help_obfs_password)
        }
        textField(spec.upMbps.takeIf { it > 0 }?.toString().orEmpty(), { onChange(spec.copy(upMbps = it.toIntOrNull() ?: 0)) }, R.string.hy2_up, R.string.hy2_help_bandwidth, number = true)
        textField(spec.downMbps.takeIf { it > 0 }?.toString().orEmpty(), { onChange(spec.copy(downMbps = it.toIntOrNull() ?: 0)) }, R.string.hy2_down, R.string.hy2_help_bandwidth, number = true)
        TextButton(onClick = { advanced = !advanced }) {
            Text(stringResource(if (advanced) R.string.hy2_advanced_hide else R.string.hy2_advanced_show))
        }
        if (advanced) {
            if (spec.access == Hysteria2Access.DIRECT) {
                textField(spec.hopInterval, { onChange(spec.copy(hopInterval = it.trim())) }, R.string.hy2_hop, R.string.hy2_help_hop, enabled = spec.serverPorts.isNotEmpty())
                textField(spec.hopIntervalMax, { onChange(spec.copy(hopIntervalMax = it.trim())) }, R.string.hy2_hop_max, R.string.hy2_help_hop_max, enabled = spec.serverPorts.isNotEmpty(), since114 = true)
            }
            Text(stringResource(R.string.hy2_bbr), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.hy2_help_bbr), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }) {
                toggleableItem(checked = spec.bbrProfile.isEmpty(), label = bbrDefault, onCheckedChange = { onChange(spec.copy(bbrProfile = "")) })
                toggleableItem(checked = spec.bbrProfile == "conservative", label = "conservative", onCheckedChange = { onChange(spec.copy(bbrProfile = "conservative")) })
                toggleableItem(checked = spec.bbrProfile == "standard", label = "standard", onCheckedChange = { onChange(spec.copy(bbrProfile = "standard")) })
                toggleableItem(checked = spec.bbrProfile == "aggressive", label = "aggressive", onCheckedChange = { onChange(spec.copy(bbrProfile = "aggressive")) })
            }
            Text(stringResource(R.string.hy2_network), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.hy2_help_network), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }) {
                toggleableItem(checked = spec.network.isEmpty(), label = netBoth, onCheckedChange = { onChange(spec.copy(network = "")) })
                toggleableItem(checked = spec.network == "tcp", label = "tcp", onCheckedChange = { onChange(spec.copy(network = "tcp")) })
                toggleableItem(checked = spec.network == "udp", label = "udp", onCheckedChange = { onChange(spec.copy(network = "udp")) })
            }
            toggle(spec.brutalDebug, R.string.hy2_brutal, R.string.hy2_help_brutal) { onChange(spec.copy(brutalDebug = it)) }
            toggle(spec.disableChromeParrot, R.string.hy2_parrot, R.string.hy2_help_parrot, since114 = true) { onChange(spec.copy(disableChromeParrot = it)) }
            if (spec.obfs == "gecko") {
                textField(spec.obfsMinPacketSize.takeIf { it > 0 }?.toString().orEmpty(), { onChange(spec.copy(obfsMinPacketSize = it.toIntOrNull() ?: 0)) }, R.string.hy2_min_packet, R.string.hy2_help_gecko, number = true, since114 = true)
                textField(spec.obfsMaxPacketSize.takeIf { it > 0 }?.toString().orEmpty(), { onChange(spec.copy(obfsMaxPacketSize = it.toIntOrNull() ?: 0)) }, R.string.hy2_max_packet, R.string.hy2_help_gecko, number = true, since114 = true)
            }
            Text(stringResource(R.string.hy2_section_tls), style = MaterialTheme.typography.titleMedium)
            textField(spec.serverName, { onChange(spec.copy(serverName = it.trim())) }, R.string.hy2_sni, R.string.hy2_help_sni)
            toggle(spec.disableSni, R.string.hy2_disable_sni, R.string.hy2_help_sni) { onChange(spec.copy(disableSni = it)) }
            toggle(spec.insecure, R.string.hy2_insecure, R.string.hy2_help_insecure) { onChange(spec.copy(insecure = it)) }
            textField(spec.alpn, { onChange(spec.copy(alpn = it.trim())) }, R.string.hy2_alpn, R.string.hy2_help_alpn)
            textField(spec.minVersion, { onChange(spec.copy(minVersion = it.trim())) }, R.string.hy2_min_tls, R.string.hy2_help_tls_version)
            textField(spec.maxVersion, { onChange(spec.copy(maxVersion = it.trim())) }, R.string.hy2_max_tls, R.string.hy2_help_tls_version)
            textField(spec.cipherSuites, { onChange(spec.copy(cipherSuites = it.trim())) }, R.string.hy2_ciphers, R.string.hy2_help_ciphers)
            textField(spec.curvePreferences, { onChange(spec.copy(curvePreferences = it.trim())) }, R.string.hy2_curves, R.string.hy2_help_curves)
            textField(spec.certificate, { onChange(spec.copy(certificate = it)) }, R.string.hy2_ca, R.string.hy2_help_ca, singleLine = false)
            textField(spec.certificatePath, { onChange(spec.copy(certificatePath = it.trim())) }, R.string.hy2_ca_path, R.string.hy2_help_ca)
            textField(spec.certificatePublicKeySha256, { onChange(spec.copy(certificatePublicKeySha256 = it.trim())) }, R.string.hy2_pin, R.string.hy2_help_pin)
            textField(spec.clientCertificate, { onChange(spec.copy(clientCertificate = it)) }, R.string.hy2_client_cert, R.string.hy2_help_client, singleLine = false)
            textField(spec.clientCertificatePath, { onChange(spec.copy(clientCertificatePath = it.trim())) }, R.string.hy2_client_cert_path, R.string.hy2_help_client)
            textField(spec.clientKey, { onChange(spec.copy(clientKey = it)) }, R.string.hy2_client_key, R.string.hy2_help_client, singleLine = false)
            textField(spec.clientKeyPath, { onChange(spec.copy(clientKeyPath = it.trim())) }, R.string.hy2_client_key_path, R.string.hy2_help_client)
            textField(spec.handshakeTimeout, { onChange(spec.copy(handshakeTimeout = it.trim())) }, R.string.hy2_handshake, R.string.hy2_help_handshake)
            toggle(spec.fragment, R.string.hy2_fragment, R.string.hy2_help_handshake) { onChange(spec.copy(fragment = it)) }
            textField(spec.fragmentFallbackDelay, { onChange(spec.copy(fragmentFallbackDelay = it.trim())) }, R.string.hy2_fragment_delay, R.string.hy2_help_handshake)
            toggle(spec.recordFragment, R.string.hy2_record_fragment, R.string.hy2_help_handshake) { onChange(spec.copy(recordFragment = it)) }
            toggle(spec.echEnabled, R.string.hy2_ech, R.string.hy2_help_ech) { onChange(spec.copy(echEnabled = it)) }
            if (spec.echEnabled || spec.echConfig.isNotBlank() || spec.echConfigPath.isNotBlank()) {
                textField(spec.echConfig, { onChange(spec.copy(echConfig = it, echEnabled = true)) }, R.string.hy2_ech_config, R.string.hy2_help_ech, singleLine = false)
                textField(spec.echConfigPath, { onChange(spec.copy(echConfigPath = it.trim(), echEnabled = true)) }, R.string.hy2_ech_path, R.string.hy2_help_ech)
                textField(spec.echQueryServerName, { onChange(spec.copy(echQueryServerName = it.trim())) }, R.string.hy2_ech_sni, R.string.hy2_help_ech)
            }
            toggle(spec.utlsEnabled, R.string.hy2_utls, R.string.hy2_help_utls) { onChange(spec.copy(utlsEnabled = it)) }
            fingerprintMenu(spec.utlsFingerprint) { onChange(spec.copy(utlsFingerprint = it, utlsEnabled = it.isNotBlank() || spec.utlsEnabled)) }
            Text(stringResource(R.string.hy2_section_quic), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.hy2_help_quic), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            textField(spec.idleTimeout, { onChange(spec.copy(idleTimeout = it.trim())) }, R.string.hy2_idle, R.string.hy2_help_quic)
            textField(spec.keepAlivePeriod, { onChange(spec.copy(keepAlivePeriod = it.trim())) }, R.string.hy2_keepalive, R.string.hy2_help_quic)
            textField(spec.streamReceiveWindow, { onChange(spec.copy(streamReceiveWindow = it.trim())) }, R.string.hy2_stream_window, R.string.hy2_help_windows)
            textField(spec.connectionReceiveWindow, { onChange(spec.copy(connectionReceiveWindow = it.trim())) }, R.string.hy2_conn_window, R.string.hy2_help_windows)
            textField(spec.maxConcurrentStreams.takeIf { it > 0 }?.toString().orEmpty(), { onChange(spec.copy(maxConcurrentStreams = it.toIntOrNull() ?: 0)) }, R.string.hy2_streams, R.string.hy2_help_quic, number = true)
            textField(spec.initialPacketSize.takeIf { it > 0 }?.toString().orEmpty(), { onChange(spec.copy(initialPacketSize = it.toIntOrNull() ?: 0)) }, R.string.hy2_initial_packet, R.string.hy2_help_quic, number = true)
            toggle(spec.disablePathMtuDiscovery, R.string.hy2_disable_pmtu, R.string.hy2_help_quic) { onChange(spec.copy(disablePathMtuDiscovery = it)) }
            Text(stringResource(R.string.hy2_section_dial), style = MaterialTheme.typography.titleMedium)
            detourMenu(spec.detourNodeId, peers.filter { it.first != selfId }) { onChange(spec.copy(detourNodeId = it)) }
            val dialOn = spec.detourNodeId.isNullOrBlank()
            if (!dialOn) {
                Text(stringResource(R.string.hy2_dial_ignored), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            textField(spec.domainResolver, { onChange(spec.copy(domainResolver = it.trim())) }, R.string.hy2_resolver, R.string.hy2_help_resolver, enabled = dialOn)
            textField(spec.domainResolverStrategy, { onChange(spec.copy(domainResolverStrategy = it.trim())) }, R.string.hy2_resolver_strategy, R.string.hy2_help_strategy, enabled = dialOn)
            textField(spec.domainResolverTimeout, { onChange(spec.copy(domainResolverTimeout = it.trim())) }, R.string.hy2_resolver_timeout, R.string.hy2_help_handshake, enabled = dialOn)
            toggle(spec.domainResolverDisableCache, R.string.hy2_resolver_nocache, R.string.hy2_help_resolver, enabled = dialOn) { onChange(spec.copy(domainResolverDisableCache = it)) }
            toggle(spec.domainResolverDisableOptimisticCache, R.string.hy2_resolver_noopt, R.string.hy2_help_resolver, enabled = dialOn) { onChange(spec.copy(domainResolverDisableOptimisticCache = it)) }
            textField(spec.connectTimeout, { onChange(spec.copy(connectTimeout = it.trim())) }, R.string.hy2_connect_timeout, R.string.hy2_help_handshake, enabled = dialOn)
            Text(stringResource(R.string.hy2_udp_fragment), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.hy2_help_udp_fragment), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }) {
                toggleableItem(checked = spec.udpFragment == null, label = omit, onCheckedChange = { if (dialOn) onChange(spec.copy(udpFragment = null)) })
                toggleableItem(checked = spec.udpFragment == true, label = on, onCheckedChange = { if (dialOn) onChange(spec.copy(udpFragment = true)) })
                toggleableItem(checked = spec.udpFragment == false, label = off, onCheckedChange = { if (dialOn) onChange(spec.copy(udpFragment = false)) })
            }
            textField(spec.bindInterface, { onChange(spec.copy(bindInterface = it.trim())) }, R.string.hy2_bind_iface, R.string.hy2_help_bind, enabled = dialOn)
            textField(spec.inet4BindAddress, { onChange(spec.copy(inet4BindAddress = it.trim())) }, R.string.hy2_bind4, R.string.hy2_help_bind, enabled = dialOn)
            textField(spec.inet6BindAddress, { onChange(spec.copy(inet6BindAddress = it.trim())) }, R.string.hy2_bind6, R.string.hy2_help_bind, enabled = dialOn)
            textField(spec.networkStrategy, { onChange(spec.copy(networkStrategy = it.trim())) }, R.string.hy2_net_strategy, R.string.hy2_help_net_strategy, enabled = dialOn)
            textField(spec.networkType, { onChange(spec.copy(networkType = it.trim())) }, R.string.hy2_net_type, R.string.hy2_help_net_type, enabled = dialOn)
            textField(spec.fallbackNetworkType, { onChange(spec.copy(fallbackNetworkType = it.trim())) }, R.string.hy2_net_fallback, R.string.hy2_help_net_type, enabled = dialOn)
            textField(spec.fallbackDelay, { onChange(spec.copy(fallbackDelay = it.trim())) }, R.string.hy2_fallback_delay, R.string.hy2_help_fallback, enabled = dialOn)
            if (spec.access == Hysteria2Access.REALM) {
                Text(stringResource(R.string.hy2_ip_version), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.hy2_help_ip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }) {
                    toggleableItem(checked = spec.realmIpVersion == 0, label = ipBoth, onCheckedChange = { onChange(spec.copy(realmIpVersion = 0)) })
                    toggleableItem(checked = spec.realmIpVersion == 4, label = "4", onCheckedChange = { onChange(spec.copy(realmIpVersion = 4)) })
                    toggleableItem(checked = spec.realmIpVersion == 6, label = "6", onCheckedChange = { onChange(spec.copy(realmIpVersion = 6, portMappingEnabled = false)) })
                }
                toggle(spec.portMappingEnabled, R.string.hy2_portmap, R.string.hy2_help_portmap, enabled = spec.realmIpVersion != 6, since114 = true) {
                    onChange(spec.copy(portMappingEnabled = it))
                }
                if (spec.portMappingEnabled) {
                    textField(spec.portMappingTimeout, { onChange(spec.copy(portMappingTimeout = it.trim())) }, R.string.hy2_portmap_timeout, R.string.hy2_help_portmap, since114 = true)
                    textField(spec.portMappingLifetime, { onChange(spec.copy(portMappingLifetime = it.trim())) }, R.string.hy2_portmap_life, R.string.hy2_help_portmap, since114 = true)
                }
                textField(spec.httpClientVersion.takeIf { it > 0 }?.toString().orEmpty(), { onChange(spec.copy(httpClientVersion = it.toIntOrNull() ?: 0, realmHttpClientTag = if (it.isNotBlank()) "" else spec.realmHttpClientTag)) }, R.string.hy2_http_version, R.string.hy2_help_http, number = true, since114 = true)
                textField(spec.realmHttpClientTag, { onChange(spec.copy(realmHttpClientTag = it.trim(), httpClientVersion = if (it.isNotBlank()) 0 else spec.httpClientVersion)) }, R.string.hy2_http_tag, R.string.hy2_help_http, since114 = true)
            }
        }
        TextButton(onClick = {
            copied = runCatching { Hysteria2Links.format(name, spec) }.getOrNull()?.also {
                clipboard.setText(AnnotatedString(it))
            }?.let { stringResourceCopied() } ?: "fail"
        }) { Text(stringResource(R.string.hy2_copy)) }
        if (copied == "ok") Text(stringResource(R.string.hy2_copied), color = MaterialTheme.colorScheme.primary)
        if (copied == "fail") Text(stringResource(R.string.hy2_copy_failed), color = MaterialTheme.colorScheme.error)
    }
}

private fun stringResourceCopied() = "ok"

@Composable
private fun issueText(code: String): String = when (code) {
    "realm_conflicts_server" -> stringResource(R.string.hy2_err_realm_conflicts_server)
    "realm_url_required" -> stringResource(R.string.hy2_err_realm_url_required)
    "realm_id_required" -> stringResource(R.string.hy2_err_realm_id_required)
    "stun_required" -> stringResource(R.string.hy2_err_stun_required)
    "bad_ip_version" -> stringResource(R.string.hy2_err_bad_ip_version)
    "port_mapping_ipv6" -> stringResource(R.string.hy2_err_port_mapping_ipv6)
    "server_required" -> stringResource(R.string.hy2_err_server_required)
    "port_required" -> stringResource(R.string.hy2_err_port_required)
    "ports_conflict" -> stringResource(R.string.hy2_err_ports_conflict)
    "bad_port_range" -> stringResource(R.string.hy2_err_bad_port_range)
    "hop_without_ports" -> stringResource(R.string.hy2_err_hop_without_ports)
    "bad_obfs" -> stringResource(R.string.hy2_err_bad_obfs)
    "obfs_password_required" -> stringResource(R.string.hy2_err_obfs_password_required)
    "gecko_size" -> stringResource(R.string.hy2_err_gecko_size)
    "bad_network" -> stringResource(R.string.hy2_err_bad_network)
    "bad_bbr" -> stringResource(R.string.hy2_err_bad_bbr)
    "bad_tls_version" -> stringResource(R.string.hy2_err_bad_tls_version)
    "bad_fingerprint" -> stringResource(R.string.hy2_err_bad_fingerprint)
    "bad_strategy" -> stringResource(R.string.hy2_err_bad_strategy)
    "bad_duration" -> stringResource(R.string.hy2_err_bad_duration)
    "bad_network_strategy" -> stringResource(R.string.hy2_err_bad_network_strategy)
    "bad_network_type" -> stringResource(R.string.hy2_err_bad_network_type)
    "strategy_conflicts_bind" -> stringResource(R.string.hy2_err_strategy_conflicts_bind)
    "fallback_without_strategy" -> stringResource(R.string.hy2_err_fallback_without_strategy)
    "http_client_conflict" -> stringResource(R.string.hy2_err_http_client_conflict)
    else -> code
}

@Composable
private fun textField(
    value: String,
    onValue: (String) -> Unit,
    label: Int,
    help: Int,
    enabled: Boolean = true,
    number: Boolean = false,
    singleLine: Boolean = true,
    since114: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        label = { FieldLabel(stringResource(label), since114) },
        supportingText = { Text(stringResource(help)) },
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
    )
}

@Composable
private fun FieldLabel(text: String, since114: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text)
        if (since114) Text(stringResource(R.string.hy2_since_114), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun toggle(checked: Boolean, label: Int, help: Int, enabled: Boolean = true, since114: Boolean = false, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            FieldLabel(stringResource(label), since114)
            Text(stringResource(help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

@Composable
private fun fingerprintMenu(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.hy2_fingerprint) + ": " + current.ifBlank { stringResource(R.string.hy2_omit) })
        }
        Text(stringResource(R.string.hy2_help_utls), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.hy2_omit)) }, onClick = { open = false; onPick("") })
            HYSTERIA2_UTLS_FINGERPRINTS.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(name) })
            }
        }
    }
}

@Composable
private fun detourMenu(current: String?, peers: List<Pair<String, String>>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = peers.firstOrNull { it.first == current }?.second ?: stringResource(R.string.hy2_detour_none)
    Column {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.hy2_detour) + ": " + label) }
        Text(stringResource(R.string.hy2_help_detour), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.hy2_detour_none)) }, onClick = { open = false; onPick(null) })
            peers.forEach { (id, peerName) ->
                DropdownMenuItem(text = { Text(peerName) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}

private fun Hysteria2Spec.asDirect(): Hysteria2Spec = copy(
    access = Hysteria2Access.DIRECT,
    serverPort = if (serverPorts.isNotEmpty()) 0 else if (serverPort == 0) 443 else serverPort,
    realmServerUrl = "",
    realmToken = "",
    realmId = "",
)

private fun Hysteria2Spec.asRealm(): Hysteria2Spec = copy(
    access = Hysteria2Access.REALM,
    server = "",
    serverPort = 0,
    serverPorts = emptyList(),
    hopInterval = "",
    hopIntervalMax = "",
)

private fun Hysteria2Spec.withRange(index: Int, start: String, end: String): Hysteria2Spec {
    val next = serverPorts.toMutableList()
    next[index] = "$start:$end"
    return copy(serverPorts = next, serverPort = 0)
}

private fun Hysteria2Spec.withoutRange(index: Int): Hysteria2Spec {
    val next = serverPorts.filterIndexed { i, _ -> i != index }
    return copy(
        serverPorts = next,
        serverPort = if (next.isEmpty() && serverPort == 0) 443 else serverPort,
        hopInterval = if (next.isEmpty()) "" else hopInterval,
        hopIntervalMax = if (next.isEmpty()) "" else hopIntervalMax,
    )
}
