package io.nekobox.next.engine.parse

import io.nekobox.next.engine.model.AnyTlsSpec
import io.nekobox.next.engine.model.HttpSpec
import io.nekobox.next.engine.model.Hysteria2Spec
import io.nekobox.next.engine.model.HysteriaSpec
import io.nekobox.next.engine.model.ImportedNode
import io.nekobox.next.engine.model.ShadowsocksSpec
import io.nekobox.next.engine.model.SocksSpec
import io.nekobox.next.engine.model.SshSpec
import io.nekobox.next.engine.model.TrojanSpec
import io.nekobox.next.engine.model.TuicSpec
import io.nekobox.next.engine.model.VlessSpec
import io.nekobox.next.engine.model.VmessSpec
import io.nekobox.next.engine.model.WireGuardSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.util.Base64

object ShareLinkParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): ImportedNode {
        val line = raw.trim()
        val scheme = line.substringBefore(':').lowercase()
        return when (scheme) {
            "vless" -> parseVless(line)
            "vmess" -> parseVmess(line)
            "trojan" -> parseTrojan(line)
            "ss" -> parseShadowsocks(line)
            "hysteria2", "hy2" -> parseHysteria2(line)
            "hysteria" -> parseHysteria(line)
            "tuic" -> parseTuic(line)
            "socks", "socks5" -> parseSocks(line)
            "http", "https" -> parseHttp(line)
            "anytls" -> parseAnyTls(line)
            "ssh" -> parseSsh(line)
            "wireguard", "wg" -> parseWireGuard(line)
            else -> error("unsupported share link scheme $scheme")
        }
    }

    fun parseLines(text: String): List<ImportedNode> {
        val nodes = mutableListOf<ImportedNode>()
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            runCatching { nodes += parse(line) }
        }
        return nodes
    }

    private fun parseVless(line: String): ImportedNode {
        val p = splitUrl(line)
        val q = p.query
        return ImportedNode(
            p.name,
            VlessSpec(
                server = p.host,
                serverPort = p.port,
                uuid = decode(p.user),
                flow = q["flow"].orEmpty(),
                network = q["type"] ?: "tcp",
                path = q["path"].orEmpty(),
                host = q["host"].orEmpty(),
                serviceName = q["serviceName"].orEmpty(),
                tls = q["security"] == "tls" || q["security"] == "reality",
                serverName = q["sni"] ?: q["peer"].orEmpty(),
                insecure = q["allowInsecure"] == "1",
                alpn = q["alpn"].orEmpty(),
                fingerprint = q["fp"].orEmpty(),
                realityPublicKey = q["pbk"].orEmpty(),
                realityShortId = q["sid"].orEmpty(),
                packetEncoding = q["packetEncoding"].orEmpty(),
            ),
        )
    }

    private fun parseVmess(line: String): ImportedNode {
        val body = line.substringAfter("://")
        val decoded = String(b64(body.substringBefore('#')))
        val obj = json.parseToJsonElement(decoded) as JsonObject
        fun s(key: String) = obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun n(key: String) = obj[key]?.jsonPrimitive?.intOrNull ?: s(key).toIntOrNull() ?: 0
        val tls = s("tls").equals("tls", true)
        return ImportedNode(
            decode(s("ps").ifBlank { line.substringAfter('#', "vmess") }),
            VmessSpec(
                server = s("add"),
                serverPort = n("port"),
                uuid = s("id"),
                alterId = n("aid"),
                security = s("scy").ifBlank { "auto" },
                network = s("net").ifBlank { "tcp" },
                path = s("path"),
                host = s("host"),
                serviceName = s("path").takeIf { s("net") == "grpc" }.orEmpty(),
                tls = tls,
                serverName = s("sni").ifBlank { s("host") },
                fingerprint = s("fp"),
                alpn = s("alpn"),
            ),
        )
    }

    private fun parseTrojan(line: String) = authorityNode(line) { p ->
        ImportedNode(
            p.name,
            TrojanSpec(
                server = p.host,
                serverPort = p.port,
                password = decode(p.user),
                network = p.query["type"] ?: "tcp",
                path = p.query["path"].orEmpty(),
                host = p.query["host"].orEmpty(),
                serviceName = p.query["serviceName"].orEmpty(),
                serverName = p.query["sni"] ?: p.query["peer"].orEmpty(),
                insecure = p.query["allowInsecure"] == "1",
                alpn = p.query["alpn"].orEmpty(),
                fingerprint = p.query["fp"].orEmpty(),
            ),
        )
    }

    private fun parseHysteria2(line: String) = authorityNode(line) { p ->
        ImportedNode(
            p.name,
            Hysteria2Spec(
                server = p.host,
                serverPort = p.port,
                password = decode(p.user),
                serverName = p.query["sni"].orEmpty(),
                insecure = p.query["insecure"] == "1",
                alpn = p.query["alpn"].orEmpty(),
                obfs = p.query["obfs"].orEmpty(),
                obfsPassword = p.query["obfs-password"].orEmpty(),
            ),
        )
    }

    private fun parseHysteria(line: String) = authorityNode(line) { p ->
        ImportedNode(
            p.name,
            HysteriaSpec(
                server = p.host,
                serverPort = p.port,
                auth = decode(p.user.ifBlank { p.query["auth"].orEmpty() }),
                serverName = p.query["peer"] ?: p.query["sni"].orEmpty(),
                insecure = p.query["insecure"] == "1",
                alpn = p.query["alpn"].orEmpty(),
                upMbps = p.query["upmbps"]?.toIntOrNull() ?: 10,
                downMbps = p.query["downmbps"]?.toIntOrNull() ?: 50,
                obfs = p.query["obfs"].orEmpty(),
            ),
        )
    }

    private fun parseTuic(line: String) = authorityNode(line) { p ->
        val uuid = decode(p.user.substringBefore(':'))
        val password = decode(p.user.substringAfter(':', ""))
        ImportedNode(
            p.name,
            TuicSpec(
                server = p.host,
                serverPort = p.port,
                uuid = uuid,
                password = password,
                congestionControl = p.query["congestion_control"] ?: "bbr",
                udpRelayMode = p.query["udp_relay_mode"] ?: "native",
                serverName = p.query["sni"].orEmpty(),
                insecure = p.query["allow_insecure"] == "1" || p.query["insecure"] == "1",
                alpn = p.query["alpn"] ?: "h3",
            ),
        )
    }

    private fun parseSocks(line: String) = authorityNode(line) { p ->
        ImportedNode(
            p.name.ifBlank { "socks" },
            SocksSpec(
                server = p.host,
                serverPort = p.port,
                username = decode(p.user.substringBefore(':')),
                password = decode(p.user.substringAfter(':', "")),
            ),
        )
    }

    private fun parseHttp(line: String) = authorityNode(line) { p ->
        ImportedNode(
            p.name.ifBlank { "http" },
            HttpSpec(
                server = p.host,
                serverPort = p.port,
                username = decode(p.user.substringBefore(':')),
                password = decode(p.user.substringAfter(':', "")),
                tls = line.startsWith("https://"),
                serverName = p.query["sni"].orEmpty(),
            ),
        )
    }

    private fun parseAnyTls(line: String) = authorityNode(line) { p ->
        ImportedNode(
            p.name,
            AnyTlsSpec(
                server = p.host,
                serverPort = p.port,
                password = decode(p.user),
                serverName = p.query["sni"].orEmpty(),
                insecure = p.query["insecure"] == "1",
                alpn = p.query["alpn"].orEmpty(),
                fingerprint = p.query["fp"].orEmpty(),
            ),
        )
    }

    private fun parseSsh(line: String) = authorityNode(line) { p ->
        ImportedNode(
            p.name.ifBlank { "ssh" },
            SshSpec(
                server = p.host,
                serverPort = p.port,
                user = decode(p.user.substringBefore(':')),
                password = decode(p.user.substringAfter(':', "")),
            ),
        )
    }

    private fun parseShadowsocks(line: String): ImportedNode {
        val raw = line.substringAfter("://").substringBefore('#')
        val name = fragmentName(line, "ss")
        if (raw.contains('@')) {
            val user = raw.substringBefore('@')
            val hostport = raw.substringAfter('@').substringBefore('?')
            val decodedUser = decodeUserinfo(user)
            val method = decodedUser.substringBefore(':')
            val password = decodedUser.substringAfter(':')
            val (host, port) = splitHostPort(hostport, 8388)
            return ImportedNode(name, ShadowsocksSpec(server = host, serverPort = port, method = method, password = password))
        }
        val decoded = String(b64(raw))
        val method = decoded.substringBefore(':')
        val rest = decoded.substringAfter(':')
        val password = rest.substringBeforeLast('@')
        val (host, port) = splitHostPort(rest.substringAfterLast('@'), 8388)
        return ImportedNode(name, ShadowsocksSpec(server = host, serverPort = port, method = method, password = password))
    }

    private fun parseWireGuard(line: String): ImportedNode {
        val body = line.substringAfter("://")
        val text = runCatching { String(b64(body.substringBefore('#'))) }.getOrDefault(body)
        if (text.contains("[Interface]") || text.contains("PrivateKey")) {
            return ImportedNode(fragmentName(line, "wireguard"), WireGuardIni.parse(text, fragmentName(line, "wireguard")).spec)
        }
        return authorityNode(line) { p ->
            ImportedNode(
                p.name.ifBlank { "wireguard" },
                WireGuardSpec(
                    server = p.host,
                    serverPort = p.port,
                    privateKey = p.query["privateKey"].orEmpty(),
                    peerPublicKey = p.query["publicKey"].orEmpty(),
                    preSharedKey = p.query["preSharedKey"].orEmpty(),
                    localAddress = p.query["address"].orEmpty(),
                ),
            )
        }
    }

    private fun authorityNode(line: String, block: (Parts) -> ImportedNode): ImportedNode = block(splitUrl(line))

    private data class Parts(
        val host: String,
        val port: Int,
        val user: String,
        val query: Map<String, String>,
        val name: String,
    )

    private fun splitUrl(line: String): Parts {
        val scheme = line.substringBefore(':')
        val after = line.substringAfter("://")
        val name = fragmentName(line, scheme)
        val noFrag = after.substringBefore('#')
        val query = parseQuery(noFrag.substringAfter('?', ""))
        val main = noFrag.substringBefore('?')
        val user: String
        val hostport: String
        val at = main.lastIndexOf('@')
        if (at >= 0) {
            user = main.substring(0, at)
            hostport = main.substring(at + 1)
        } else {
            user = ""
            hostport = main
        }
        val defaultPort = when (scheme.lowercase()) {
            "ssh" -> 22
            "http" -> 80
            "socks", "socks5" -> 1080
            else -> 443
        }
        val (host, port) = splitHostPort(hostport, defaultPort)
        return Parts(host, port, user, query, name)
    }

    private fun splitHostPort(hostport: String, defaultPort: Int): Pair<String, Int> {
        if (hostport.startsWith('[')) {
            val host = hostport.substringBefore(']').removePrefix("[")
            val port = hostport.substringAfter(']', "").removePrefix(":").toIntOrNull() ?: defaultPort
            return host to port
        }
        val host = hostport.substringBeforeLast(':')
        val port = hostport.substringAfterLast(':', "").toIntOrNull() ?: defaultPort
        return host to port
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        return q.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val k = decode(part.substringBefore('='))
            val v = decode(part.substringAfter('=', ""))
            k to v
        }.toMap()
    }

    private fun fragmentName(line: String, fallback: String): String {
        val frag = line.substringAfter('#', "")
        return decode(frag).ifBlank { fallback }
    }

    private fun decodeUserinfo(raw: String): String {
        val text = if (raw.contains(':')) decode(raw) else runCatching { String(b64(raw)) }.getOrElse { decode(raw) }
        return text
    }

    private fun decode(s: String) = URLDecoder.decode(s, Charsets.UTF_8)

    private fun b64(s: String): ByteArray {
        val cleaned = s.trim().replace("\n", "").replace("\r", "")
        val pad = "=".repeat((4 - cleaned.length % 4) % 4)
        return try {
            Base64.getDecoder().decode(cleaned + pad)
        } catch (_: IllegalArgumentException) {
            Base64.getUrlDecoder().decode(cleaned + pad)
        }
    }
}
