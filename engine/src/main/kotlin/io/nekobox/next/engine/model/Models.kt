package io.nekobox.next.engine.model

import io.nekobox.next.engine.NekoJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

const val NODE_SCHEMA_VERSION = 1

/**
 * Protocol payload stored as one versioned JSON document per node.
 * The Room column keeps [NODE_SCHEMA_VERSION] beside the document so a
 * future migration can rewrite JSON without a wide per-protocol table.
 */
@Serializable
sealed interface NodeSpec {
    val detourNodeId: String? get() = null
}

@Serializable
@SerialName("vless")
data class VlessSpec(
    val server: String = "",
    val serverPort: Int = 443,
    val uuid: String = "",
    val flow: String = "",
    val packetEncoding: String = "",
    val network: String = "tcp",
    val path: String = "",
    val host: String = "",
    val serviceName: String = "",
    val tls: Boolean = true,
    val serverName: String = "",
    val insecure: Boolean = false,
    val alpn: String = "",
    val fingerprint: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    override val detourNodeId: String? = null,
) : NodeSpec

@Serializable
@SerialName("vmess")
data class VmessSpec(
    val server: String = "",
    val serverPort: Int = 443,
    val uuid: String = "",
    val security: String = "auto",
    val alterId: Int = 0,
    val network: String = "tcp",
    val path: String = "",
    val host: String = "",
    val serviceName: String = "",
    val tls: Boolean = false,
    val serverName: String = "",
    val insecure: Boolean = false,
    val alpn: String = "",
    val fingerprint: String = "",
    override val detourNodeId: String? = null,
) : NodeSpec

@Serializable
@SerialName("trojan")
data class TrojanSpec(
    val server: String = "",
    val serverPort: Int = 443,
    val password: String = "",
    val network: String = "tcp",
    val path: String = "",
    val host: String = "",
    val serviceName: String = "",
    val tls: Boolean = true,
    val serverName: String = "",
    val insecure: Boolean = false,
    val alpn: String = "",
    val fingerprint: String = "",
    override val detourNodeId: String? = null,
) : NodeSpec

@Serializable
@SerialName("shadowsocks")
data class ShadowsocksSpec(
    val server: String = "",
    val serverPort: Int = 8388,
    val method: String = "aes-256-gcm",
    val password: String = "",
    val plugin: String = "",
    val pluginOpts: String = "",
    override val detourNodeId: String? = null,
) : NodeSpec

@Serializable
@SerialName("hysteria")
data class HysteriaSpec(
    val server: String = "",
    val serverPort: Int = 443,
    val auth: String = "",
    val serverName: String = "",
    val insecure: Boolean = false,
    val alpn: String = "",
    val upMbps: Int = 10,
    val downMbps: Int = 50,
    val obfs: String = "",
    override val detourNodeId: String? = null,
) : NodeSpec

@Serializable
@SerialName("tuic")
data class TuicSpec(
    val server: String = "",
    val serverPort: Int = 443,
    val uuid: String = "",
    val password: String = "",
    val congestionControl: String = "bbr",
    val udpRelayMode: String = "native",
    val serverName: String = "",
    val insecure: Boolean = false,
    val alpn: String = "h3",
    override val detourNodeId: String? = null,
) : NodeSpec

@Serializable
@SerialName("socks")
data class SocksSpec(
    val server: String = "",
    val serverPort: Int = 1080,
    val version: String = "5",
    val username: String = "",
    val password: String = "",
    override val detourNodeId: String? = null,
) : NodeSpec

@Serializable
@SerialName("http")
data class HttpSpec(
    val server: String = "",
    val serverPort: Int = 8080,
    val username: String = "",
    val password: String = "",
    val tls: Boolean = false,
    val serverName: String = "",
    val insecure: Boolean = false,
    override val detourNodeId: String? = null,
) : NodeSpec

@Serializable
@SerialName("wireguard")
data class WireGuardSpec(
    val server: String = "",
    val serverPort: Int = 51820,
    val privateKey: String = "",
    val peerPublicKey: String = "",
    val preSharedKey: String = "",
    val localAddress: String = "",
    val mtu: Int = 1280,
    val reserved: String = "",
    override val detourNodeId: String? = null,
) : NodeSpec

@Serializable
@SerialName("anytls")
data class AnyTlsSpec(
    val server: String = "",
    val serverPort: Int = 443,
    val password: String = "",
    val serverName: String = "",
    val insecure: Boolean = false,
    val alpn: String = "",
    val fingerprint: String = "",
    override val detourNodeId: String? = null,
) : NodeSpec

@Serializable
@SerialName("ssh")
data class SshSpec(
    val server: String = "",
    val serverPort: Int = 22,
    val user: String = "",
    val password: String = "",
    val privateKey: String = "",
    override val detourNodeId: String? = null,
) : NodeSpec

@Serializable
@SerialName("byedpi")
data class ByeDpiSpec(
    val preset: String = ByeDpiPresets.MILD,
    val rawArgs: String = "",
    val hosts: List<String> = emptyList(),
) : NodeSpec {
    fun effectiveArgs(): String = ByeDpiPresets.args(preset, rawArgs)
}

@Serializable
@SerialName("custom")
data class CustomSpec(
    val outboundJson: String = "{}",
    override val detourNodeId: String? = null,
) : NodeSpec

object ByeDpiPresets {
    const val MILD = "mild"
    const val SPLIT = "split"
    const val DISORDER = "disorder"
    const val FAKE = "fake"
    const val CUSTOM = "custom"

    val all = listOf(MILD, SPLIT, DISORDER, FAKE, CUSTOM)

    fun args(preset: String, custom: String): String = when (preset) {
        MILD -> "--auto=torst --timeout 3 --tlsrec 3+s"
        SPLIT -> "--split 1 --split 3+s"
        DISORDER -> "--disorder 1"
        FAKE -> "--fake 1 --ttl 8"
        else -> custom.trim()
    }
}

object NodeSpecs {
    val protocols = listOf(
        "vless", "vmess", "trojan", "shadowsocks", "hysteria2", "hysteria",
        "tuic", "socks", "http", "wireguard", "anytls", "ssh", "byedpi", "custom",
    )

    fun protocolOf(spec: NodeSpec): String = when (spec) {
        is VlessSpec -> "vless"
        is VmessSpec -> "vmess"
        is TrojanSpec -> "trojan"
        is ShadowsocksSpec -> "shadowsocks"
        is Hysteria2Spec -> "hysteria2"
        is HysteriaSpec -> "hysteria"
        is TuicSpec -> "tuic"
        is SocksSpec -> "socks"
        is HttpSpec -> "http"
        is WireGuardSpec -> "wireguard"
        is AnyTlsSpec -> "anytls"
        is SshSpec -> "ssh"
        is ByeDpiSpec -> "byedpi"
        is CustomSpec -> "custom"
    }

    fun default(protocol: String): NodeSpec = when (protocol) {
        "vless" -> VlessSpec()
        "vmess" -> VmessSpec()
        "trojan" -> TrojanSpec()
        "shadowsocks" -> ShadowsocksSpec()
        "hysteria2" -> Hysteria2Spec()
        "hysteria" -> HysteriaSpec()
        "tuic" -> TuicSpec()
        "socks" -> SocksSpec()
        "http" -> HttpSpec()
        "wireguard" -> WireGuardSpec()
        "anytls" -> AnyTlsSpec()
        "ssh" -> SshSpec()
        "byedpi" -> ByeDpiSpec()
        "custom" -> CustomSpec()
        else -> error("unknown protocol $protocol")
    }

    fun encode(spec: NodeSpec): String = NekoJson.encodeToString(NodeSpec.serializer(), spec)

    fun decode(json: String): NodeSpec = NekoJson.decodeFromString(NodeSpec.serializer(), json)

    fun toJsonObject(spec: NodeSpec): JsonObject =
        NekoJson.encodeToJsonElement(NodeSpec.serializer(), spec) as JsonObject

    fun fromJsonObject(obj: JsonObject): NodeSpec =
        NekoJson.decodeFromJsonElement(NodeSpec.serializer(), obj)
}

@Serializable
data class RouteRule(
    val id: String = "",
    val enabled: Boolean = true,
    val domain: List<String> = emptyList(),
    val domainSuffix: List<String> = emptyList(),
    val domainKeyword: List<String> = emptyList(),
    val ipCidr: List<String> = emptyList(),
    val port: List<Int> = emptyList(),
    val packageName: List<String> = emptyList(),
    val ruleSet: List<String> = emptyList(),
    /** proxy, bypass, block, or a node id. */
    val outbound: String = "proxy",
)

@Serializable
data class RuleSetSource(
    val tag: String,
    val url: String,
    val format: String = "binary",
)

enum class RunMode { VPN, PROXY }

enum class PerAppMode { OFF, ALLOW, DENY }

@Serializable
data class GeneratorSettings(
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
    val perAppPackages: List<String> = emptyList(),
    val globalOverride: String = "",
    val byeDpiPort: Int = 18765,
    val byeDpiUsername: String = "",
    val byeDpiPassword: String = "",
    val selectedGroupId: String? = null,
    val selectedNodeId: String? = null,
)

@Serializable
data class NodeRecord(
    val id: String,
    val name: String,
    val groupId: String,
    val spec: NodeSpec,
)

@Serializable
data class GroupRecord(
    val id: String,
    val name: String,
    val type: String = "selector",
    val nodeIds: List<String> = emptyList(),
    val selectedNodeId: String? = null,
)

data class ImportedNode(val name: String, val spec: NodeSpec)

data class ParseBatch(val nodes: List<ImportedNode>, val warnings: List<String>)

data class ByeDpiLaunch(
    val listen: String,
    val username: String,
    val password: String,
    val args: String,
    val hosts: List<String>,
)

data class GeneratedConfig(
    val singBoxJson: String,
    val byeDpi: ByeDpiLaunch?,
)
