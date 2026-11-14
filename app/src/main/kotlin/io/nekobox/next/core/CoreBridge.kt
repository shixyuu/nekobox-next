package io.nekobox.next.core

/** What the process asks the native core to do. The AAR and the stub both implement this. */
interface CoreBridge {
    val native: Boolean
    fun start(configJson: String, byeDpiJson: String, base: String, work: String, temp: String, platform: CorePlatform)
    fun reload(configJson: String, byeDpiJson: String)
    fun stop()
    fun version(): String
}

interface CorePlatform {
    fun protect(fd: Int): Boolean
    fun openTun(spec: String): Int
    fun log(message: String)
    fun findProcess(protocol: Int, src: String, srcPort: Int, dst: String, dstPort: Int): String
    fun defaultInterface(): String
}

fun byeDpiJson(launch: io.nekobox.next.engine.model.ByeDpiLaunch?): String {
    if (launch == null) return ""
    return buildString {
        append("{\"listen\":\"")
        append(launch.listen)
        append("\",\"username\":\"")
        append(launch.username)
        append("\",\"password\":\"")
        append(launch.password)
        append("\",\"args\":\"")
        append(launch.args.replace("\"", "\\\""))
        append("\",\"hosts\":[")
        append(launch.hosts.joinToString(",") { "\"${it.replace("\"", "")}\"" })
        append("]}")
    }
}
