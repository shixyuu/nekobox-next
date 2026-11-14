package io.nekobox.next.engine.config

import io.nekobox.next.engine.model.AnyTlsSpec
import io.nekobox.next.engine.model.ByeDpiLaunch
import io.nekobox.next.engine.model.ByeDpiSpec
import io.nekobox.next.engine.model.CustomSpec
import io.nekobox.next.engine.model.GeneratedConfig
import io.nekobox.next.engine.model.GeneratorSettings
import io.nekobox.next.engine.model.GroupRecord
import io.nekobox.next.engine.model.HttpSpec
import io.nekobox.next.engine.model.Hysteria2Spec
import io.nekobox.next.engine.model.toSingBoxOutbound
import io.nekobox.next.engine.model.HysteriaSpec
import io.nekobox.next.engine.model.NodeRecord
import io.nekobox.next.engine.model.NodeSpec
import io.nekobox.next.engine.model.PerAppMode
import io.nekobox.next.engine.model.RouteRule
import io.nekobox.next.engine.model.RuleSetSource
import io.nekobox.next.engine.model.RunMode
import io.nekobox.next.engine.model.ShadowsocksSpec
import io.nekobox.next.engine.model.SocksSpec
import io.nekobox.next.engine.model.SshSpec
import io.nekobox.next.engine.model.TrojanSpec
import io.nekobox.next.engine.model.TuicSpec
import io.nekobox.next.engine.model.VlessSpec
import io.nekobox.next.engine.model.VmessSpec
import io.nekobox.next.engine.model.WireGuardSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object SingBoxConfigGenerator {
    fun nodeTag(id: String) = "node-$id"
    fun groupTag(id: String) = "group-$id"

    fun generate(
        settings: GeneratorSettings,
        nodes: List<NodeRecord>,
        groups: List<GroupRecord>,
        rules: List<RouteRule>,
        ruleSets: List<RuleSetSource> = emptyList(),
    ): GeneratedConfig {
        val byId = nodes.associateBy { it.id }
        val selected = settings.selectedNodeId?.let { byId[it] }
        val bye = activeByeDpi(settings, selected, byId)
        val outbounds = mutableListOf<JsonObject>()
        nodes.forEach { node ->
            outbounds += outbound(node, settings, byId)
        }
        outbounds += obj("type" to "direct", "tag" to "direct")
        outbounds += obj("type" to "block", "tag" to "block")
        outbounds += obj("type" to "dns", "tag" to "dns-out")

        val selectedGroup = groups.firstOrNull { it.id == settings.selectedGroupId } ?: groups.firstOrNull()
        if (selectedGroup != null && selectedGroup.nodeIds.isNotEmpty()) {
            val members = selectedGroup.nodeIds.map { nodeTag(it) }
            val defaultTag = selectedGroup.selectedNodeId?.let { nodeTag(it) }
                ?: settings.selectedNodeId?.let { nodeTag(it) }
                ?: members.first()
            outbounds += if (selectedGroup.type == "urltest") {
                buildJsonObject {
                    put("type", "urltest")
                    put("tag", groupTag(selectedGroup.id))
                    put("outbounds", jsonStrings(members))
                    put("url", "https://www.gstatic.com/generate_204")
                    put("interval", "3m")
                }
            } else {
                buildJsonObject {
                    put("type", "selector")
                    put("tag", groupTag(selectedGroup.id))
                    put("outbounds", jsonStrings(members))
                    put("default", defaultTag)
                }
            }
        }
        val finalTag = when {
            selectedGroup != null && selectedGroup.nodeIds.isNotEmpty() -> groupTag(selectedGroup.id)
            selected != null -> nodeTag(selected.id)
            else -> "direct"
        }

        val root = buildJsonObject {
            putJsonObject("log") { put("level", settings.logLevel) }
            put("dns", dns(settings, finalTag))
            put("inbounds", inbounds(settings))
            put("outbounds", JsonArray(outbounds))
            put("route", route(settings, rules, ruleSets, finalTag))
            putJsonObject("experimental") {
                putJsonObject("clash_api") {
                    put("external_controller", "127.0.0.1:${settings.clashPort}")
                    put("secret", settings.clashSecret)
                }
            }
        }
        val merged = if (settings.globalOverride.isBlank()) {
            root
        } else {
            val override = JsonMerge.parseObject(settings.globalOverride)
            JsonMerge.merge(root, override)
        }
        return GeneratedConfig(JsonMerge.encode(merged), bye)
    }

    private fun activeByeDpi(
        settings: GeneratorSettings,
        selected: NodeRecord?,
        byId: Map<String, NodeRecord>,
    ): ByeDpiLaunch? {
        val spec = when (val s = selected?.spec) {
            is ByeDpiSpec -> s
            else -> {
                val detour = s?.detourNodeId?.let { byId[it]?.spec }
                detour as? ByeDpiSpec
            }
        } ?: return null
        return ByeDpiLaunch(
            listen = "127.0.0.1:${settings.byeDpiPort}",
            username = settings.byeDpiUsername,
            password = settings.byeDpiPassword,
            args = spec.effectiveArgs(),
            hosts = spec.hosts,
        )
    }

    private fun outbound(node: NodeRecord, settings: GeneratorSettings, byId: Map<String, NodeRecord>): JsonObject {
        val tag = nodeTag(node.id)
        val base = when (val spec = node.spec) {
            is ByeDpiSpec -> socks(
                "127.0.0.1",
                settings.byeDpiPort,
                settings.byeDpiUsername,
                settings.byeDpiPassword,
            )
            is CustomSpec -> JsonMerge.parseObject(spec.outboundJson).toMutableMap().let { map ->
                buildJsonObject {
                    map.forEach { (k, v) -> put(k, v) }
                    put("tag", tag)
                }
            }
            else -> protocolOutbound(spec)
        }
        val withTag = if (node.spec is CustomSpec) base else base.with("tag", JsonPrimitive(tag))
        val detourId = node.spec.detourNodeId
        if (!detourId.isNullOrBlank() && byId.containsKey(detourId) && node.spec !is ByeDpiSpec) {
            return withTag.with("detour", JsonPrimitive(nodeTag(detourId)))
        }
        return withTag
    }

    private fun protocolOutbound(spec: NodeSpec): JsonObject = when (spec) {
        is VlessSpec -> buildJsonObject {
            put("type", "vless")
            put("server", spec.server)
            put("server_port", spec.serverPort)
            put("uuid", spec.uuid)
            if (spec.flow.isNotBlank()) put("flow", spec.flow)
            if (spec.packetEncoding.isNotBlank()) put("packet_encoding", spec.packetEncoding)
            transport(this, spec.network, spec.path, spec.host, spec.serviceName)
            tls(this, spec.tls, spec.serverName, spec.insecure, spec.alpn, spec.fingerprint, spec.realityPublicKey, spec.realityShortId)
        }
        is VmessSpec -> buildJsonObject {
            put("type", "vmess")
            put("server", spec.server)
            put("server_port", spec.serverPort)
            put("uuid", spec.uuid)
            put("security", spec.security.ifBlank { "auto" })
            put("alter_id", spec.alterId)
            transport(this, spec.network, spec.path, spec.host, spec.serviceName)
            tls(this, spec.tls, spec.serverName, spec.insecure, spec.alpn, spec.fingerprint, "", "")
        }
        is TrojanSpec -> buildJsonObject {
            put("type", "trojan")
            put("server", spec.server)
            put("server_port", spec.serverPort)
            put("password", spec.password)
            transport(this, spec.network, spec.path, spec.host, spec.serviceName)
            tls(this, spec.tls, spec.serverName.ifBlank { spec.server }, spec.insecure, spec.alpn, spec.fingerprint, "", "")
        }
        is ShadowsocksSpec -> buildJsonObject {
            put("type", "shadowsocks")
            put("server", spec.server)
            put("server_port", spec.serverPort)
            put("method", spec.method)
            put("password", spec.password)
            if (spec.plugin.isNotBlank()) put("plugin", spec.plugin)
            if (spec.pluginOpts.isNotBlank()) put("plugin_opts", spec.pluginOpts)
        }
        is Hysteria2Spec -> spec.toSingBoxOutbound()
        is HysteriaSpec -> buildJsonObject {
            put("type", "hysteria")
            put("server", spec.server)
            put("server_port", spec.serverPort)
            put("auth_str", spec.auth)
            put("up_mbps", spec.upMbps)
            put("down_mbps", spec.downMbps)
            if (spec.obfs.isNotBlank()) put("obfs", spec.obfs)
            tls(this, true, spec.serverName.ifBlank { spec.server }, spec.insecure, spec.alpn, "", "", "")
        }
        is TuicSpec -> buildJsonObject {
            put("type", "tuic")
            put("server", spec.server)
            put("server_port", spec.serverPort)
            put("uuid", spec.uuid)
            put("password", spec.password)
            put("congestion_control", spec.congestionControl)
            put("udp_relay_mode", spec.udpRelayMode)
            tls(this, true, spec.serverName.ifBlank { spec.server }, spec.insecure, spec.alpn, "", "", "")
        }
        is SocksSpec -> socks(spec.server, spec.serverPort, spec.username, spec.password, spec.version)
        is HttpSpec -> buildJsonObject {
            put("type", "http")
            put("server", spec.server)
            put("server_port", spec.serverPort)
            if (spec.username.isNotBlank()) put("username", spec.username)
            if (spec.password.isNotBlank()) put("password", spec.password)
            tls(this, spec.tls, spec.serverName, spec.insecure, "", "", "", "")
        }
        is WireGuardSpec -> buildJsonObject {
            put("type", "wireguard")
            put("server", spec.server)
            put("server_port", spec.serverPort)
            put("private_key", spec.privateKey)
            put("peer_public_key", spec.peerPublicKey)
            if (spec.preSharedKey.isNotBlank()) put("pre_shared_key", spec.preSharedKey)
            put("local_address", jsonStrings(splitList(spec.localAddress)))
            put("mtu", spec.mtu)
            val reserved = splitList(spec.reserved).mapNotNull { it.toIntOrNull() }
            if (reserved.isNotEmpty()) put("reserved", buildJsonArray { reserved.forEach { add(JsonPrimitive(it)) } })
        }
        is AnyTlsSpec -> buildJsonObject {
            put("type", "anytls")
            put("server", spec.server)
            put("server_port", spec.serverPort)
            put("password", spec.password)
            tls(this, true, spec.serverName.ifBlank { spec.server }, spec.insecure, spec.alpn, spec.fingerprint, "", "")
        }
        is SshSpec -> buildJsonObject {
            put("type", "ssh")
            put("server", spec.server)
            put("server_port", spec.serverPort)
            put("user", spec.user)
            if (spec.password.isNotBlank()) put("password", spec.password)
            if (spec.privateKey.isNotBlank()) put("private_key", spec.privateKey)
        }
        is ByeDpiSpec, is CustomSpec -> error("handled above")
    }

    private fun socks(server: String, port: Int, user: String, pass: String, version: String = "5") = buildJsonObject {
        put("type", "socks")
        put("server", server)
        put("server_port", port)
        put("version", version)
        if (user.isNotBlank()) put("username", user)
        if (pass.isNotBlank()) put("password", pass)
    }

    private fun transport(o: kotlinx.serialization.json.JsonObjectBuilder, network: String, path: String, host: String, service: String) {
        if (network.isBlank() || network == "tcp") return
        o.putJsonObject("transport") {
            put("type", network)
            if (path.isNotBlank()) put("path", path)
            if (host.isNotBlank()) {
                when (network) {
                    "ws", "http", "httpupgrade" -> putJsonObject("headers") { put("Host", host) }
                    else -> put("host", host)
                }
            }
            if (service.isNotBlank()) put("service_name", service)
        }
    }

    private fun tls(
        o: kotlinx.serialization.json.JsonObjectBuilder,
        enabled: Boolean,
        serverName: String,
        insecure: Boolean,
        alpn: String,
        fingerprint: String,
        realityPublicKey: String,
        realityShortId: String,
    ) {
        if (!enabled && realityPublicKey.isBlank()) return
        o.putJsonObject("tls") {
            put("enabled", true)
            if (serverName.isNotBlank()) put("server_name", serverName)
            if (insecure) put("insecure", true)
            val alpns = splitList(alpn)
            if (alpns.isNotEmpty()) put("alpn", jsonStrings(alpns))
            if (fingerprint.isNotBlank()) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", fingerprint)
                }
            }
            if (realityPublicKey.isNotBlank()) {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", realityPublicKey)
                    if (realityShortId.isNotBlank()) put("short_id", realityShortId)
                }
            }
        }
    }

    private fun dns(settings: GeneratorSettings, proxyTag: String) = buildJsonObject {
        putJsonArray("servers") {
            if (settings.fakeDns) {
                add(buildJsonObject {
                    put("type", "fakeip")
                    put("tag", "fakeip")
                    put("inet4_range", "198.18.0.0/15")
                    if (settings.ipv6) put("inet6_range", "fc00::/18")
                })
            }
            add(dnsServer("remote", settings.dnsRemote, proxyTag))
            add(dnsServer("direct-dns", settings.dnsDirect, "direct"))
        }
        putJsonArray("rules") {
            if (settings.fakeDns) {
                add(buildJsonObject {
                    put("query_type", jsonStrings(listOf("A", "AAAA")))
                    put("server", "fakeip")
                })
            }
        }
        put("final", "remote")
        put("strategy", if (settings.ipv6) "prefer_ipv4" else "ipv4_only")
        put("independent_cache", true)
    }

    private fun dnsServer(tag: String, raw: String, detour: String): JsonObject {
        val tls = raw.startsWith("https://") || raw.startsWith("tls://")
        val host = raw.removePrefix("https://").removePrefix("tls://").removePrefix("udp://")
            .substringBefore("/").substringBefore(":")
        return buildJsonObject {
            put("type", if (tls && raw.startsWith("https://")) "https" else if (tls) "tls" else "udp")
            put("tag", tag)
            put("server", host.ifBlank { "1.1.1.1" })
            if (raw.startsWith("https://")) put("path", "/" + raw.substringAfter("://").substringAfter("/", ""))
            put("detour", detour)
        }
    }

    private fun inbounds(settings: GeneratorSettings) = buildJsonArray {
        if (settings.mode == RunMode.VPN) {
            add(buildJsonObject {
                put("type", "tun")
                put("tag", "tun-in")
                put("address", jsonStrings(buildList {
                    add("172.19.0.1/30")
                    if (settings.ipv6) add("fdfe:dcba:9876::1/126")
                }))
                put("mtu", settings.mtu)
                put("auto_route", true)
                put("strict_route", true)
                put("stack", settings.tunStack)
                put("sniff", true)
                put("sniff_override_destination", true)
                when (settings.perAppMode) {
                    PerAppMode.ALLOW -> if (settings.perAppPackages.isNotEmpty()) {
                        put("include_package", jsonStrings(settings.perAppPackages))
                    }
                    PerAppMode.DENY -> if (settings.perAppPackages.isNotEmpty()) {
                        put("exclude_package", jsonStrings(settings.perAppPackages))
                    }
                    PerAppMode.OFF -> Unit
                }
            })
        }
        add(buildJsonObject {
            put("type", "mixed")
            put("tag", "mixed-in")
            put("listen", "127.0.0.1")
            put("listen_port", settings.mixedPort)
            // Random credentials are the default. The old client left this open.
            if (settings.mixedUsername.isNotBlank()) {
                putJsonArray("users") {
                    add(buildJsonObject {
                        put("username", settings.mixedUsername)
                        put("password", settings.mixedPassword)
                    })
                }
            }
            put("sniff", true)
        })
    }

    private fun route(
        settings: GeneratorSettings,
        rules: List<RouteRule>,
        ruleSets: List<RuleSetSource>,
        finalTag: String,
    ) = buildJsonObject {
        if (ruleSets.isNotEmpty()) {
            put("rule_set", buildJsonArray {
                ruleSets.forEach { src ->
                    add(buildJsonObject {
                        put("type", "remote")
                        put("tag", src.tag)
                        put("format", src.format)
                        put("url", src.url)
                    })
                }
            })
        }
        putJsonArray("rules") {
            add(buildJsonObject {
                put("protocol", "dns")
                put("outbound", "dns-out")
            })
            if (settings.fakeDns) {
                add(buildJsonObject {
                    put("ip_cidr", jsonStrings(listOf("198.18.0.0/15", "fc00::/18")))
                    put("outbound", finalTag)
                })
            }
            rules.filter { it.enabled }.forEach { rule ->
                add(buildJsonObject {
                    if (rule.domain.isNotEmpty()) put("domain", jsonStrings(rule.domain))
                    if (rule.domainSuffix.isNotEmpty()) put("domain_suffix", jsonStrings(rule.domainSuffix))
                    if (rule.domainKeyword.isNotEmpty()) put("domain_keyword", jsonStrings(rule.domainKeyword))
                    if (rule.ipCidr.isNotEmpty()) put("ip_cidr", jsonStrings(rule.ipCidr))
                    if (rule.port.isNotEmpty()) put("port", buildJsonArray { rule.port.forEach { add(JsonPrimitive(it)) } })
                    if (rule.packageName.isNotEmpty()) put("package_name", jsonStrings(rule.packageName))
                    if (rule.ruleSet.isNotEmpty()) put("rule_set", jsonStrings(rule.ruleSet))
                    put("outbound", when (rule.outbound) {
                        "proxy" -> finalTag
                        "bypass" -> "direct"
                        "block" -> "block"
                        else -> if (rule.outbound.startsWith("node-")) rule.outbound else "node-${rule.outbound}"
                    })
                })
            }
        }
        put("auto_detect_interface", true)
        put("final", finalTag)
    }

    private fun splitList(raw: String) = raw.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun jsonStrings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun obj(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { put(it.first, it.second) } }

    private fun JsonObject.with(key: String, value: JsonElement) = buildJsonObject {
        forEach { (k, v) -> put(k, v) }
        put(key, value)
    }
}
