package io.nekobox.next.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * Official Hysteria client defaults from apernet/hysteria `app/cmd/client.go`
 * (`defaultRealmSTUNServers`). sing-box requires `realm.stun_servers`.
 */
val HYSTERIA_DEFAULT_STUN_SERVERS = listOf(
    "stun.nextcloud.com:3478",
    "stun.sip.us:3478",
    "global.stun.twilio.com:3478",
)

val HYSTERIA2_UTLS_FINGERPRINTS = listOf(
    "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk",
    "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized",
)

@Serializable
enum class Hysteria2Access {
    @SerialName("direct")
    DIRECT,

    @SerialName("realm")
    REALM,
}

/**
 * sing-box 1.14.4 `option.Hysteria2OutboundOptions` plus the outbound TLS, QUIC
 * and dial fields that struct embeds.
 *
 * Linux-only dial knobs (`netns`, `routing_mark`, `bind_address_no_port`) and
 * TLS spoof / kernel options are omitted: they do not apply on Android.
 * `upMbps` / `downMbps` of 0 means "leave empty", which selects BBR.
 * `serverPort` of 0 with a non-empty [serverPorts] means the single port is omitted.
 */
@Serializable
@SerialName("hysteria2")
data class Hysteria2Spec(
    val access: Hysteria2Access = Hysteria2Access.DIRECT,
    val server: String = "",
    val serverPort: Int = 443,
    val serverPorts: List<String> = emptyList(),
    val hopInterval: String = "",
    val hopIntervalMax: String = "",
    val upMbps: Int = 0,
    val downMbps: Int = 0,
    val bbrProfile: String = "",
    val obfs: String = "",
    val obfsPassword: String = "",
    val obfsMinPacketSize: Int = 0,
    val obfsMaxPacketSize: Int = 0,
    val password: String = "",
    val network: String = "",
    val disableChromeParrot: Boolean = false,
    val brutalDebug: Boolean = false,
    val serverName: String = "",
    val disableSni: Boolean = false,
    val insecure: Boolean = false,
    val alpn: String = "",
    val minVersion: String = "",
    val maxVersion: String = "",
    val cipherSuites: String = "",
    val curvePreferences: String = "",
    val certificate: String = "",
    val certificatePath: String = "",
    val certificatePublicKeySha256: String = "",
    val clientCertificate: String = "",
    val clientCertificatePath: String = "",
    val clientKey: String = "",
    val clientKeyPath: String = "",
    val handshakeTimeout: String = "",
    val fragment: Boolean = false,
    val fragmentFallbackDelay: String = "",
    val recordFragment: Boolean = false,
    val echEnabled: Boolean = false,
    val echConfig: String = "",
    val echConfigPath: String = "",
    val echQueryServerName: String = "",
    val utlsEnabled: Boolean = false,
    val utlsFingerprint: String = "",
    val idleTimeout: String = "",
    val keepAlivePeriod: String = "",
    val streamReceiveWindow: String = "",
    val connectionReceiveWindow: String = "",
    val maxConcurrentStreams: Int = 0,
    val initialPacketSize: Int = 0,
    val disablePathMtuDiscovery: Boolean = false,
    val domainResolver: String = "",
    val domainResolverStrategy: String = "",
    val domainResolverTimeout: String = "",
    val domainResolverDisableCache: Boolean = false,
    val domainResolverDisableOptimisticCache: Boolean = false,
    val connectTimeout: String = "",
    val udpFragment: Boolean? = null,
    val bindInterface: String = "",
    val inet4BindAddress: String = "",
    val inet6BindAddress: String = "",
    val networkStrategy: String = "",
    val networkType: String = "",
    val fallbackNetworkType: String = "",
    val fallbackDelay: String = "",
    val realmServerUrl: String = "",
    val realmToken: String = "",
    val realmId: String = "",
    val stunServers: List<String> = HYSTERIA_DEFAULT_STUN_SERVERS,
    val realmIpVersion: Int = 0,
    val portMappingEnabled: Boolean = false,
    val portMappingTimeout: String = "",
    val portMappingLifetime: String = "",
    val httpClientVersion: Int = 0,
    val realmHttpClientTag: String = "",
    override val detourNodeId: String? = null,
) : NodeSpec

object Hysteria2Check {
    private val duration = Regex("""^(\d+(\.\d+)?(ns|us|µs|μs|ms|s|m|h))+$""")
    private val versions = setOf("", "1.0", "1.1", "1.2", "1.3")
    private val profiles = setOf("", "conservative", "standard", "aggressive")
    private val strategies = setOf("", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")
    private val networkStrategies = setOf("", "default", "hybrid", "fallback")
    private val interfaceTypes = setOf("wifi", "cellular", "ethernet", "other")

    fun issues(spec: Hysteria2Spec): List<String> {
        val out = mutableListOf<String>()
        if (spec.access == Hysteria2Access.REALM) {
            if (spec.server.isNotBlank() || spec.serverPort != 0 || spec.serverPorts.isNotEmpty()) {
                out += "realm_conflicts_server"
            }
            if (!isHttpUrl(spec.realmServerUrl)) out += "realm_url_required"
            if (spec.realmId.isBlank()) out += "realm_id_required"
            if (spec.stunServers.none { it.isNotBlank() }) out += "stun_required"
            if (spec.realmIpVersion !in setOf(0, 4, 6)) out += "bad_ip_version"
            if (spec.portMappingEnabled && spec.realmIpVersion == 6) out += "port_mapping_ipv6"
        } else {
            if (spec.server.isBlank()) out += "server_required"
            if (spec.serverPorts.isEmpty() && spec.serverPort !in 1..65535) out += "port_required"
            if (spec.serverPorts.isNotEmpty() && spec.serverPort != 0) out += "ports_conflict"
            if (spec.serverPorts.any { !validPortRange(it) }) out += "bad_port_range"
            if ((spec.hopInterval.isNotBlank() || spec.hopIntervalMax.isNotBlank()) && spec.serverPorts.isEmpty()) {
                out += "hop_without_ports"
            }
        }
        if (spec.obfs.isNotBlank() && spec.obfs !in setOf("salamander", "gecko")) out += "bad_obfs"
        if (spec.obfs.isNotBlank() && spec.obfsPassword.isBlank()) out += "obfs_password_required"
        if (spec.obfs == "gecko" && spec.obfsMinPacketSize > 0 && spec.obfsMaxPacketSize > 0 &&
            spec.obfsMinPacketSize > spec.obfsMaxPacketSize
        ) {
            out += "gecko_size"
        }
        if (spec.network !in setOf("", "tcp", "udp")) out += "bad_network"
        if (spec.bbrProfile !in profiles) out += "bad_bbr"
        if (spec.minVersion !in versions || spec.maxVersion !in versions) out += "bad_tls_version"
        if (spec.utlsFingerprint.isNotBlank() && spec.utlsFingerprint !in HYSTERIA2_UTLS_FINGERPRINTS) out += "bad_fingerprint"
        if (spec.domainResolverStrategy !in strategies) out += "bad_strategy"
        if (spec.networkStrategy !in networkStrategies) out += "bad_network_strategy"
        splitCsv(spec.networkType).forEach { if (it !in interfaceTypes) out += "bad_network_type" }
        splitCsv(spec.fallbackNetworkType).forEach { if (it !in interfaceTypes) out += "bad_network_type" }
        val dialBlocked = !spec.detourNodeId.isNullOrBlank()
        if (!dialBlocked && spec.networkStrategy.isNotBlank() &&
            (spec.bindInterface.isNotBlank() || spec.inet4BindAddress.isNotBlank() || spec.inet6BindAddress.isNotBlank())
        ) {
            out += "strategy_conflicts_bind"
        }
        if (spec.fallbackDelay.isNotBlank() && spec.networkStrategy.isBlank() && spec.domainResolverStrategy.isBlank()) {
            out += "fallback_without_strategy"
        }
        if (spec.realmHttpClientTag.isNotBlank() && spec.httpClientVersion != 0) out += "http_client_conflict"
        listOf(
            spec.hopInterval, spec.hopIntervalMax, spec.handshakeTimeout, spec.idleTimeout,
            spec.keepAlivePeriod, spec.connectTimeout, spec.domainResolverTimeout,
            spec.portMappingTimeout, spec.portMappingLifetime, spec.fragmentFallbackDelay,
            spec.fallbackDelay,
        ).forEach { if (it.isNotBlank() && !duration.matches(it)) out += "bad_duration" }
        return out.distinct()
    }

    /** sing-quic `hysteria.ParsePorts`: every entry must contain one colon. */
    fun validPortRange(raw: String): Boolean {
        val text = raw.trim()
        val colon = text.indexOf(':')
        if (colon < 0 || text.indexOf(':', colon + 1) >= 0) return false
        val startText = text.substring(0, colon)
        val endText = text.substring(colon + 1)
        val start = startText.toIntOrNull() ?: if (startText.isEmpty()) 0 else return false
        val end = endText.toIntOrNull() ?: if (endText.isEmpty()) 65535 else return false
        return start in 0..65535 && end in 0..65535 && start <= end
    }
}

fun Hysteria2Spec.toSingBoxOutbound(): JsonObject = buildJsonObject {
    put("type", "hysteria2")
    if (access == Hysteria2Access.REALM) {
        put("realm", realmJson())
    } else {
        put("server", server)
        if (serverPorts.isNotEmpty()) {
            put("server_ports", stringArray(serverPorts))
        } else {
            put("server_port", serverPort)
        }
        if (hopInterval.isNotBlank()) put("hop_interval", hopInterval)
        if (hopIntervalMax.isNotBlank()) put("hop_interval_max", hopIntervalMax)
    }
    if (password.isNotBlank()) put("password", password)
    if (upMbps > 0) put("up_mbps", upMbps)
    if (downMbps > 0) put("down_mbps", downMbps)
    if (obfs.isNotBlank()) {
        put("obfs", buildJsonObject {
            put("type", obfs)
            put("password", obfsPassword)
            if (obfs == "gecko") {
                if (obfsMinPacketSize > 0) put("min_packet_size", obfsMinPacketSize)
                if (obfsMaxPacketSize > 0) put("max_packet_size", obfsMaxPacketSize)
            }
        })
    }
    if (network.isNotBlank()) put("network", network)
    if (bbrProfile.isNotBlank()) put("bbr_profile", bbrProfile)
    if (brutalDebug) put("brutal_debug", true)
    if (disableChromeParrot) put("disable_chrome_parrot", true)
    put("tls", tlsJson())
    if (idleTimeout.isNotBlank()) put("idle_timeout", idleTimeout)
    if (keepAlivePeriod.isNotBlank()) put("keep_alive_period", keepAlivePeriod)
    if (streamReceiveWindow.isNotBlank()) put("stream_receive_window", streamReceiveWindow)
    if (connectionReceiveWindow.isNotBlank()) put("connection_receive_window", connectionReceiveWindow)
    if (maxConcurrentStreams > 0) put("max_concurrent_streams", maxConcurrentStreams)
    if (initialPacketSize > 0) put("initial_packet_size", initialPacketSize)
    if (disablePathMtuDiscovery) put("disable_path_mtu_discovery", true)
    val dialBlocked = !detourNodeId.isNullOrBlank()
    if (!dialBlocked) {
        if (connectTimeout.isNotBlank()) put("connect_timeout", connectTimeout)
        udpFragment?.let { put("udp_fragment", it) }
        if (bindInterface.isNotBlank()) put("bind_interface", bindInterface)
        if (inet4BindAddress.isNotBlank()) put("inet4_bind_address", inet4BindAddress)
        if (inet6BindAddress.isNotBlank()) put("inet6_bind_address", inet6BindAddress)
        if (networkStrategy.isNotBlank()) put("network_strategy", networkStrategy)
        val types = splitCsv(networkType)
        if (types.isNotEmpty()) put("network_type", stringArray(types))
        val fallbackTypes = splitCsv(fallbackNetworkType)
        if (fallbackTypes.isNotEmpty()) put("fallback_network_type", stringArray(fallbackTypes))
        if (fallbackDelay.isNotBlank()) put("fallback_delay", fallbackDelay)
        if (domainResolver.isNotBlank()) put("domain_resolver", domainResolverJson())
    }
}

private fun Hysteria2Spec.domainResolverJson(): JsonElement {
    val onlyServer = domainResolverStrategy.isBlank() && domainResolverTimeout.isBlank() &&
        !domainResolverDisableCache && !domainResolverDisableOptimisticCache
    if (onlyServer) return JsonPrimitive(domainResolver)
    return buildJsonObject {
        put("server", domainResolver)
        if (domainResolverStrategy.isNotBlank()) put("strategy", domainResolverStrategy)
        if (domainResolverTimeout.isNotBlank()) put("timeout", domainResolverTimeout)
        if (domainResolverDisableCache) put("disable_cache", true)
        if (domainResolverDisableOptimisticCache) put("disable_optimistic_cache", true)
    }
}

private fun Hysteria2Spec.realmJson() = buildJsonObject {
    put("server_url", realmServerUrl)
    if (realmToken.isNotBlank()) put("token", realmToken)
    put("realm_id", realmId)
    put("stun_servers", stringArray(stunServers.filter { it.isNotBlank() }))
    if (realmIpVersion != 0) put("ip_version", realmIpVersion)
    if (portMappingEnabled) {
        put("port_mapping", buildJsonObject {
            put("enabled", true)
            if (portMappingTimeout.isNotBlank()) put("timeout", portMappingTimeout)
            if (portMappingLifetime.isNotBlank()) put("lifetime", portMappingLifetime)
        })
    }
    when {
        realmHttpClientTag.isNotBlank() -> put("http_client", realmHttpClientTag)
        httpClientVersion in 1..3 -> put("http_client", buildJsonObject { put("version", httpClientVersion) })
    }
}

private fun Hysteria2Spec.tlsJson() = buildJsonObject {
    put("enabled", true)
    if (disableSni) put("disable_sni", true)
    if (serverName.isNotBlank()) put("server_name", serverName)
    if (insecure) put("insecure", true)
    val alpns = splitCsv(alpn)
    if (alpns.isNotEmpty()) put("alpn", stringArray(alpns))
    if (minVersion.isNotBlank()) put("min_version", minVersion)
    if (maxVersion.isNotBlank()) put("max_version", maxVersion)
    val ciphers = splitCsv(cipherSuites)
    if (ciphers.isNotEmpty()) put("cipher_suites", stringArray(ciphers))
    val curves = splitCsv(curvePreferences)
    if (curves.isNotEmpty()) put("curve_preferences", stringArray(curves))
    val certs = pemList(certificate)
    if (certs.isNotEmpty()) put("certificate", stringArray(certs))
    if (certificatePath.isNotBlank()) put("certificate_path", certificatePath)
    val pins = splitCsv(certificatePublicKeySha256).map { pinToSingBox(it) }
    if (pins.isNotEmpty()) put("certificate_public_key_sha256", stringArray(pins))
    val clientCerts = pemList(clientCertificate)
    if (clientCerts.isNotEmpty()) put("client_certificate", stringArray(clientCerts))
    if (clientCertificatePath.isNotBlank()) put("client_certificate_path", clientCertificatePath)
    val clientKeys = pemList(clientKey)
    if (clientKeys.isNotEmpty()) put("client_key", stringArray(clientKeys))
    if (clientKeyPath.isNotBlank()) put("client_key_path", clientKeyPath)
    if (handshakeTimeout.isNotBlank()) put("handshake_timeout", handshakeTimeout)
    if (fragment) put("fragment", true)
    if (fragmentFallbackDelay.isNotBlank()) put("fragment_fallback_delay", fragmentFallbackDelay)
    if (recordFragment) put("record_fragment", true)
    if (echEnabled || echConfig.isNotBlank() || echConfigPath.isNotBlank()) {
        put("ech", buildJsonObject {
            put("enabled", true)
            val configs = pemList(echConfig)
            if (configs.isNotEmpty()) put("config", stringArray(configs))
            if (echConfigPath.isNotBlank()) put("config_path", echConfigPath)
            if (echQueryServerName.isNotBlank()) put("query_server_name", echQueryServerName)
        })
    }
    if (utlsEnabled || utlsFingerprint.isNotBlank()) {
        put("utls", buildJsonObject {
            put("enabled", true)
            if (utlsFingerprint.isNotBlank()) put("fingerprint", utlsFingerprint)
        })
    }
}

fun hysteria2FromSingBox(o: JsonObject): Hysteria2Spec {
    val realm = o["realm"] as? JsonObject
    val obfs = o["obfs"] as? JsonObject
    val tls = o["tls"] as? JsonObject
    val ech = tls?.get("ech") as? JsonObject
    val utls = tls?.get("utls") as? JsonObject
    val mapping = realm?.get("port_mapping") as? JsonObject
    val ports = stringsOf(o["server_ports"])
    val resolver = o["domain_resolver"]
    val access = if (realm != null) Hysteria2Access.REALM else Hysteria2Access.DIRECT
    val http = realm?.get("http_client")
    return Hysteria2Spec(
        access = access,
        server = if (access == Hysteria2Access.REALM) "" else str(o, "server"),
        serverPort = if (access == Hysteria2Access.REALM || ports.isNotEmpty()) 0 else intOf(o, "server_port") ?: 443,
        serverPorts = if (access == Hysteria2Access.REALM) emptyList() else ports,
        hopInterval = str(o, "hop_interval"),
        hopIntervalMax = str(o, "hop_interval_max"),
        upMbps = intOf(o, "up_mbps") ?: 0,
        downMbps = intOf(o, "down_mbps") ?: 0,
        bbrProfile = str(o, "bbr_profile"),
        obfs = str(obfs, "type"),
        obfsPassword = str(obfs, "password"),
        obfsMinPacketSize = intOf(obfs, "min_packet_size") ?: 0,
        obfsMaxPacketSize = intOf(obfs, "max_packet_size") ?: 0,
        password = str(o, "password"),
        network = networkOf(o["network"]),
        disableChromeParrot = boolOf(o, "disable_chrome_parrot"),
        brutalDebug = boolOf(o, "brutal_debug"),
        serverName = str(tls, "server_name"),
        disableSni = boolOf(tls, "disable_sni"),
        insecure = boolOf(tls, "insecure"),
        alpn = stringsOf(tls?.get("alpn")).joinToString(","),
        minVersion = str(tls, "min_version"),
        maxVersion = str(tls, "max_version"),
        cipherSuites = stringsOf(tls?.get("cipher_suites")).joinToString(","),
        curvePreferences = stringsOf(tls?.get("curve_preferences")).joinToString(","),
        certificate = stringsOf(tls?.get("certificate")).joinToString("\n"),
        certificatePath = str(tls, "certificate_path"),
        certificatePublicKeySha256 = stringsOf(tls?.get("certificate_public_key_sha256")).joinToString(","),
        clientCertificate = stringsOf(tls?.get("client_certificate")).joinToString("\n"),
        clientCertificatePath = str(tls, "client_certificate_path"),
        clientKey = stringsOf(tls?.get("client_key")).joinToString("\n"),
        clientKeyPath = str(tls, "client_key_path"),
        handshakeTimeout = str(tls, "handshake_timeout"),
        fragment = boolOf(tls, "fragment"),
        fragmentFallbackDelay = str(tls, "fragment_fallback_delay"),
        recordFragment = boolOf(tls, "record_fragment"),
        echEnabled = boolOf(ech, "enabled") || ech?.get("config") != null,
        echConfig = stringsOf(ech?.get("config")).joinToString("\n"),
        echConfigPath = str(ech, "config_path"),
        echQueryServerName = str(ech, "query_server_name"),
        utlsEnabled = boolOf(utls, "enabled") || str(utls, "fingerprint").isNotBlank(),
        utlsFingerprint = str(utls, "fingerprint"),
        idleTimeout = str(o, "idle_timeout"),
        keepAlivePeriod = str(o, "keep_alive_period"),
        streamReceiveWindow = str(o, "stream_receive_window"),
        connectionReceiveWindow = str(o, "connection_receive_window"),
        maxConcurrentStreams = intOf(o, "max_concurrent_streams") ?: 0,
        initialPacketSize = intOf(o, "initial_packet_size") ?: 0,
        disablePathMtuDiscovery = boolOf(o, "disable_path_mtu_discovery"),
        domainResolver = when (resolver) {
            is JsonPrimitive -> resolver.content
            is JsonObject -> str(resolver, "server")
            else -> ""
        },
        domainResolverStrategy = (resolver as? JsonObject)?.let { str(it, "strategy") }.orEmpty(),
        domainResolverTimeout = (resolver as? JsonObject)?.let { str(it, "timeout") }.orEmpty(),
        domainResolverDisableCache = boolOf(resolver as? JsonObject, "disable_cache"),
        domainResolverDisableOptimisticCache = boolOf(resolver as? JsonObject, "disable_optimistic_cache"),
        connectTimeout = str(o, "connect_timeout"),
        udpFragment = (o["udp_fragment"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
        bindInterface = str(o, "bind_interface"),
        inet4BindAddress = str(o, "inet4_bind_address"),
        inet6BindAddress = str(o, "inet6_bind_address"),
        networkStrategy = str(o, "network_strategy"),
        networkType = stringsOf(o["network_type"]).joinToString(","),
        fallbackNetworkType = stringsOf(o["fallback_network_type"]).joinToString(","),
        fallbackDelay = str(o, "fallback_delay"),
        realmServerUrl = str(realm, "server_url"),
        realmToken = str(realm, "token"),
        realmId = str(realm, "realm_id"),
        stunServers = stringsOf(realm?.get("stun_servers")).ifEmpty {
            if (realm != null) HYSTERIA_DEFAULT_STUN_SERVERS else HYSTERIA_DEFAULT_STUN_SERVERS
        },
        realmIpVersion = intOf(realm, "ip_version") ?: 0,
        portMappingEnabled = boolOf(mapping, "enabled"),
        portMappingTimeout = str(mapping, "timeout"),
        portMappingLifetime = str(mapping, "lifetime"),
        httpClientVersion = (http as? JsonObject)?.let { intOf(it, "version") } ?: 0,
        realmHttpClientTag = (http as? JsonPrimitive)?.contentOrNull.orEmpty(),
    )
}

object Hysteria2Links {
    fun parse(raw: String): ImportedNode {
        val line = raw.trim()
        val scheme = line.substringBefore(':').lowercase()
        return when (scheme) {
            "realm", "realm+http" -> parseRealm(line)
            else -> parseHy2(line)
        }
    }

    fun format(name: String, spec: Hysteria2Spec): String = when (spec.access) {
        Hysteria2Access.REALM -> formatRealm(spec)
        Hysteria2Access.DIRECT -> formatHy2(name, spec)
    }

    /** Clash `ports` / official `7000-10000,20000` into sing-box `start:end` ranges. */
    fun toSingRanges(raw: String): List<String> = raw.split(',', '\n', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { token ->
            val normalized = if (':' in token) token else token.replace('-', ':')
            if (':' !in normalized) "$normalized:$normalized" else normalized
        }

    private fun parseHy2(line: String): ImportedNode {
        val after = line.substringAfter("://")
        val name = decodePath(line.substringAfter('#', ""))
        val noFrag = after.substringBefore('#')
        val query = parseQuery(noFrag.substringAfter('?', ""))
        val pathStripped = noFrag.substringBefore('?')
        val authority = if (pathStripped.endsWith("/") || pathStripped.count { it == '/' } > 0) {
            pathStripped.substringBefore('/')
        } else {
            pathStripped
        }
        val at = authority.lastIndexOf('@')
        val user = if (at >= 0) authority.substring(0, at) else ""
        val hostport = if (at >= 0) authority.substring(at + 1) else authority
        val (host, port, hostRanges) = splitHost(hostport)
        val queryPorts = query["mport"].orEmpty().ifBlank { query["ports"].orEmpty() }
        val ranges = if (hostRanges.isNotEmpty()) hostRanges else if (queryPorts.isNotBlank()) toSingRanges(queryPorts) else emptyList()
        val hop = query["hop-interval"].orEmpty().ifBlank { query["hop"].orEmpty() }.let { raw ->
            if (raw.isNotBlank() && raw.all { it.isDigit() }) "${raw}s" else raw
        }
        return ImportedNode(
            name.ifBlank { "hysteria2" },
            Hysteria2Spec(
                access = Hysteria2Access.DIRECT,
                server = host,
                serverPort = if (ranges.isEmpty()) port else 0,
                serverPorts = ranges,
                hopInterval = hop,
                password = decodePath(user),
                serverName = query["sni"].orEmpty(),
                insecure = truthy(query["insecure"]),
                alpn = query["alpn"].orEmpty(),
                obfs = query["obfs"].orEmpty(),
                obfsPassword = query["obfs-password"].orEmpty(),
                certificatePublicKeySha256 = query["pinSHA256"].orEmpty(),
                echEnabled = query["ech"].orEmpty().isNotBlank(),
                echConfig = query["ech"].orEmpty(),
            ),
        )
    }

    private fun parseRealm(line: String): ImportedNode {
        val hash = line.indexOf('#')
        val core = if (hash >= 0) line.substring(0, hash) else line
        val fragment = if (hash >= 0) decodePath(line.substring(hash + 1)) else ""
        val uri = URI(core)
        val http = if (uri.scheme.equals("realm+http", true)) "http" else "https"
        val defaultPort = if (http == "http") 80 else 443
        val token = decodePath(uri.rawUserInfo.orEmpty())
        require(token.isNotBlank()) { "realm token is required" }
        val realmId = decodePath(uri.path.trim('/'))
        require(realmId.isNotBlank() && '/' !in realmId) { "realm id must be one segment" }
        val host = uri.host ?: error("realm host is required")
        val port = if (uri.port > 0) uri.port else defaultPort
        val portSuffix = if (port == defaultPort) "" else ":$port"
        val hostText = if (':' in host) "[$host]" else host
        val stuns = queryValues(uri.rawQuery, "stun").flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        return ImportedNode(
            fragment.ifBlank { realmId },
            Hysteria2Spec(
                access = Hysteria2Access.REALM,
                server = "",
                serverPort = 0,
                realmServerUrl = "$http://$hostText$portSuffix",
                realmToken = token,
                realmId = realmId,
                stunServers = stuns.ifEmpty { HYSTERIA_DEFAULT_STUN_SERVERS },
            ),
        )
    }

    private fun formatHy2(name: String, spec: Hysteria2Spec): String {
        val user = encodeUser(spec.password)
        val host = if (':' in spec.server && !spec.server.startsWith("[")) "[${spec.server}]" else spec.server
        val hostport = if (spec.serverPorts.isNotEmpty()) {
            val shown = spec.serverPorts.joinToString(",") { range ->
                val start = range.substringBefore(':')
                val end = range.substringAfter(':')
                if (start == end) start else "$start-$end"
            }
            "$host:$shown"
        } else {
            "$host:${spec.serverPort}"
        }
        val q = linkedMapOf<String, String>()
        if (spec.obfs.isNotBlank()) q["obfs"] = spec.obfs
        if (spec.obfsPassword.isNotBlank()) q["obfs-password"] = spec.obfsPassword
        if (spec.serverName.isNotBlank()) q["sni"] = spec.serverName
        if (spec.insecure) q["insecure"] = "1"
        if (spec.alpn.isNotBlank()) q["alpn"] = spec.alpn
        if (spec.certificatePublicKeySha256.isNotBlank()) q["pinSHA256"] = spec.certificatePublicKeySha256.substringBefore(',')
        if (spec.echConfig.isNotBlank()) q["ech"] = spec.echConfig
        if (spec.hopInterval.isNotBlank()) q["hop-interval"] = spec.hopInterval
        val query = q.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return buildString {
            append("hysteria2://").append(user).append('@').append(hostport).append('/')
            if (query.isNotEmpty()) append('?').append(query)
            if (name.isNotBlank()) append('#').append(enc(name))
        }
    }

    private fun formatRealm(spec: Hysteria2Spec): String {
        val uri = URI(spec.realmServerUrl)
        val http = uri.scheme == "http"
        val scheme = if (http) "realm+http" else "realm"
        val port = if (uri.port > 0) uri.port else if (http) 80 else 443
        val defaultPort = if (http) 80 else 443
        val host = uri.host ?: spec.realmServerUrl
        val hostText = if (':' in host && !host.startsWith("[")) "[$host]" else host
        val portText = if (port == defaultPort) "" else ":$port"
        val customStun = spec.stunServers.filter { it.isNotBlank() }
        val query = if (customStun == HYSTERIA_DEFAULT_STUN_SERVERS) {
            ""
        } else {
            "?" + customStun.joinToString("&") { "stun=${enc(it)}" }
        }
        return "$scheme://${enc(spec.realmToken)}@$hostText$portText/${enc(spec.realmId)}$query"
    }

    private fun splitHost(hostport: String): Triple<String, Int, List<String>> {
        if (hostport.startsWith("[")) {
            val end = hostport.indexOf(']')
            val host = hostport.substring(1, end)
            val rest = hostport.substring(end + 1).removePrefix(":")
            if (rest.isEmpty()) return Triple(host, 443, emptyList())
            if (rest.any { it == '-' || it == ',' }) return Triple(host, 0, toSingRanges(rest))
            return Triple(host, rest.toIntOrNull() ?: 443, emptyList())
        }
        val colon = hostport.lastIndexOf(':')
        if (colon < 0) return Triple(hostport, 443, emptyList())
        val host = hostport.substring(0, colon)
        val rest = hostport.substring(colon + 1)
        if (rest.any { it == '-' || it == ',' }) return Triple(host, 0, toSingRanges(rest))
        return Triple(host, rest.toIntOrNull() ?: 443, emptyList())
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        return q.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            decodeQuery(part.substringBefore('=')) to decodeQuery(part.substringAfter('=', ""))
        }.toMap()
    }

    private fun queryValues(raw: String?, key: String): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('&').mapNotNull { part ->
            val k = decodeQuery(part.substringBefore('='))
            if (k != key) return@mapNotNull null
            decodeQuery(part.substringAfter('=', ""))
        }
    }

    private fun encodeUser(password: String): String {
        val parts = password.split(':', limit = 2)
        return if (parts.size == 2) "${enc(parts[0])}:${enc(parts[1])}" else enc(password)
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun decodeQuery(s: String) = URLDecoder.decode(s, Charsets.UTF_8)
    private fun decodePath(s: String) = URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)
    private fun truthy(raw: String?) = raw.equals("1") || raw.equals("t", true) || raw.equals("true", true)
}

fun clashHysteria2(m: Map<*, *>): Hysteria2Spec {
    fun s(key: String) = m[key]?.toString().orEmpty()
    fun i(key: String) = s(key).trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    val obfsMap = m["obfs"] as? Map<*, *>
    val obfsType = obfsMap?.get("type")?.toString().orEmpty().ifBlank { (m["obfs"] as? String).orEmpty() }
    val obfsPassword = obfsMap?.get("password")?.toString().orEmpty().ifBlank { s("obfs-password") }
    val ports = s("ports").ifBlank { s("mport") }
    val ranges = if (ports.isBlank()) emptyList() else Hysteria2Links.toSingRanges(ports)
    val hop = s("hop-interval").ifBlank { s("hop_interval") }.let { raw ->
        if (raw.isNotBlank() && raw.all { it.isDigit() }) "${raw}s" else raw
    }
    val alpn = when (val value = m["alpn"]) {
        is List<*> -> value.joinToString(",") { it.toString() }
        else -> value?.toString().orEmpty()
    }
    val fingerprint = s("client-fingerprint").ifBlank { s("fingerprint") }
    return Hysteria2Spec(
        server = s("server"),
        serverPort = if (ranges.isEmpty()) i("port").let { if (it == 0) 443 else it } else 0,
        serverPorts = ranges,
        hopInterval = hop,
        password = s("password"),
        upMbps = i("up"),
        downMbps = i("down"),
        obfs = obfsType,
        obfsPassword = obfsPassword,
        obfsMinPacketSize = obfsMap?.get("min_packet_size")?.toString()?.toIntOrNull() ?: 0,
        obfsMaxPacketSize = obfsMap?.get("max_packet_size")?.toString()?.toIntOrNull() ?: 0,
        serverName = s("sni"),
        insecure = s("skip-cert-verify").toBoolean(),
        alpn = alpn,
        certificate = s("ca-str").ifBlank { s("ca") },
        utlsEnabled = fingerprint.isNotBlank(),
        utlsFingerprint = fingerprint,
        certificatePublicKeySha256 = s("pinSHA256"),
    )
}

private fun stringArray(values: List<String>): JsonArray = buildJsonArray {
    values.forEach { add(JsonPrimitive(it)) }
}

private fun splitCsv(raw: String) = raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

private fun pemList(raw: String): List<String> {
    val text = raw.trim()
    if (text.isEmpty()) return emptyList()
    if (!text.contains("-----BEGIN")) return listOf(text)
    return Regex("(?m)(?=-----BEGIN )").split(text).map { it.trim() }.filter { it.isNotEmpty() }
}

private fun pinToSingBox(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.matches(Regex("(?i)[0-9a-f]{64}"))) {
        val bytes = trimmed.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Base64.getEncoder().encodeToString(bytes)
    }
    return trimmed
}

private fun isHttpUrl(raw: String): Boolean {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
}

private fun str(o: JsonObject?, key: String) = o?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
private fun intOf(o: JsonObject?, key: String) = o?.get(key)?.jsonPrimitive?.intOrNull
    ?: o?.get(key)?.jsonPrimitive?.contentOrNull?.toIntOrNull()
private fun boolOf(o: JsonObject?, key: String) = o?.get(key)?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

private fun stringsOf(element: JsonElement?): List<String> = when (element) {
    is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.isNotBlank() }
    is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
    else -> emptyList()
}

private fun networkOf(element: JsonElement?): String = when (element) {
    is JsonArray -> {
        val items = element.mapNotNull { it.jsonPrimitive.contentOrNull }
        if (items.size == 1) items[0] else ""
    }
    is JsonPrimitive -> {
        val value = element.content
        if (value == "tcp" || value == "udp") value else ""
    }
    else -> ""
}
