package io.nekobox.next.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Talks to the sing-box Clash API on loopback. Selector changes do not reload the core. */
class ClashClient(private val port: Int, private val secret: String) {
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun select(group: String, outbound: String) {
        val body = """{"name":"$outbound"}""".toRequestBody("application/json".toMediaType())
        val call = Request.Builder()
            .url("http://127.0.0.1:$port/proxies/$group")
            .put(body)
            .header("Authorization", "Bearer $secret")
            .build()
        http.newCall(call).execute().close()
    }

    fun delay(tag: String, url: String, timeoutMs: Int = 5000): Int {
        val encoded = java.net.URLEncoder.encode(url, "UTF-8")
        val call = Request.Builder()
            .url("http://127.0.0.1:$port/proxies/$tag/delay?timeout=$timeoutMs&url=$encoded")
            .header("Authorization", "Bearer $secret")
            .build()
        http.newCall(call).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(text.ifBlank { "HTTP ${response.code}" })
            return json.parseToJsonElement(text).jsonObject["delay"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: error("no delay")
        }
    }

    fun trafficOnce(): Pair<Long, Long>? {
        val call = Request.Builder()
            .url("http://127.0.0.1:$port/traffic")
            .header("Authorization", "Bearer $secret")
            .build()
        http.newCall(call).execute().use { response ->
            val line = response.body?.source()?.readUtf8Line() ?: return null
            val obj = json.parseToJsonElement(line).jsonObject
            val up = obj["up"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
            val down = obj["down"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
            return up to down
        }
    }
}
