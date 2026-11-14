package io.nekobox.next.engine.backup

import io.nekobox.next.engine.NekoJson
import io.nekobox.next.engine.model.GroupRecord
import io.nekobox.next.engine.model.NodeRecord
import io.nekobox.next.engine.model.RouteRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class BackupSubscription(
    val id: String,
    val name: String,
    val url: String,
    val userAgent: String = "NekoBOX NEXT",
    val autoUpdateHours: Int = 6,
)

@Serializable
data class BackupDocument(
    val schema: Int = 1,
    val groups: List<GroupRecord> = emptyList(),
    val nodes: List<NodeRecord> = emptyList(),
    val subscriptions: List<BackupSubscription> = emptyList(),
    val rules: List<RouteRule> = emptyList(),
    val settings: JsonObject = buildJsonObject { },
)

object BackupCodec {
    fun encode(doc: BackupDocument): String = NekoJson.encodeToString(BackupDocument.serializer(), doc)
    fun decode(raw: String): BackupDocument = NekoJson.decodeFromString(BackupDocument.serializer(), raw)
}
