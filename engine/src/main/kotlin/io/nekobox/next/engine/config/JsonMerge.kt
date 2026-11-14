package io.nekobox.next.engine.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deep-merges a user JSON document over the generated sing-box config.
 * Objects merge recursively. Arrays and scalars in the override replace
 * the base value. A JSON null removes the key.
 */
object JsonMerge {
    private val json = Json { prettyPrint = true }

    fun parseObject(raw: String): JsonObject {
        val element = json.parseToJsonElement(raw)
        return element as? JsonObject ?: error("override must be a JSON object")
    }

    fun merge(base: JsonObject, override: JsonObject): JsonObject {
        val out = base.toMutableMap()
        override.forEach { (key, value) ->
            if (value is JsonNull) {
                out.remove(key)
                return@forEach
            }
            val existing = out[key]
            out[key] = if (existing is JsonObject && value is JsonObject) merge(existing, value) else value
        }
        return JsonObject(out)
    }

    fun encode(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)

    fun pretty(raw: String): String = encode(json.parseToJsonElement(raw))
}

fun formatRate(bytesPerSecond: Long): String {
    val v = bytesPerSecond.coerceAtLeast(0).toDouble()
    return when {
        v < 1024 -> "${v.toLong()} B/s"
        v < 1024 * 1024 -> String.format("%.1f KB/s", v / 1024)
        v < 1024 * 1024 * 1024 -> String.format("%.1f MB/s", v / (1024 * 1024))
        else -> String.format("%.2f GB/s", v / (1024 * 1024 * 1024))
    }
}
