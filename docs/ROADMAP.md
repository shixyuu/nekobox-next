# 路线图

PHASE 1 已经能生成配置、解析订阅、开关 VPN，并跑通本地 ByeDPI。下面按优先级排列还没对齐 NekoBox 1.4.2 的部分。每一项都写清缺的是哪一层。

## P0：没有这些，日常使用不完整

1. **在 CI 和本机打出 `nekocore.aar` 并完成一次真机握手。** 绑定代码在 `core/mobile`，脚本在 `scripts/build-core.sh`。缺 Go 1.25.5、NDK 或 sing-box 模块下载时，调试包只会占住 TUN。要对着 `sing-box check` 把生成的 JSON 再跑一遍，确认 1.14 的 DNS / clash_api 字段没有改名。
2. **选择组热切换的回归。** 普通节点走 Clash API `PUT /proxies/{group}`，不重载进程。切到另一份 ByeDPI 参数会 `ACTION_RELOAD`，因为去同步配置绑在已经监听的 SOCKS 上。还要补：组内 urltest 的自动选优、前端/落地两个槽位的专用 UI（现在用节点上的「前置代理」`detourNodeId` 表达）。
3. **URL Test 的失败展示。** TCPing 已对节点服务器做直连计时。URL Test 只在核心已连接时打 Clash `delay`。未连接、ByeDPI（没有远端地址）和自定义 JSON 节点要有明确的空结果，而不是静默失败。
4. **二维码。** 现在的「扫码」是粘贴框。应接 CameraX + 条码识别，并支持从图片解码。

## P1：路由与 DNS 对齐

5. **规则编辑器。** 数据模型和生成器已有 domain / suffix / keyword / ip / port / package / rule-set，以及 proxy、bypass、block、指定节点。界面只做了「域名后缀 → 绕过」。要补完整表单、排序、启用开关。
6. **规则集下载与更新。** `rule_sets` 表和 remote rule-set 出站已经生成。还缺地理资源包管理、本地 `.srs` 导入、更新失败重试。
7. **分应用列表的搜索和系统应用折叠。** 允许/排除模式会写进 TUN 的 `include_package` / `exclude_package`。VPN 进程自身在非白名单模式下会被 `addDisallowedApplication` 排除，避免回环。
8. **DNS 细节。** 设置里有远程/直连、FakeDNS、IPv6 策略。还缺 DoH 路径校验、DNS 规则（国内直连）、FakeIP 过滤列表、`local` DNS 平台传输（现在 `LocalDNSTransport` 返回禁用错误）。
9. **TUN。** 栈（gvisor / system / mixed）、MTU、IPv6 地址已进配置。还缺 HTTP 代理入站开关以外的系统代理、分应用之外的 UID 规则、`strict_route` 的用户开关。

## P2：协议与订阅

10. **ShadowTLS、Hysteria 1 的完整传输、Naive、SSH 私钥文件、WireGuard reserved 的表单校验。** 生成器里 Hysteria、AnyTLS、SSH、WireGuard 已有基本字段，编辑页是按 JSON 键铺的，缺协议专用校验和分享链接往返测试补全。
11. **传输层。** VLESS / VMess / Trojan 会写出 ws、grpc、http、httpupgrade。还缺 xhttp、splithttp、reality 的 spiderX / 短 ID 列表、uTLS 指纹列表。
12. **订阅。** 已解析 Clash proxies、Base64 分享链接、sing-box outbounds、SIP008、WireGuard INI。还缺：只更新节点不碰手写节点的冲突策略（现在按 `subscriptionId` 整组替换）、ETag、自定义 User-Agent 的设置页、解析警告列表。
13. **链式代理的多跳。** 一跳 `detour` 已生成。多跳和「落地组」还没有。
14. **自定义全局 JSON。** 对象递归合并，数组被覆盖，`null` 删键。还缺编辑器、合并预览、用 sing-box 校验失败时的回滚。

## P3：系统集成与抛光

15. **快捷设置磁贴状态。** 磁贴能在已授权时启动 VPN；还不会反映已连接/未连接，也不能从磁贴断开。
16. **开机自启。** 已授予 VPN 且开关打开时会拉起服务。Android 14+ 对后台启动前台服务的限制要在真机上再确认。
17. **仪表板。** 现在只显示 Clash `/traffic` 的上下行。还缺连接表、关闭连接、流量图。
18. **日志。** 内存环和清空已有。还缺级别过滤、导出、把 libbox 的日志回调接到同一条流（桥接里的 `WriteLog` 已留好）。
19. **备份。** JSON 往返已测。还缺加密、含设置密钥的可选导出、与旧版 NekoBox 备份的导入。
20. **通知与快捷方式。** 前台通知有断开动作。还缺磁贴以外的 App Shortcut、始终开启 VPN 的说明。
21. **动态配色** 已是设置项，默认关闭，种子色是青绿。还缺预览。
22. **测试。** Room 迁移 1→2 已写在 `MIGRATION_1_2`，还没有 Instrumentation 测试。配置生成尚未对 sing-box 二进制做 `check`。没有 Paparazzi / 设备截图。

不在范围内、也不该做的：把 ByeDPI 说成能绕过 IP 封锁。它只影响按 SNI 或关键词的 DPI。见 [BYEDPI.md](BYEDPI.md)。
