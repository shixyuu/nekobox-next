package io.nekobox.next.core

/** Compiled only when app/libs/nekocore.aar is present. */
fun createCoreBridge(): CoreBridge = LibboxCoreBridge()

private class LibboxCoreBridge : CoreBridge {
    override val native = true

    override fun start(configJson: String, byeDpiJson: String, base: String, work: String, temp: String, platform: CorePlatform) {
        io.nekobox.next.corelib.mobile.Mobile.start(configJson, byeDpiJson, base, work, temp, adapt(platform))
    }

    override fun reload(configJson: String, byeDpiJson: String) {
        io.nekobox.next.corelib.mobile.Mobile.reload(configJson, byeDpiJson)
    }

    override fun stop() {
        io.nekobox.next.corelib.mobile.Mobile.stop()
    }

    override fun version(): String = io.nekobox.next.corelib.mobile.Mobile.coreVersion()

    private fun adapt(host: CorePlatform) = object : io.nekobox.next.corelib.mobile.Platform {
        override fun protect(fd: Int): Boolean = host.protect(fd)
        override fun openTun(spec: String): Int = host.openTun(spec)
        override fun writeLog(message: String) = host.log(message)
        override fun findProcess(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int): String =
            host.findProcess(ipProtocol, sourceAddress, sourcePort, destinationAddress, destinationPort)
        override fun defaultInterface(): String = host.defaultInterface()
    }
}
