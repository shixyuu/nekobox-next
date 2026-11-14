# NekoBOX NEXT

NekoBOX NEXT 是 [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)（GPL-3.0，最后发行版 1.4.2）的重写。核心换成官方 [sing-box](https://github.com/SagerNet/sing-box) 1.14.4 的 `libbox`（gomobile Android 绑定），界面用 Jetpack Compose 与 Material 3 Expressive。

版权所有 (C) 2026 历史。本程序以 GNU GPL-3.0 发布，全文见 `LICENSE`。

本阶段是 **PHASE 1**：可编译的数据层、配置生成、订阅解析、VPN 启停，以及本地 ByeDPI 节点。尚未对齐的能力写在 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 架构

单进程。界面、Room、DataStore、WorkManager 与 `VpnService` 都在默认进程里。

```
app/          Compose UI、Room、DataStore、VpnService、WorkManager
engine/       纯 JVM：节点模型、sing-box 配置生成、分享链接与订阅解析
core/byedpi   Go：ByeDPI 去同步算法、带随机账号的本地 SOCKS5
core/mobile   Go：gomobile 入口，调用官方 libbox，并拉起 ByeDPI
```

节点协议配置用 kotlinx.serialization 存成一份带版本号的 JSON（`nodes.configJson` + `schemaVersion`），不是 Kryo，也不是每种协议一列的宽表。Room 从版本 1 到 2 有真实迁移（给 `nodes` 加 `pinned`），没有破坏性回退，查询不走主线程。

选中 ByeDPI 时，流量仍由 sing-box 的 TUN 收进来，再交给本机 SOCKS5。这个 SOCKS5 只监听 `127.0.0.1`，默认带随机用户名和密码。拨号前对 socket 调用 `VpnService.protect`，让去同步连接绕开 TUN。其他节点可以把 ByeDPI 设成 `detour`（前置代理）。

选择本地 SOCKS，而不是往 sing-box 里注册自定义 outbound：官方 `libbox.NewCommandServer` 只认内置协议表，外挂 outbound 会在每次升级时被甩掉。SOCKS outbound 和 `detour` 是稳定字段，protect 也落在我们自己的拨号器上。

混合入站同样绑在 `127.0.0.1`，并且默认写入随机用户名和密码。旧客户端这里是空的。

`minSdk` 是 26。通知渠道、现在这套 `VpnService.Builder`，以及 ByeDPI 用的套接字选项都从 Android 8 起可用，同时不必把 Android 8 设备排除在外。`targetSdk` / `compileSdk` 为 37。字节码目标是 JDK 17。ABI 拆分为 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`，并额外打一个 universal APK。仓库里没有 keystore。

## 本地构建

需要 JDK 17 或更高（本机用 21 编译、目标字节码 17）、Android SDK（已验证 API 37 与 Build-Tools 36）、以及构建 AAR 时的 Go 1.26.0、NDK 与 gomobile。

```bash
export ANDROID_HOME="$HOME/android-sdk"
./scripts/build-core.sh          # 产出 app/libs/nekocore.aar，版本钉在脚本顶部
./gradlew assembleDebug
./gradlew assemblePreview        # arm64 与 x86_64 各一个，R8 + 压缩 so，debug 签名
./gradlew :engine:test
( cd core && go test ./byedpi/ )
```

`preview` 是给分发通道用的构建类型：打开 R8 和资源压缩，`libgojni.so` 以旧式压缩方式打进 APK，签名用 debug。默认核心只编 `android/arm64` 和 `android/amd64`，所以 `assemblePreview` 得到 `app-arm64-v8a-preview.apk` 和 `app-x86_64-preview.apk`。四个 ABI 用 `NEKOCORE_TARGETS=android ./scripts/build-core.sh`，并自行把 `splits.abi.include` 改回包含 `armeabi-v7a` 和 `x86`。

构建标签只留界面和配置生成器用到的：`with_gvisor`、`with_quic`、`with_wireguard`、`with_utls`、`with_clash_api`，外加链接必需的 `badlinkname`、`tfogo_checklinkname0`。Tailscale、ACME、DHCP DNS、Cloudflare Tunnel、CCM、OCM 没有入口，不打进核心。链接参数是 `-trimpath` 和 `-ldflags "-s -w -buildid= -checklinkname=0"`。

`scripts/build-core.sh` 固定：

- sing-box `v1.14.4`（`core/mobile/go.mod` 的 require，没有指向仓库外的 replace）
- Go `1.26.0`（钉住的 gomobile 声明 `go 1.26.0`，且 `gomobile bind` 要把 `golang.org/x/mobile` 放进模块图。sing-box 1.14.4 自己的 `go` 行仍是 1.25.5。脚本发现本机更旧时会下载 1.26.0）
- gomobile `v0.0.0-20261011034528-197770444867`

没有 AAR 时，调试包仍能编译。`VpnService` 会建立 TUN 并读掉数据包，但 **不会转发**。日志里会写「核心未链接」。装上 AAR 之后，Gradle 改用 `src/coreAar`，由 libbox 通过 `OpenTun` 拿走同一个 fd。

Kotlin 使用 2.4.21。AGP 9 自带 Kotlin 插件，应用模块不再单独应用 `org.jetbrains.kotlin.android`。KSP 2.3.14 是当前能配上这条 Kotlin 线的符号处理版本。

## 这一阶段有什么

- 首页：未连接 / 连接中 / 已连接，约 250dp 的 Sunny → 连接中 ContainedLoadingIndicator → Flower 加对勾，速度，当前节点胶囊和节点选择表。
- 节点：分组 ButtonGroup、协议形状头像、FloatingToolbar（测速 SplitButton、排序、更新订阅）、FAB 菜单（手动添加、剪贴板、扫码粘贴、文件导入）、分组与订阅页。
- 一个节点编辑页，顶部切换协议，表单来自模型字段。ByeDPI 有轻度 tlsrec、分段、乱序、假包和自定义参数。Hysteria2 是按 sing-box 1.14.4 出站做的完整表单：直连或 Realm 打洞、端口跳跃、salamander / gecko 混淆、带宽留空即 BBR，以及 TLS、QUIC 和拨号字段。旧版 NekoBox 没有这些控件。
- 路由：规则 / 分应用。
- 更多：日志、设置（VPN / 仅代理、TUN 栈、MTU、IPv6、DNS、FakeDNS）、仪表板、备份恢复、规则集资源、关于。
- 配置生成覆盖 VLESS（含 Reality）、VMess、Trojan、Shadowsocks、Hysteria2（含 1.14 的端口跳跃、gecko 混淆、BBR 配置和 Realm）、Hysteria、TUIC、SOCKS、HTTP、WireGuard、AnyTLS、SSH、自定义 JSON、ByeDPI。
- 分享链接与订阅：Clash Meta YAML（SnakeYAML `SafeConstructor`）、v2rayN Base64、sing-box outbound JSON、SIP008、WireGuard INI。
- 深链：`sn://`、`clash://`、`ss`、`vmess`、`vless`、`trojan`、`hysteria2`、`hy2`、`realm`、`tuic`、`anytls`。`realm+http://` 可以粘贴导入，Android 的 scheme 不能包含加号，所以没有单独的意图过滤器。
- 快捷设置磁贴、开机自启（系统尚未授予 VPN 时只记日志）、订阅定期更新。

界面文案默认简体中文（`values/strings.xml`），英文在 `values-en`。

## 测试

在本环境中（JDK 21，Android SDK 37，Build-Tools 36，Go 1.22 只跑 ByeDPI 测试）：

- `cd core && go test ./byedpi/` 通过。覆盖参数解析、分段计划（split / disorder TTL / fake / oob / SNI / tlsrec / mod-http）、主机过滤、SOCKS5 鉴权，以及在 Linux 上对 echo 服务的 split+disorder 与 fake+oob 往返。
- `./gradlew :engine:test` 通过。覆盖分享链接、Clash YAML、Base64、sing-box JSON、SIP008、WireGuard INI、配置生成（Reality、ByeDPI detour、混合入站账号、IPv6 关闭、JSON 合并）和备份往返。
- `./gradlew assembleDebug` 通过，产出按 ABI 拆分的 debug APK。没有连接 AAR，所以这个包里的 TUN 不会转发。没有模拟器，Compose 界面没有截图。

`core/mobile` 对照的是 sing-box v1.14.4 的 libbox 源码（`Setup`、`NewCommandServer`、`PlatformInterface`、`TunOptions`）。`./scripts/build-core.sh` 打出 `app/libs/nekocore.aar` 之后，调试包会改用 `src/coreAar`，由 libbox 通过 `OpenTun` 拿走同一个 fd。链接参数带 `-checklinkname=0`，和上游 `build_libbox` 一样，否则 `os.checkPidfdOnce` 的 linkname 过不了 Go 1.26 的链接器。Java 包名是 `io.nekobox.next.corelib.mobile`：`native` 是 Java 关键字，不能放进包名。

AAR 需要 Go 1.26.0、NDK 和完整的 sing-box 依赖图（含 gVisor）。脚本会在 Go 过旧时自行安装 1.26.0。NDK 仍要事先放在 `ANDROID_HOME/ndk`。缺 SDK 时脚本直接失败并打印缺什么，而不是静默打出一个空包。
