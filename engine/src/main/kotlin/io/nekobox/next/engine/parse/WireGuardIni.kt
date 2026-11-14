package io.nekobox.next.engine.parse

import io.nekobox.next.engine.model.ImportedNode
import io.nekobox.next.engine.model.WireGuardSpec

object WireGuardIni {
    fun parse(text: String, name: String = "wireguard"): ImportedNode {
        val map = mutableMapOf<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) return@forEach
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=', "").trim()
            if (key.isNotEmpty()) map[key.lowercase()] = value
        }
        val endpoint = map["endpoint"].orEmpty()
        val host = endpoint.substringBeforeLast(':')
        val port = endpoint.substringAfterLast(':', "51820").toIntOrNull() ?: 51820
        return ImportedNode(
            name,
            WireGuardSpec(
                server = host.removePrefix("[").removeSuffix("]"),
                serverPort = port,
                privateKey = map["privatekey"].orEmpty(),
                peerPublicKey = map["publickey"].orEmpty(),
                preSharedKey = map["presharedkey"].orEmpty(),
                localAddress = map["address"].orEmpty(),
                mtu = map["mtu"]?.toIntOrNull() ?: 1280,
            ),
        )
    }
}
