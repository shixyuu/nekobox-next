package io.nekobox.next.core

/** Used when scripts/build-core.sh has not produced app/libs/nekocore.aar yet. */
fun createCoreBridge(): CoreBridge = StubCoreBridge()

private class StubCoreBridge : CoreBridge {
    override val native = false
    override fun start(configJson: String, byeDpiJson: String, base: String, work: String, temp: String, platform: CorePlatform) {
        platform.log("native core is not linked; holding the TUN without sing-box")
    }
    override fun reload(configJson: String, byeDpiJson: String) = Unit
    override fun stop() = Unit
    override fun version() = "stub"
}
