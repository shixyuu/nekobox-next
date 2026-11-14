# Third-party notices

## ByeDPI

https://github.com/hufrea/byedpi

MIT License

Copyright (c) 2024 hufrea

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

`core/byedpi` is a Go reimplementation of the desync behaviour described by
ByeDPI 0.17.3. The built-in fake TLS ClientHello and the fake HTTP request in
`core/byedpi/fake.go` are the templates from that project (`tls_data` and
`http_data` in `packets.c`). The C sources are not compiled into the app.

## sing-box

https://github.com/SagerNet/sing-box

`core/mobile` links the unmodified module `github.com/sagernet/sing-box`
v1.14.4 (GPL-3.0) through `experimental/libbox`. No `replace` directive points
outside this repository.

## NekoBoxForAndroid

https://github.com/MatsuriDayo/NekoBoxForAndroid

GPL-3.0. NekoBOX NEXT is a rewrite. It does not copy that project's source.

## AndroidX, Material, Kotlin

Apache License 2.0. Compose Material 3 Expressive components
(`androidx.compose.material3:material3` 1.5.0-rc01) and
`androidx.graphics:graphics-shapes` supply the shapes used by the connect
button and protocol avatars.

## SnakeYAML

Apache License 2.0. Subscription import uses `SafeConstructor` so YAML tags
cannot construct arbitrary Java types.
