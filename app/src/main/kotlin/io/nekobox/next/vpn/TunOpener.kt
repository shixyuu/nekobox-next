package io.nekobox.next.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Builds the system TUN from either a libbox OpenTun spec or the generated sing-box config. */
object TunOpener {
    private val json = Json { ignoreUnknownKeys = true }

    fun openSpec(vpn: VpnService, spec: String): Int {
        val root = json.parseToJsonElement(spec).jsonObject
        val builder = vpn.Builder()
        builder.setSession("NekoBOX NEXT")
        builder.setMtu(root["mtu"]?.jsonPrimitive?.content?.toIntOrNull() ?: 9000)
        addresses(root, "inet4Address", builder)
        addresses(root, "inet6Address", builder)
        routes(root, "inet4Route", builder)
        routes(root, "inet6Route", builder)
        if ((root["inet4Route"]?.jsonArray?.size ?: 0) == 0) {
            builder.addRoute("0.0.0.0", 0)
        }
        applyPackages(vpn, builder, strings(root, "includePackage"), strings(root, "excludePackage"))
        strings(root, "dnsServers").forEach { runCatching { builder.addDnsServer(it.substringBefore("/")) } }
        builder.setBlocking(false)
        val pfd = builder.establish() ?: error("VpnService.establish returned null")
        return pfd.detachFd()
    }

    fun openFromConfig(vpn: VpnService, configJson: String): ParcelFileDescriptor {
        val root = json.parseToJsonElement(configJson).jsonObject
        val tun = root["inbounds"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.content == "tun" }
            ?: error("config has no tun inbound")
        val builder = vpn.Builder()
        builder.setSession("NekoBOX NEXT")
        builder.setMtu(tun["mtu"]?.jsonPrimitive?.content?.toIntOrNull() ?: 9000)
        tun["address"]?.jsonArray?.forEach { cidr ->
            val raw = cidr.jsonPrimitive.content
            val ip = raw.substringBefore("/")
            val prefix = raw.substringAfter("/", "32").toInt()
            builder.addAddress(ip, prefix)
        }
        builder.addRoute("0.0.0.0", 0)
        if (tun["address"]?.jsonArray?.any { it.jsonPrimitive.content.contains(":") } == true) {
            builder.addRoute("::", 0)
        }
        applyPackages(
            vpn,
            builder,
            tun["include_package"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            tun["exclude_package"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        )
        builder.setBlocking(false)
        return builder.establish() ?: error("VpnService.establish returned null")
    }

    private fun addresses(root: kotlinx.serialization.json.JsonObject, key: String, builder: VpnService.Builder) {
        root[key]?.jsonArray?.forEach { cidr ->
            val raw = cidr.jsonPrimitive.content
            builder.addAddress(raw.substringBefore("/"), raw.substringAfter("/", "32").toInt())
        }
    }

    private fun routes(root: kotlinx.serialization.json.JsonObject, key: String, builder: VpnService.Builder) {
        root[key]?.jsonArray?.forEach { cidr ->
            val raw = cidr.jsonPrimitive.content
            builder.addRoute(raw.substringBefore("/"), raw.substringAfter("/", "0").toInt())
        }
    }

    private fun strings(root: kotlinx.serialization.json.JsonObject, key: String) =
        root[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun applyPackages(vpn: VpnService, builder: VpnService.Builder, include: List<String>, exclude: List<String>) {
        if (include.isNotEmpty()) {
            include.forEach { runCatching { builder.addAllowedApplication(it) } }
            return
        }
        runCatching { builder.addDisallowedApplication(vpn.packageName) }
        exclude.forEach { runCatching { builder.addDisallowedApplication(it) } }
    }
}
