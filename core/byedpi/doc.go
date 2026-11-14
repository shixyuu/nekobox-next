// SPDX-License-Identifier: GPL-3.0-or-later
//
// Package byedpi is a Go reimplementation of the DPI-desync techniques in
// ByeDPI (https://github.com/hufrea/byedpi, MIT, Copyright (c) 2024 hufrea).
// The algorithms follow ByeDPI 0.17.3. NekoBOX NEXT, Copyright (C) 2026 历史.
//
// The engine runs entirely on the device. sing-box reaches it through a
// loopback SOCKS5 outbound with random credentials, and every dialled socket
// is passed to VpnService.protect so it bypasses the TUN.
package byedpi
