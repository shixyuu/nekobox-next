package io.nekobox.next.data

import io.nekobox.next.db.AppPackageEntity
import io.nekobox.next.db.GroupEntity
import io.nekobox.next.db.NekoDatabase
import io.nekobox.next.db.NodeEntity
import io.nekobox.next.db.RuleEntity
import io.nekobox.next.db.RuleSetEntity
import io.nekobox.next.db.SubscriptionEntity
import io.nekobox.next.engine.backup.BackupCodec
import io.nekobox.next.engine.backup.BackupDocument
import io.nekobox.next.engine.backup.BackupSubscription
import io.nekobox.next.engine.config.SingBoxConfigGenerator
import io.nekobox.next.engine.model.ByeDpiSpec
import io.nekobox.next.engine.model.GeneratedConfig
import io.nekobox.next.engine.model.GeneratorSettings
import io.nekobox.next.engine.model.GroupRecord
import io.nekobox.next.engine.model.HttpSpec
import io.nekobox.next.engine.model.Hysteria2Spec
import io.nekobox.next.engine.model.HysteriaSpec
import io.nekobox.next.engine.model.ImportedNode
import io.nekobox.next.engine.model.NODE_SCHEMA_VERSION
import io.nekobox.next.engine.model.NodeRecord
import io.nekobox.next.engine.model.NodeSpec
import io.nekobox.next.engine.model.NodeSpecs
import io.nekobox.next.engine.model.RouteRule
import io.nekobox.next.engine.model.RuleSetSource
import io.nekobox.next.engine.model.SocksSpec
import io.nekobox.next.engine.model.TrojanSpec
import io.nekobox.next.engine.model.TuicSpec
import io.nekobox.next.engine.model.VlessSpec
import io.nekobox.next.engine.model.VmessSpec
import io.nekobox.next.engine.model.WireGuardSpec
import io.nekobox.next.engine.parse.ShareLinkParser
import io.nekobox.next.engine.parse.SubscriptionParser
import io.nekobox.next.settings.AppSettings
import io.nekobox.next.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val db: NekoDatabase,
    private val settingsStore: SettingsStore,
) {
    val groups = db.groups().observe()
    val subscriptions = db.subscriptions().observe()
    val rules = db.rules().observe()
    val ruleSets = db.ruleSets().observe()
    val packages = db.appPackages().observe()
    val settings = settingsStore.flow

    fun nodes(groupId: String): Flow<List<NodeEntity>> = db.nodes().observe(groupId)

    suspend fun ensureSeeded() {
        ensureSecrets()
        if (db.groups().count() > 0) return
        val groupId = UUID.randomUUID().toString()
        db.groups().upsert(GroupEntity(groupId, "默认", "selector", 0, null))
        val nodeId = UUID.randomUUID().toString()
        saveNode(nodeId, groupId, "ByeDPI 轻度", ByeDpiSpec(preset = "mild"), null)
        settingsStore.update { it.copy(selectedGroupId = groupId, selectedNodeId = nodeId) }
        db.groups().select(groupId, nodeId)
    }

    suspend fun ensureSecrets() {
        settingsStore.update { cur ->
            cur.copy(
                mixedUsername = cur.mixedUsername.ifBlank { token(8) },
                mixedPassword = cur.mixedPassword.ifBlank { token(18) },
                clashSecret = cur.clashSecret.ifBlank { token(18) },
                byeDpiUsername = cur.byeDpiUsername.ifBlank { token(8) },
                byeDpiPassword = cur.byeDpiPassword.ifBlank { token(18) },
            )
        }
    }

    suspend fun currentSettings(): AppSettings {
        ensureSecrets()
        return settingsStore.flow.first()
    }

    suspend fun updateSettings(block: (AppSettings) -> AppSettings) = settingsStore.update(block)

    suspend fun buildConfig(): GeneratedConfig {
        val s = currentSettings()
        val nodes = db.nodes().all().map { it.toRecord() }
        val groups = db.groups().all().map { group ->
            GroupRecord(
                id = group.id,
                name = group.name,
                type = group.type,
                nodeIds = db.nodes().inGroup(group.id).map { it.id },
                selectedNodeId = group.selectedNodeId ?: s.selectedNodeId,
            )
        }
        val rules = db.rules().all().map { decodeRule(it) }
        val sets = db.ruleSets().all().map { RuleSetSource(it.tag, it.url, it.format) }
        val gen = GeneratorSettings(
            mode = s.mode,
            tunStack = s.tunStack,
            mtu = s.mtu,
            ipv6 = s.ipv6,
            dnsRemote = s.dnsRemote,
            dnsDirect = s.dnsDirect,
            fakeDns = s.fakeDns,
            mixedPort = s.mixedPort,
            mixedUsername = s.mixedUsername,
            mixedPassword = s.mixedPassword,
            clashPort = s.clashPort,
            clashSecret = s.clashSecret,
            logLevel = s.logLevel,
            perAppMode = s.perAppMode,
            perAppPackages = db.appPackages().all(),
            globalOverride = s.globalOverride,
            byeDpiPort = s.byeDpiPort,
            byeDpiUsername = s.byeDpiUsername,
            byeDpiPassword = s.byeDpiPassword,
            selectedGroupId = s.selectedGroupId,
            selectedNodeId = s.selectedNodeId,
        )
        return SingBoxConfigGenerator.generate(gen, nodes, groups, rules, sets)
    }

    suspend fun selectNode(groupId: String, nodeId: String) {
        db.groups().select(groupId, nodeId)
        settingsStore.update { it.copy(selectedGroupId = groupId, selectedNodeId = nodeId) }
    }

    suspend fun addGroup(name: String, type: String): String {
        val id = UUID.randomUUID().toString()
        val order = db.groups().all().size
        db.groups().upsert(GroupEntity(id, name, type, order, null))
        return id
    }

    suspend fun deleteGroup(id: String) {
        db.nodes().inGroup(id).forEach { db.nodes().delete(it.id) }
        db.groups().delete(id)
    }

    suspend fun saveNode(id: String, groupId: String, name: String, spec: NodeSpec, subscriptionId: String?) {
        val existing = db.nodes().get(id)
        db.nodes().upsert(
            NodeEntity(
                id = id,
                groupId = groupId,
                name = name,
                protocol = NodeSpecs.protocolOf(spec),
                schemaVersion = NODE_SCHEMA_VERSION,
                configJson = NodeSpecs.encode(spec),
                subscriptionId = subscriptionId,
                latencyMs = existing?.latencyMs,
                sortOrder = existing?.sortOrder ?: db.nodes().inGroup(groupId).size,
                pinned = existing?.pinned ?: 0,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun importShare(groupId: String, link: String): String {
        val node = ShareLinkParser.parse(link)
        val id = UUID.randomUUID().toString()
        saveNode(id, groupId, node.name, node.spec, null)
        return id
    }

    suspend fun importText(groupId: String, text: String): Int {
        val batch = SubscriptionParser.parse(text)
        batch.nodes.forEach { saveNode(UUID.randomUUID().toString(), groupId, it.name, it.spec, null) }
        return batch.nodes.size
    }

    suspend fun addSubscription(name: String, url: String, groupId: String) {
        db.subscriptions().upsert(
            SubscriptionEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                url = url,
                groupId = groupId,
                userAgent = "NekoBOX NEXT",
                autoUpdateHours = 6,
                lastUpdatedAt = null,
                lastError = null,
            ),
        )
    }

    suspend fun deleteSubscription(id: String) {
        db.nodes().deleteSubscription(id)
        db.subscriptions().delete(id)
    }

    suspend fun refreshSubscription(id: String): String? {
        val sub = db.subscriptions().get(id) ?: return "订阅不存在"
        return try {
            val body = httpGet(sub.url, sub.userAgent)
            val batch = SubscriptionParser.parse(body)
            db.nodes().deleteSubscription(id)
            batch.nodes.forEachIndexed { index, node ->
                db.nodes().upsert(
                    NodeEntity(
                        id = UUID.randomUUID().toString(),
                        groupId = sub.groupId,
                        name = node.name,
                        protocol = NodeSpecs.protocolOf(node.spec),
                        schemaVersion = NODE_SCHEMA_VERSION,
                        configJson = NodeSpecs.encode(node.spec),
                        subscriptionId = id,
                        latencyMs = null,
                        sortOrder = index,
                        pinned = 0,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            db.subscriptions().upsert(sub.copy(lastUpdatedAt = System.currentTimeMillis(), lastError = batch.warnings.firstOrNull()))
            null
        } catch (e: Exception) {
            db.subscriptions().upsert(sub.copy(lastError = e.message))
            e.message
        }
    }

    suspend fun refreshAll() {
        db.subscriptions().all().forEach { refreshSubscription(it.id) }
    }

    suspend fun setLatency(nodeId: String, ms: Int?) = db.nodes().latency(nodeId, ms)

    suspend fun tcpPing(nodeId: String): Int? {
        val entity = db.nodes().get(nodeId) ?: return null
        val spec = NodeSpecs.decode(entity.configJson)
        val endpoint = endpointOf(spec) ?: return null
        val ms = ping(endpoint.first, endpoint.second)
        db.nodes().latency(nodeId, ms)
        return ms
    }

    suspend fun sortGroup(groupId: String, byLatency: Boolean) {
        val nodes = db.nodes().inGroup(groupId).sortedWith(
            if (byLatency) compareBy<NodeEntity> { it.latencyMs ?: Int.MAX_VALUE }.thenBy { it.name }
            else compareBy { it.name },
        )
        nodes.forEachIndexed { index, node -> db.nodes().order(node.id, index) }
    }

    suspend fun saveRule(rule: RouteRule) {
        val id = rule.id.ifBlank { UUID.randomUUID().toString() }
        val order = db.rules().all().size
        db.rules().upsert(
            RuleEntity(
                id = id,
                enabled = if (rule.enabled) 1 else 0,
                schemaVersion = 1,
                configJson = Json { encodeDefaults = true }.encodeToString(RouteRule.serializer(), rule.copy(id = id)),
                sortOrder = order,
            ),
        )
    }

    suspend fun deleteRule(id: String) = db.rules().delete(id)

    suspend fun saveRuleSet(tag: String, url: String) {
        db.ruleSets().upsert(RuleSetEntity(tag, url, "binary"))
    }

    suspend fun togglePackage(name: String, enabled: Boolean) {
        if (enabled) db.appPackages().upsert(AppPackageEntity(name)) else db.appPackages().delete(name)
    }

    suspend fun exportBackup(): String {
        val s = currentSettings()
        val doc = BackupDocument(
            groups = db.groups().all().map {
                GroupRecord(it.id, it.name, it.type, db.nodes().inGroup(it.id).map { n -> n.id }, it.selectedNodeId)
            },
            nodes = db.nodes().all().map { it.toRecord() },
            subscriptions = db.subscriptions().all().map {
                BackupSubscription(it.id, it.name, it.url, it.userAgent, it.autoUpdateHours)
            },
            rules = db.rules().all().map { decodeRule(it) },
            settings = buildJsonObject {
                put("mode", s.mode.name)
                put("tunStack", s.tunStack)
                put("mtu", s.mtu)
                put("ipv6", s.ipv6)
                put("fakeDns", s.fakeDns)
            },
        )
        return BackupCodec.encode(doc)
    }

    suspend fun importBackup(raw: String) {
        val doc = BackupCodec.decode(raw)
        doc.groups.forEach {
            db.groups().upsert(GroupEntity(it.id, it.name, it.type, 0, it.selectedNodeId))
        }
        doc.nodes.forEach {
            saveNode(it.id, it.groupId, it.name, it.spec, null)
        }
        doc.subscriptions.forEach {
            db.subscriptions().upsert(
                SubscriptionEntity(it.id, it.name, it.url, doc.groups.firstOrNull()?.id ?: "", it.userAgent, it.autoUpdateHours, null, null),
            )
        }
        doc.rules.forEach { saveRule(it) }
    }

    fun decode(entity: NodeEntity): NodeSpec = NodeSpecs.decode(entity.configJson)

    suspend fun decodeNode(id: String): NodeSpec? = db.nodes().get(id)?.let { NodeSpecs.decode(it.configJson) }

    suspend fun findNode(id: String) = db.nodes().get(id)

    suspend fun deleteNode(id: String) = db.nodes().delete(id)

    suspend fun snapshotNodes(groupId: String) = db.nodes().inGroup(groupId)

    private fun NodeEntity.toRecord() = NodeRecord(id, name, groupId, NodeSpecs.decode(configJson))

    private fun decodeRule(entity: RuleEntity): RouteRule =
        Json { ignoreUnknownKeys = true }.decodeFromString(RouteRule.serializer(), entity.configJson)

    private fun endpointOf(spec: NodeSpec): Pair<String, Int>? = when (spec) {
        is VlessSpec -> spec.server to spec.serverPort
        is VmessSpec -> spec.server to spec.serverPort
        is TrojanSpec -> spec.server to spec.serverPort
        is io.nekobox.next.engine.model.ShadowsocksSpec -> spec.server to spec.serverPort
        is Hysteria2Spec -> spec.server to spec.serverPort
        is HysteriaSpec -> spec.server to spec.serverPort
        is TuicSpec -> spec.server to spec.serverPort
        is SocksSpec -> spec.server to spec.serverPort
        is HttpSpec -> spec.server to spec.serverPort
        is WireGuardSpec -> spec.server to spec.serverPort
        is io.nekobox.next.engine.model.AnyTlsSpec -> spec.server to spec.serverPort
        is io.nekobox.next.engine.model.SshSpec -> spec.server to spec.serverPort
        is ByeDpiSpec, is io.nekobox.next.engine.model.CustomSpec -> null
    }

    private fun ping(host: String, port: Int): Int {
        val start = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 3000)
        }
        return ((System.nanoTime() - start) / 1_000_000).toInt()
    }

    private fun httpGet(url: String, userAgent: String): String {
        val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.length > 2_000_000) error("subscription is too large")
            return body
        }
    }

    private fun token(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }
}

fun importedName(node: ImportedNode) = node.name
