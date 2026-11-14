package io.nekobox.next.engine

import io.nekobox.next.engine.backup.BackupCodec
import io.nekobox.next.engine.backup.BackupDocument
import io.nekobox.next.engine.config.JsonMerge
import io.nekobox.next.engine.config.SingBoxConfigGenerator
import io.nekobox.next.engine.config.formatRate
import io.nekobox.next.engine.model.ByeDpiSpec
import io.nekobox.next.engine.model.CustomSpec
import io.nekobox.next.engine.model.GeneratorSettings
import io.nekobox.next.engine.model.GroupRecord
import io.nekobox.next.engine.model.Hysteria2Spec
import io.nekobox.next.engine.model.NodeRecord
import io.nekobox.next.engine.model.NodeSpecs
import io.nekobox.next.engine.model.PerAppMode
import io.nekobox.next.engine.model.RouteRule
import io.nekobox.next.engine.model.RuleSetSource
import io.nekobox.next.engine.model.RunMode
import io.nekobox.next.engine.model.ShadowsocksSpec
import io.nekobox.next.engine.model.SocksSpec
import io.nekobox.next.engine.model.TrojanSpec
import io.nekobox.next.engine.model.TuicSpec
import io.nekobox.next.engine.model.VlessSpec
import io.nekobox.next.engine.model.VmessSpec
import io.nekobox.next.engine.model.WireGuardSpec
import io.nekobox.next.engine.parse.ShareLinkParser
import io.nekobox.next.engine.parse.SubscriptionParser
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class EngineTest {
    @Test
    fun shareLinksRoundTripIntoTypedSpecs() {
        val vless = ShareLinkParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@example.com:443?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome&pbk=PUB&sid=ab&type=tcp&flow=xtls-rprx-vision#Reality",
        )
        val spec = vless.spec as VlessSpec
        assertEquals("Reality", vless.name)
        assertEquals("example.com", spec.server)
        assertEquals("PUB", spec.realityPublicKey)
        assertEquals("xtls-rprx-vision", spec.flow)
        assertEquals("chrome", spec.fingerprint)
        assertTrue(spec.tls)

        val vmessJson = """{"v":"2","ps":"vm","add":"1.2.3.4","port":"10086","id":"uuid","aid":"0","scy":"auto","net":"ws","tls":"tls","sni":"a.com","host":"a.com","path":"/ray"}"""
        val vmess = ShareLinkParser.parse("vmess://${Base64.getEncoder().encodeToString(vmessJson.toByteArray())}")
        assertEquals("ws", (vmess.spec as VmessSpec).network)
        assertTrue((vmess.spec as VmessSpec).tls)

        val ss = ShareLinkParser.parse("ss://${Base64.getEncoder().encodeToString("aes-256-gcm:secret".toByteArray())}@10.0.0.8:8388#shadow")
        assertEquals("aes-256-gcm", (ss.spec as ShadowsocksSpec).method)
        assertEquals("secret", (ss.spec as ShadowsocksSpec).password)

        val hy2 = ShareLinkParser.parse("hysteria2://pass@hy.example:443?sni=hy.example&obfs=salamander&obfs-password=op#hy")
        assertEquals("salamander", (hy2.spec as Hysteria2Spec).obfs)

        val tuic = ShareLinkParser.parse("tuic://uuid:pw@tuic.example:443?congestion_control=bbr&sni=tuic.example#tu")
        assertEquals("bbr", (tuic.spec as TuicSpec).congestionControl)
        assertEquals("pw", (tuic.spec as TuicSpec).password)

        val trojan = ShareLinkParser.parse("trojan://secret@t.example:443?sni=t.example#tj")
        assertEquals("secret", (trojan.spec as TrojanSpec).password)
    }

    @Test
    fun subscriptionsFromFixtures() {
        val clash = SubscriptionParser.parse(fixture("clash-meta.yaml"))
        assertEquals(3, clash.nodes.size)
        assertTrue(clash.nodes[1].spec is VlessSpec)
        assertEquals("PUBKEY", (clash.nodes[1].spec as VlessSpec).realityPublicKey)

        val links = buildString {
            appendLine("vless://11111111-1111-1111-1111-111111111111@a.example:443?security=tls&sni=a.example#a")
            appendLine("trojan://pw@b.example:443?sni=b.example#b")
        }
        val b64 = SubscriptionParser.parse(Base64.getEncoder().encodeToString(links.toByteArray()))
        assertEquals(2, b64.nodes.size)

        val sing = SubscriptionParser.parse(fixture("singbox-outbounds.json"))
        assertEquals(2, sing.nodes.size)
        assertTrue(sing.nodes[0].spec is ShadowsocksSpec)
        assertTrue(sing.nodes[1].spec is SocksSpec)

        val sip = SubscriptionParser.parse(fixture("sip008.json"))
        assertEquals("aes-128-gcm", (sip.nodes.single().spec as ShadowsocksSpec).method)

        val wg = SubscriptionParser.parse(fixture("wireguard.ini"))
        val spec = wg.nodes.single().spec as WireGuardSpec
        assertEquals("203.0.113.8", spec.server)
        assertEquals(51820, spec.serverPort)
        assertEquals("PRIV", spec.privateKey)
    }

    @Test
    fun yamlSafeLoaderRejectsCustomTags() {
        val batch = SubscriptionParser.parse("proxies:\n  - !!java.lang.Runtime [\"\"]\n")
        assertTrue(batch.nodes.isEmpty())
        assertTrue(batch.warnings.isNotEmpty() || batch.nodes.isEmpty())
    }

    @Test
    fun generatorCoversProtocolsAndByeDpiDetour() {
        val bye = NodeRecord("bd", "ByeDPI", "g", ByeDpiSpec(preset = "disorder", hosts = listOf("blocked.test")))
        val vless = NodeRecord(
            "v",
            "reality",
            "g",
            VlessSpec(
                server = "edge.example",
                serverPort = 443,
                uuid = "11111111-1111-1111-1111-111111111111",
                flow = "xtls-rprx-vision",
                tls = true,
                serverName = "www.microsoft.com",
                fingerprint = "chrome",
                realityPublicKey = "PUB",
                realityShortId = "ab",
                detourNodeId = "bd",
            ),
        )
        val custom = NodeRecord("c", "raw", "g", CustomSpec("""{"type":"socks","server":"9.9.9.9","server_port":1080}"""))
        val settings = GeneratorSettings(
            mode = RunMode.VPN,
            tunStack = "gvisor",
            mtu = 1400,
            ipv6 = false,
            fakeDns = true,
            mixedUsername = "u",
            mixedPassword = "p",
            clashSecret = "s3cret",
            perAppMode = PerAppMode.DENY,
            perAppPackages = listOf("com.android.vending"),
            byeDpiUsername = "bye-user",
            byeDpiPassword = "bye-pass",
            selectedGroupId = "g",
            selectedNodeId = "v",
            globalOverride = """{"log":{"level":"debug"}}""",
        )
        val generated = SingBoxConfigGenerator.generate(
            settings,
            listOf(bye, vless, custom),
            listOf(GroupRecord("g", "默认", "selector", listOf("bd", "v", "c"), "v")),
            listOf(RouteRule(id = "r", domainSuffix = listOf("cn"), outbound = "bypass")),
            listOf(RuleSetSource("geo", "https://example.com/geo.srs")),
        )
        val root = JsonMerge.parseObject(generated.singBoxJson)
        assertEquals("debug", root["log"]!!.jsonObject["level"]!!.jsonPrimitive.content)
        val outs = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        val vlessOut = outs.first { it["tag"]?.jsonPrimitive?.content == "node-v" }
        assertEquals("node-bd", vlessOut["detour"]!!.jsonPrimitive.content)
        assertEquals("PUB", vlessOut["tls"]!!.jsonObject["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content)
        val byeOut = outs.first { it["tag"]?.jsonPrimitive?.content == "node-bd" }
        assertEquals("socks", byeOut["type"]!!.jsonPrimitive.content)
        assertEquals("bye-user", byeOut["username"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", byeOut["server"]!!.jsonPrimitive.content)
        val mixed = root["inbounds"]!!.jsonArray.first { it.jsonObject["type"]!!.jsonPrimitive.content == "mixed" }.jsonObject
        assertEquals("u", mixed["users"]!!.jsonArray.first().jsonObject["username"]!!.jsonPrimitive.content)
        val tun = root["inbounds"]!!.jsonArray.first { it.jsonObject["type"]!!.jsonPrimitive.content == "tun" }.jsonObject
        assertEquals("gvisor", tun["stack"]!!.jsonPrimitive.content)
        assertFalse(tun["address"]!!.jsonArray.any { it.jsonPrimitive.content.contains(":") && it.jsonPrimitive.content.contains("fdfe") })
        assertEquals("com.android.vending", tun["exclude_package"]!!.jsonArray.first().jsonPrimitive.content)
        val launch = generated.byeDpi!!
        assertEquals("--disorder 1", launch.args)
        assertEquals(listOf("blocked.test"), launch.hosts)
        val selector = outs.first { it["type"]?.jsonPrimitive?.content == "selector" }
        assertEquals("node-v", selector["default"]!!.jsonPrimitive.content)
    }

    @Test
    fun mergeReplacesArraysAndDeletesNulls() {
        val base = JsonMerge.parseObject("""{"a":{"b":1,"c":2},"list":[1]}""")
        val over = JsonMerge.parseObject("""{"a":{"c":null,"d":3},"list":[9]}""")
        val merged = JsonMerge.merge(base, over)
        assertEquals("9", merged["list"]!!.jsonArray.first().jsonPrimitive.content)
        assertFalse(merged["a"]!!.jsonObject.containsKey("c"))
        assertEquals("3", merged["a"]!!.jsonObject["d"]!!.jsonPrimitive.content)
    }

    @Test
    fun backupRoundTripAndSchema() {
        val node = NodeRecord("1", "n", "g", SocksSpec(server = "1.1.1.1", serverPort = 1, username = "a", password = "b"))
        val doc = BackupDocument(groups = listOf(GroupRecord("g", "默认")), nodes = listOf(node))
        val again = BackupCodec.decode(BackupCodec.encode(doc))
        assertEquals("1.1.1.1", (again.nodes.single().spec as SocksSpec).server)
        val encoded = NodeSpecs.encode(node.spec)
        assertTrue(encoded.contains("\"protocol\":\"socks\""))
        assertEquals("socks", NodeSpecs.protocolOf(NodeSpecs.decode(encoded)))
    }

    @Test
    fun rateFormat() {
        assertEquals("512 B/s", formatRate(512))
        assertTrue(formatRate(2048).contains("KB/s"))
    }

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()
}
