package io.nekobox.next.engine

import io.nekobox.next.engine.model.HYSTERIA_DEFAULT_STUN_SERVERS
import io.nekobox.next.engine.model.Hysteria2Access
import io.nekobox.next.engine.model.Hysteria2Check
import io.nekobox.next.engine.model.Hysteria2Links
import io.nekobox.next.engine.model.Hysteria2Spec
import io.nekobox.next.engine.model.toSingBoxOutbound
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

class Hysteria2Test {
    @Test
    fun portHoppingOmitsSinglePortAndKeepsColonRanges() {
        val spec = Hysteria2Spec(
            server = "hy.example",
            serverPort = 0,
            serverPorts = listOf("2080:3000", "20000:20000"),
            hopInterval = "15s",
            hopIntervalMax = "45s",
            password = "pw",
        )
        assertTrue(Hysteria2Check.issues(spec).isEmpty())
        val json = spec.toSingBoxOutbound()
        assertFalse(json.containsKey("server_port"))
        assertEquals(listOf("2080:3000", "20000:20000"), json["server_ports"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("15s", json["hop_interval"]!!.jsonPrimitive.content)
        assertEquals("45s", json["hop_interval_max"]!!.jsonPrimitive.content)
        assertFalse(Hysteria2Check.validPortRange("20000"))
        assertTrue(Hysteria2Check.issues(spec.copy(serverPort = 443)).contains("ports_conflict"))
        assertTrue(Hysteria2Check.issues(spec.copy(serverPorts = emptyList(), serverPort = 443, hopInterval = "15s")).contains("hop_without_ports"))
    }

    @Test
    fun obfsGeckoAndBlankBandwidthUsesBbr() {
        val spec = Hysteria2Spec(
            server = "hy.example",
            serverPort = 443,
            password = "pw",
            obfs = "gecko",
            obfsPassword = "g",
            obfsMinPacketSize = 600,
            obfsMaxPacketSize = 1100,
            bbrProfile = "aggressive",
            disableChromeParrot = true,
            brutalDebug = true,
        )
        assertTrue(Hysteria2Check.issues(spec).isEmpty())
        val json = spec.toSingBoxOutbound()
        assertFalse(json.containsKey("up_mbps"))
        assertFalse(json.containsKey("down_mbps"))
        val obfs = json["obfs"]!!.jsonObject
        assertEquals("gecko", obfs["type"]!!.jsonPrimitive.content)
        assertEquals(600, obfs["min_packet_size"]!!.jsonPrimitive.content.toInt())
        assertEquals("aggressive", json["bbr_profile"]!!.jsonPrimitive.content)
        assertEquals("true", json["disable_chrome_parrot"]!!.jsonPrimitive.content)
        assertEquals("true", json["tls"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        assertTrue(Hysteria2Check.issues(spec.copy(obfsPassword = "")).contains("obfs_password_required"))
        assertTrue(Hysteria2Check.issues(spec.copy(obfsMinPacketSize = 2000)).contains("gecko_size"))
    }

    @Test
    fun realmConflictsWithServerAndFillsDefaultStun() {
        val bad = Hysteria2Spec(
            access = Hysteria2Access.REALM,
            server = "left.example",
            serverPort = 443,
            realmServerUrl = "https://realm.example",
            realmId = "room",
            stunServers = listOf("stun.example:3478"),
        )
        assertTrue(Hysteria2Check.issues(bad).contains("realm_conflicts_server"))
        val spec = bad.copy(server = "", serverPort = 0, portMappingEnabled = true, realmIpVersion = 6)
        assertTrue(Hysteria2Check.issues(spec).contains("port_mapping_ipv6"))
        val clean = spec.copy(portMappingEnabled = false, realmIpVersion = 0, stunServers = emptyList())
        assertTrue(Hysteria2Check.issues(clean).contains("stun_required"))
        val ready = clean.copy(stunServers = HYSTERIA_DEFAULT_STUN_SERVERS, realmToken = "tok")
        assertTrue(Hysteria2Check.issues(ready).isEmpty())
        val json = ready.toSingBoxOutbound()
        assertFalse(json.containsKey("server"))
        assertFalse(json.containsKey("server_port"))
        assertFalse(json.containsKey("server_ports"))
        val realm = json["realm"]!!.jsonObject
        assertEquals("https://realm.example", realm["server_url"]!!.jsonPrimitive.content)
        assertEquals(HYSTERIA_DEFAULT_STUN_SERVERS, realm["stun_servers"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun shareLinkRoundTripKeepsObfsHopSniPin() {
        val pin = "ab".repeat(32)
        val spec = Hysteria2Spec(
            server = "better.call",
            serverPort = 0,
            serverPorts = listOf("7000:10000", "20000:20000"),
            hopInterval = "20s",
            password = "saul:goodman",
            obfs = "salamander",
            obfsPassword = "op",
            serverName = "better.call",
            insecure = true,
            alpn = "h3",
            certificatePublicKeySha256 = pin,
            echConfig = "ech-bytes",
            echEnabled = true,
        )
        val link = Hysteria2Links.format("Saul", spec)
        assertTrue(link.startsWith("hysteria2://"))
        assertTrue(link.contains("7000-10000,20000"))
        val back = ShareLinkParser.parse(link).spec as Hysteria2Spec
        assertEquals("Saul", ShareLinkParser.parse(link).name)
        assertEquals(spec.serverPorts, back.serverPorts)
        assertEquals(0, back.serverPort)
        assertEquals("saul:goodman", back.password)
        assertEquals("salamander", back.obfs)
        assertEquals("op", back.obfsPassword)
        assertEquals("better.call", back.serverName)
        assertTrue(back.insecure)
        assertEquals("h3", back.alpn)
        assertEquals(pin, back.certificatePublicKeySha256)
        assertEquals("20s", back.hopInterval)
        assertEquals("ech-bytes", back.echConfig)
        val json = spec.toSingBoxOutbound()
        val written = json["tls"]!!.jsonObject["certificate_public_key_sha256"]!!.jsonArray[0].jsonPrimitive.content
        assertEquals(Base64.getEncoder().encodeToString(ByteArray(32) { 0xAB.toByte() }), written)
    }

    @Test
    fun mportQueryAndHy2Scheme() {
        val parsed = ShareLinkParser.parse(
            "hy2://pw@host.example:443?mport=20000-40000,50000&sni=h.example&insecure=1&obfs=gecko&obfs-password=g&pinSHA256=abc",
        )
        val spec = parsed.spec as Hysteria2Spec
        assertEquals(listOf("20000:40000", "50000:50000"), spec.serverPorts)
        assertEquals(0, spec.serverPort)
        assertEquals("gecko", spec.obfs)
        assertTrue(spec.insecure)
        assertEquals("abc", spec.certificatePublicKeySha256)
    }

    @Test
    fun realmAddressesBecomeSingBoxRealm() {
        val https = ShareLinkParser.parse("realm://tok@realm.example/room").spec as Hysteria2Spec
        assertEquals(Hysteria2Access.REALM, https.access)
        assertEquals("https://realm.example", https.realmServerUrl)
        assertEquals("tok", https.realmToken)
        assertEquals("room", https.realmId)
        assertEquals("", https.server)
        assertEquals(0, https.serverPort)
        assertEquals(HYSTERIA_DEFAULT_STUN_SERVERS, https.stunServers)
        val http = ShareLinkParser.parse("realm+http://tok@10.0.0.8:8080/room?stun=stun.l.google.com:19302&stun=stun.example:3478").spec as Hysteria2Spec
        assertEquals("http://10.0.0.8:8080", http.realmServerUrl)
        assertEquals(listOf("stun.l.google.com:19302", "stun.example:3478"), http.stunServers)
        val again = Hysteria2Links.parse(Hysteria2Links.format("ignored", https)).spec as Hysteria2Spec
        assertEquals(https.realmServerUrl, again.realmServerUrl)
        assertEquals(https.realmId, again.realmId)
        assertEquals(HYSTERIA_DEFAULT_STUN_SERVERS, again.stunServers)
    }

    @Test
    fun clashMetaMapsHopObfsAndTls() {
        val yaml = """
            proxies:
              - name: hy
                type: hysteria2
                server: hy.example
                port: 443
                password: pw
                ports: 20000-40000,50000
                hop-interval: 15
                up: 20
                down: 100 Mbps
                obfs: gecko
                obfs-password: g
                sni: hy.example
                skip-cert-verify: true
                alpn:
                  - h3
                fingerprint: chrome
                pinSHA256: abcd
        """.trimIndent()
        val spec = SubscriptionParser.parse(yaml).nodes.single().spec as Hysteria2Spec
        assertEquals(listOf("20000:40000", "50000:50000"), spec.serverPorts)
        assertEquals("15s", spec.hopInterval)
        assertEquals(20, spec.upMbps)
        assertEquals(100, spec.downMbps)
        assertEquals("gecko", spec.obfs)
        assertEquals("g", spec.obfsPassword)
        assertTrue(spec.insecure)
        assertEquals("h3", spec.alpn)
        assertTrue(spec.utlsEnabled)
        assertEquals("chrome", spec.utlsFingerprint)
        assertEquals("abcd", spec.certificatePublicKeySha256)
    }

    @Test
    fun singBoxOutboundJsonKeepsRealm() {
        val body = """
            {"type":"hysteria2","tag":"r","password":"p","tls":{"enabled":true,"server_name":"cert.example"},
             "realm":{"server_url":"https://realm.example","token":"tok","realm_id":"room","stun_servers":["stun.example:3478"]}}
        """.trimIndent()
        val spec = SubscriptionParser.parse(body).nodes.single().spec as Hysteria2Spec
        assertEquals(Hysteria2Access.REALM, spec.access)
        assertEquals("", spec.server)
        assertEquals("cert.example", spec.serverName)
        assertEquals("room", spec.realmId)
        assertEquals(listOf("stun.example:3478"), spec.stunServers)
    }
}