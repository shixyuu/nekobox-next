package io.nekobox.next.engine.parse

import io.nekobox.next.engine.model.AnyTlsSpec
import io.nekobox.next.engine.model.HttpSpec
import io.nekobox.next.engine.model.Hysteria2Spec
import io.nekobox.next.engine.model.clashHysteria2
import io.nekobox.next.engine.model.hysteria2FromSingBox
import io.nekobox.next.engine.model.ImportedNode
import io.nekobox.next.engine.model.ParseBatch
import io.nekobox.next.engine.model.ShadowsocksSpec
import io.nekobox.next.engine.model.SocksSpec
import io.nekobox.next.engine.model.TrojanSpec
import io.nekobox.next.engine.model.TuicSpec
import io.nekobox.next.engine.model.VlessSpec
import io.nekobox.next.engine.model.VmessSpec
import io.nekobox.next.engine.model.WireGuardSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.Base64

/**
 * Parses subscription bodies into nodes only. Clash YAML is loaded with
 * SnakeYAML's SafeConstructor so custom tags cannot construct Java objects.
 */
object SubscriptionParser {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_CHARS = 2_000_000

    fun parse(body: String): ParseBatch {
        val text = body.trim().removePrefix("\uFEFF")
        if (text.length > MAX_CHARS) return ParseBatch(emptyList(), listOf("subscription is too large"))
        if (text.isEmpty()) return ParseBatch(emptyList(), listOf("empty subscription"))
        return try {
            when {
                text.contains("[Interface]") -> ParseBatch(listOf(WireGuardIni.parse(text)), emptyList())
                text.startsWith("{") || text.startsWith("[") -> parseJson(text)
                text.contains("proxies:") || text.contains("Proxy:") -> parseClash(text)
                else -> parseMaybeBase64(text)
            }
        } catch (e: Exception) {
            ParseBatch(emptyList(), listOf(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun parseMaybeBase64(text: String): ParseBatch {
        val decoded = runCatching {
            val cleaned = text.replace("\n", "").replace("\r", "")
            val pad = "=".repeat((4 - cleaned.length % 4) % 4)
            String(Base64.getDecoder().decode(cleaned + pad))
        }.getOrNull()
        val candidate = decoded?.takeIf { it.contains("://") || it.trim().startsWith("{") || it.contains("proxies:") } ?: text
        if (candidate.trim().startsWith("{")) return parseJson(candidate)
        if (candidate.contains("proxies:")) return parseClash(candidate)
        val nodes = mutableListOf<ImportedNode>()
        val warnings = mutableListOf<String>()
        candidate.lineSequence().map { it.trim() }.filter { it.contains("://") }.forEach { line ->
            runCatching { nodes += ShareLinkParser.parse(line) }
                .onFailure { warnings += "skip ${line.take(48)}: ${it.message}" }
        }
        if (nodes.isEmpty()) warnings += "no nodes recognized"
        return ParseBatch(nodes, warnings)
    }

    private fun parseJson(text: String): ParseBatch {
        val element = json.parseToJsonElement(text)
        val warnings = mutableListOf<String>()
        val nodes = mutableListOf<ImportedNode>()
        when (element) {
            is JsonArray -> element.forEach { item ->
                if (item is JsonObject) readSingBoxOutbound(item, nodes, warnings)
            }
            is JsonObject -> {
                element["outbounds"]?.jsonArray?.forEach { item ->
                    if (item is JsonObject) readSingBoxOutbound(item, nodes, warnings)
                }
                element["servers"]?.jsonArray?.forEach { item ->
                    if (item is JsonObject) readSip008(item, nodes, warnings)
                }
                if (element["type"] != null && nodes.isEmpty()) readSingBoxOutbound(element, nodes, warnings)
            }
            else -> warnings += "json root is not an object or array"
        }
        return ParseBatch(nodes, warnings)
    }

    private fun readSip008(obj: JsonObject, nodes: MutableList<ImportedNode>, warnings: MutableList<String>) {
        val server = str(obj, "server")
        if (server.isBlank()) {
            warnings += "sip008 entry missing server"
            return
        }
        nodes += ImportedNode(
            str(obj, "remarks").ifBlank { server },
            ShadowsocksSpec(
                server = server,
                serverPort = int(obj, "server_port") ?: 8388,
                method = str(obj, "method").ifBlank { "aes-256-gcm" },
                password = str(obj, "password"),
            ),
        )
    }

    private fun readSingBoxOutbound(obj: JsonObject, nodes: MutableList<ImportedNode>, warnings: MutableList<String>) {
        val type = str(obj, "type")
        if (type.isBlank() || type == "direct" || type == "block" || type == "dns" || type == "selector" || type == "urltest") {
            return
        }
        val name = str(obj, "tag").ifBlank { type }
        val spec = runCatching { singBoxToSpec(type, obj) }.getOrElse {
            warnings += "skip $name: ${it.message}"
            return
        }
        if (spec != null) nodes += ImportedNode(name, spec) else warnings += "unsupported outbound $type"
    }

    private fun singBoxToSpec(type: String, o: JsonObject) = when (type) {
        "vless" -> VlessSpec(
            server = str(o, "server"),
            serverPort = int(o, "server_port") ?: 443,
            uuid = str(o, "uuid"),
            flow = str(o, "flow"),
            packetEncoding = str(o, "packet_encoding"),
            tls = bool(o["tls"] as? JsonObject, "enabled"),
            serverName = nested(o, "tls", "server_name"),
            insecure = bool(o["tls"] as? JsonObject, "insecure"),
            fingerprint = nestedOpt(o["tls"] as? JsonObject, "utls", "fingerprint"),
            realityPublicKey = nestedOpt(o["tls"] as? JsonObject, "reality", "public_key"),
            realityShortId = nestedOpt(o["tls"] as? JsonObject, "reality", "short_id"),
        )
        "vmess" -> VmessSpec(
            server = str(o, "server"),
            serverPort = int(o, "server_port") ?: 443,
            uuid = str(o, "uuid"),
            security = str(o, "security").ifBlank { "auto" },
            alterId = int(o, "alter_id") ?: 0,
            tls = bool(o["tls"] as? JsonObject, "enabled"),
            serverName = nested(o, "tls", "server_name"),
        )
        "trojan" -> TrojanSpec(
            server = str(o, "server"),
            serverPort = int(o, "server_port") ?: 443,
            password = str(o, "password"),
            serverName = nested(o, "tls", "server_name"),
        )
        "shadowsocks" -> ShadowsocksSpec(
            server = str(o, "server"),
            serverPort = int(o, "server_port") ?: 8388,
            method = str(o, "method"),
            password = str(o, "password"),
        )
        "hysteria2" -> hysteria2FromSingBox(o)
        "tuic" -> TuicSpec(
            server = str(o, "server"),
            serverPort = int(o, "server_port") ?: 443,
            uuid = str(o, "uuid"),
            password = str(o, "password"),
            congestionControl = str(o, "congestion_control").ifBlank { "bbr" },
            serverName = nested(o, "tls", "server_name"),
        )
        "socks" -> SocksSpec(
            server = str(o, "server"),
            serverPort = int(o, "server_port") ?: 1080,
            username = str(o, "username"),
            password = str(o, "password"),
            version = str(o, "version").ifBlank { "5" },
        )
        "http" -> HttpSpec(
            server = str(o, "server"),
            serverPort = int(o, "server_port") ?: 8080,
            username = str(o, "username"),
            password = str(o, "password"),
            tls = bool(o["tls"] as? JsonObject, "enabled"),
        )
        "wireguard" -> WireGuardSpec(
            server = str(o, "server"),
            serverPort = int(o, "server_port") ?: 51820,
            privateKey = str(o, "private_key"),
            peerPublicKey = str(o, "peer_public_key"),
            preSharedKey = str(o, "pre_shared_key"),
            localAddress = (o["local_address"] as? JsonArray)?.joinToString(",") { it.jsonPrimitive.content } ?: str(o, "local_address"),
            mtu = int(o, "mtu") ?: 1280,
        )
        "anytls" -> AnyTlsSpec(
            server = str(o, "server"),
            serverPort = int(o, "server_port") ?: 443,
            password = str(o, "password"),
            serverName = nested(o, "tls", "server_name"),
        )
        else -> null
    }

    private fun parseClash(text: String): ParseBatch {
        val root = loadYaml(text)
        val proxies = (root["proxies"] as? List<*>) ?: (root["Proxy"] as? List<*>) ?: emptyList<Any>()
        val nodes = mutableListOf<ImportedNode>()
        val warnings = mutableListOf<String>()
        proxies.forEach { item ->
            val map = item as? Map<*, *> ?: return@forEach
            val type = map["type"]?.toString()?.lowercase().orEmpty()
            val name = map["name"]?.toString()?.ifBlank { type } ?: type
            val spec = runCatching { clashToSpec(type, map) }.getOrElse {
                warnings += "skip $name: ${it.message}"
                return@forEach
            }
            if (spec == null) warnings += "unsupported clash type $type" else nodes += ImportedNode(name, spec)
        }
        return ParseBatch(nodes, warnings)
    }

    @Suppress("UNCHECKED_CAST")
    private fun clashToSpec(type: String, m: Map<*, *>) = when (type) {
        "ss", "shadowsocks" -> ShadowsocksSpec(
            server = m.s("server"),
            serverPort = m.i("port"),
            method = m.s("cipher").ifBlank { m.s("method") },
            password = m.s("password"),
            plugin = m.s("plugin"),
            pluginOpts = m["plugin-opts"]?.toString().orEmpty(),
        )
        "vmess" -> VmessSpec(
            server = m.s("server"),
            serverPort = m.i("port"),
            uuid = m.s("uuid"),
            alterId = m.i("alterId"),
            security = m.s("cipher").ifBlank { "auto" },
            network = m.s("network").ifBlank { "tcp" },
            path = wsPath(m),
            host = wsHost(m),
            tls = m.b("tls"),
            serverName = m.s("servername"),
            fingerprint = m.s("client-fingerprint"),
        )
        "vless" -> {
            val reality = m["reality-opts"] as? Map<*, *>
            VlessSpec(
                server = m.s("server"),
                serverPort = m.i("port"),
                uuid = m.s("uuid"),
                flow = m.s("flow"),
                network = m.s("network").ifBlank { "tcp" },
                path = wsPath(m),
                host = wsHost(m),
                serviceName = (m["grpc-opts"] as? Map<*, *>)?.get("grpc-service-name")?.toString().orEmpty(),
                tls = m.b("tls") || reality != null,
                serverName = m.s("servername"),
                fingerprint = m.s("client-fingerprint"),
                realityPublicKey = reality?.get("public-key")?.toString().orEmpty(),
                realityShortId = reality?.get("short-id")?.toString().orEmpty(),
            )
        }
        "trojan" -> TrojanSpec(
            server = m.s("server"),
            serverPort = m.i("port"),
            password = m.s("password"),
            network = m.s("network").ifBlank { "tcp" },
            serverName = m.s("sni"),
            fingerprint = m.s("client-fingerprint"),
        )
        "hysteria2", "hy2" -> clashHysteria2(m)
        "tuic" -> TuicSpec(
            server = m.s("server"),
            serverPort = m.i("port"),
            uuid = m.s("uuid"),
            password = m.s("password"),
            congestionControl = m.s("congestion-controller").ifBlank { "bbr" },
            serverName = m.s("sni"),
        )
        "socks5", "socks" -> SocksSpec(m.s("server"), m.i("port").let { if (it == 0) 1080 else it }, username = m.s("username"), password = m.s("password"))
        "http" -> HttpSpec(m.s("server"), m.i("port"), m.s("username"), m.s("password"), tls = m.b("tls"))
        "wireguard" -> WireGuardSpec(
            server = m.s("server"),
            serverPort = m.i("port"),
            privateKey = m.s("private-key"),
            peerPublicKey = m.s("public-key"),
            preSharedKey = m.s("pre-shared-key"),
            localAddress = (m["ip"] ?: m["local-address"])?.toString().orEmpty(),
            mtu = m.i("mtu").let { if (it == 0) 1280 else it },
        )
        "anytls" -> AnyTlsSpec(m.s("server"), m.i("port"), m.s("password"), m.s("sni"))
        else -> null
    }

    private fun wsPath(m: Map<*, *>) = (m["ws-opts"] as? Map<*, *>)?.get("path")?.toString().orEmpty()
    private fun wsHost(m: Map<*, *>): String {
        val headers = (m["ws-opts"] as? Map<*, *>)?.get("headers") as? Map<*, *>
        return headers?.get("Host")?.toString().orEmpty()
    }

    private fun loadYaml(text: String): Map<String, Any?> {
        val options = LoaderOptions()
        options.maxAliasesForCollections = 50
        options.codePointLimit = MAX_CHARS
        options.isAllowDuplicateKeys = true
        options.nestingDepthLimit = 64
        val yaml = Yaml(SafeConstructor(options))
        val loaded = yaml.load<Any?>(text) ?: return emptyMap()
        if (loaded !is Map<*, *>) error("yaml root is not a map")
        @Suppress("UNCHECKED_CAST")
        return loaded as Map<String, Any?>
    }

    private fun str(o: JsonObject, key: String) = o[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun int(o: JsonObject, key: String) = o[key]?.jsonPrimitive?.intOrNull
        ?: o[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    private fun bool(o: JsonObject?, key: String) = o?.get(key)?.jsonPrimitive?.booleanOrNull ?: false
    private fun nested(o: JsonObject, a: String, b: String) = (o[a] as? JsonObject)?.get(b)?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun nestedOpt(o: JsonObject?, a: String, b: String) = (o?.get(a) as? JsonObject)?.get(b)?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun Map<*, *>.s(key: String) = this[key]?.toString().orEmpty()
    private fun Map<*, *>.i(key: String) = this[key]?.toString()?.toIntOrNull() ?: 0
    private fun Map<*, *>.b(key: String) = this[key]?.toString()?.toBooleanStrictOrNull() ?: (this[key]?.toString() == "true")
}
