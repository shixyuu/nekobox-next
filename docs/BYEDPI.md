# ByeDPI 节点

ByeDPI 节点没有远端服务器。选中它之后，sing-box 把流量交给本机的 SOCKS5，由 Go 拨号器在发出去的第一段数据上做 DPI 去同步，然后直连目标。也可以把这个节点设成别的节点的前置（`detour`）：那时被去同步的是「连到代理服务器」的那一段 TCP，而不是用户流量本身。

算法对照 [hufrea/byedpi](https://github.com/hufrea/byedpi) v0.17.3（MIT，Copyright (c) 2024 hufrea）。声明见 `THIRD_PARTY_NOTICES`。这是 Go 重新实现，没有把 C 源码编进 APK。

## 为什么是本地 SOCKS，而不是自定义 outbound

官方 libbox 的 `NewCommandServer` 只加载 sing-box 自带的协议。自己注册 outbound 就要替换或 fork 注册表，升级 1.14 时会断。SOCKS outbound 和 `detour` 是配置里的稳定字段。

去同步套接字必须 `protect`。如果拨号发生在 sing-box 进程内的某个我们插不进去的 outbound 里，就拿不到「在 connect 之前」的 fd。本地 SOCKS 的 `Dialer.Control` 里可以调用 `VpnService.protect`，连接不会再被 TUN 吸回去。监听地址固定是 `127.0.0.1`，并带随机账号，避免同机其他应用白嫖。

同一时刻只有一份 ByeDPI 参数在监听。从普通节点切到另一份 ByeDPI 预设会重载核心；普通节点之间的选择组切换不重载。

## 参数

编辑页预设写进节点的 `rawArgs`，运行时交给 `byedpi.ParseArgs`。

| 预设 | 参数 |
| --- | --- |
| 轻度 tlsrec | `--auto=torst --timeout 3 --tlsrec 3+s` |
| 分段 split | `--split 1 --split 3+s` |
| 乱序 disorder | `--disorder 1` |
| 假包 fake+ttl | `--fake 1 --ttl 8` |
| 自定义 | 用户输入的 ByeDPI 风格参数 |

位置格式与 ByeDPI 相同：`offset[:repeats:skip][+flag]`。

- `+s` SNI 起点，`+h` HTTP Host 起点
- `+e` 末尾，`+m` 中间，`+r` 随机，`+n` 不追加
- 负数且没有 flag：从缓冲区末尾往前数

`--auto=torst,redirect,ssl_err` 会新开一组。写在 `--auto` 前面的参数是先试的那一组（可以是空的，也就是先裸发）。连接在 `timeout` 内被 RST 或没有读到任何响应时，换下一组。`torst` 是超时或重置，`redirect` 是 HTTP 重定向，`ssl_err` / `tls` 是 TLS 告警。完整的 ByeDPI 缓存和 `--auto-mode` 排序还没有做，主机到策略的记忆留在路线图。

其它已解析的开关：`--ttl`、`--def-ttl`、`--oob`、`--disoob`、`--oob-data`、`--mod-http h,d,r`、`--tlsminor`、`--udp-fake`、`--md5sig`、`--drop-sack`、`--fake-sni`、`--hosts a,b`（节点上的域名列表会再并进来）。

域名列表为空表示全部主机都去同步。`*.example.com` 匹配这个后缀和裸域。其它条目匹配自身和子域。

## 每种手法在发什么

计划器 `Plan` 不碰套接字，测试锁的是它。

**split。** `TCP_NODELAY` 打开后，按位置多次 `write`。`--split 3 --split 7` 在 30 字节上是 `[0:3)`、`[3:7)`、其余。`--split 1:3:5` 是偏移 1、6、11。内核按顺序给序号，对端重组后仍是原文。DPI 若只看单个段，就看不到完整的 SNI。

**disorder。** 与 split 的切分一样，但落在 disorder 上的那一段以 TTL=1 先发出（重复次数里偶数轮，包含默认的一次）。这段到不了服务器。服务器先收到后面的段，用 SACK 指出洞，内核再用恢复后的 TTL 重传第一段。对端看到的顺序和 README 里「先 7–30 再 1–7」一致；本机 `send` 的顺序仍是先左段（TTL 1）再右段。数据没有被改写，只是重传打乱了 DPI 的重组。

**oob / disoob。** 在切点多送 1 个带 `MSG_OOB` 的字节（默认 `a`，可用 `--oob-data`）。符合规范的栈不会把这个字节放进字节流。disoob 再叠加 TTL=1。适合插在 SNI 中间（`--oob 3+s`）。

**fake。** 这是 Linux 上 ByeDPI 的 vmsplice 做法，见 `socket_linux.go`：

1. 用匿名映射放一段与切分等长的假数据（内置模板是 ByeDPI 的 wikipedia ClientHello；HTTP 用它的 GET 模板）。
2. 把 TTL 降到 `--ttl`（默认 8）。
3. 可选地试 `TCP_MD5SIG`。失败就继续，不让连接失败。
4. `vmsplice(SPLICE_F_GIFT)` 再 `splice` 到套接字，假包先离开。
5. 等发送队列排空后，把同一页改写成真实字节。低 TTL 的那一发到不了服务器；重传读的是改写后的页，序号不变，服务器拿到原文。
6. 恢复 TTL，解除 MD5，`munmap`。

假包失败时退回直接写真实字节，连接仍然可用，只是这一跳没有假包。

**tlsrec。** 在 ClientHello 里再插入一个 5 字节记录头，总长加 5。`--tlsrec 3` 把第一条记录的载荷长度改成 3。带 `+s` 时，偏移先按绝对位置算再减掉记录头的 5 字节，和 `desync.c` 里 `part_tls` 的约定一致。拒绝切分时不会像原版那样把长度再加 5。

**mod-http。** `h` 把 `Host` 改成 `hOsT`，`d` 把主机名偶数位大写，`r` 去掉冒号后的空格并用制表符补回同样的长度。

**udp-fake。** SOCKS5 UDP ASSOCIATE 上，先发 N 个低 TTL 的 64 字节零数据报（ByeDPI 的默认 UDP 假包），再发真实数据报。用来干扰只看第一包的 QUIC 关键字检测。

**drop-sack。** 在套接字上挂 ByeDPI 那条 BPF，丢掉入站 SACK，让重传行为更接近它的预期。挂不上就忽略。

## 在 Android 上、没有 root 时什么能用

下面这些不需要 root，普通应用对自有套接字就能做：

- `TCP_NODELAY`、分多次 `write`（split）
- `IP_TTL` / `IPV6_UNICAST_HOPS`（disorder、fake、udp-fake）
- `MSG_OOB`（内核不禁止时；少数设备会直接返回错误，那时这一段退化为普通发送）
- `vmsplice` / `splice`（fake 的页赠送）
- `SO_ATTACH_FILTER`（drop-sack，失败则跳过）

下面这些做不到，也没有放进实现：

- `TCP_REPAIR`、原始套接字、packet socket。它们要 `CAP_NET_ADMIN` 或 root。假包不靠回退序号，而靠「同一序号、重传时页里已经是真数据」。
- `TCP_MD5SIG` 在未授权的 Android 上经常失败。代码会试，失败就只用 TTL。

构建标签是 `linux || android`。`GOOS=android` 不会命中单独的 `linux` 标签，所以两个都写了。非 Linux 主机上的 `ApplyTCP` 仍会做 HTTP 改写和 tlsrec，但把结果一次写完，不设置 TTL。

## 它帮不上什么

它只打乱「第一段明文里的 SNI 或 HTTP Host 被中间盒按关键字匹配」这种检测。

它不会：

- 改变目标 IP，所以拦 IP、拦路由、拦数据中心网段都没用
- 在服务器已经把连接重置、且重置发生在任何去同步之前时变出一条新路径
- 修复被主动探测封掉的 Reality / 端口
- 对已经加密、且 DPI 不看首包关键字的流量做任何事

假包和乱序依赖「低 TTL 死在 DPI 附近、重传才到服务器」。TTL 要试。设得太高，假包会到达服务器并把握手搞坏；设得太低，连运营商的检测器都看不见。预设里的 8 只是 ByeDPI 的默认值。

`--auto` 的探测会在第一组发出后等一个读。健康的 TLS 服务器通常马上回 ServerHello。只在后续组带了 detect 且设置了 timeout 时才等。等不到就换下一组并重新拨号。
