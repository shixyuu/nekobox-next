#!/usr/bin/env bash
# Build the official sing-box libbox plus the in-repo ByeDPI engine into an Android AAR.
# Pins: sing-box v1.14.4, Go 1.26.0, gomobile v0.0.0-20261011034528-197770444867.
# sing-box 1.14.4's own go line is 1.25.5. This gomobile revision declares go 1.26.0
# and `gomobile bind` refuses to run unless golang.org/x/mobile is in the module
# graph, which a Go 1.25 toolchain with GOTOOLCHAIN=local will not load.
set -euo pipefail

SING_BOX_VERSION=v1.14.4
GO_VERSION=1.26.0
GOMOBILE_VERSION=v0.0.0-20261011034528-197770444867
ANDROID_API=26
# Tags that back a feature this app actually generates or shows in the UI:
# gVisor TUN stack, Hysteria/TUIC QUIC, WireGuard, uTLS fingerprints, Clash API.
# badlinkname and tfogo_checklinkname0 keep the Android pidfd/TFO linknames working.
# Not included, because nothing in the UI or the config generator uses them:
# with_tailscale, with_acme, with_dhcp, with_cloudflared, with_ccm, with_ocm.
BUILD_TAGS="with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0"
# Phone preview is arm64. The emulator retest is x86_64. Set NEKOCORE_TARGETS=android for all four ABIs.
TARGETS="${NEKOCORE_TARGETS:-android/arm64,android/amd64}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/libs/nekocore.aar"

if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]]; then
  echo "ANDROID_HOME is not set. Install the Android SDK and NDK first." >&2
  exit 1
fi
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"

go_is_new_enough() {
  command -v go >/dev/null 2>&1 || return 1
  local have
  have="$(go env GOVERSION | sed 's/^go//')"
  # The pinned gomobile module requires Go 1.26.0. sing-box 1.14.4 allows 1.25.5 or newer.
  python3 - <<PY
have = tuple(int(x) for x in "$have".split(".")[:3])
need = tuple(int(x) for x in "$GO_VERSION".split("."))
raise SystemExit(0 if have >= need else 1)
PY
}

if ! go_is_new_enough; then
  echo "Installing Go ${GO_VERSION} (current toolchain is too old for gomobile ${GOMOBILE_VERSION})."
  tmp="$(mktemp -d)"
  curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" -o "$tmp/go.tgz"
  rm -rf "$HOME/sdk/go${GO_VERSION}"
  mkdir -p "$HOME/sdk"
  tar -C "$HOME/sdk" -xzf "$tmp/go.tgz"
  mv "$HOME/sdk/go" "$HOME/sdk/go${GO_VERSION}"
  export PATH="$HOME/sdk/go${GO_VERSION}/bin:$PATH"
fi

export GOTOOLCHAIN=local
export GOPROXY="${GOPROXY:-https://proxy.golang.org,direct}"

cd "$ROOT/core/mobile"
go mod download

export PATH="$(go env GOPATH)/bin:$PATH"
# `gomobile bind` loads golang.org/x/mobile/bind from this module. A tool
# directive keeps that module in the graph across `go mod tidy`.
go get -tool "golang.org/x/mobile/cmd/gobind@${GOMOBILE_VERSION}"
go install "golang.org/x/mobile/cmd/gomobile@${GOMOBILE_VERSION}"
go install "golang.org/x/mobile/cmd/gobind@${GOMOBILE_VERSION}"
gomobile init

mkdir -p "$ROOT/app/libs"
# gomobile bind compiles every ABI at once. Each c-shared link of sing-box
# (gVisor included) is large enough that four of them together get OOM-killed
# on a 16 GB machine. Serialize only those links.
real_go="$(command -v go)"
wrap="$(mktemp -d)"
cat > "$wrap/go" <<EOF
#!/usr/bin/env bash
if [[ " \$* " == *" -buildmode=c-shared "* ]]; then
  exec flock "$wrap/cshared.lock" "$real_go" "\$@"
else
  exec "$real_go" "\$@"
fi
EOF
chmod +x "$wrap/go"
export PATH="$wrap:$PATH"

# Same linker flags as sing-box cmd/internal/build_shared.LinkerFlags.
# -checklinkname=0 is required: libbox linknames os.checkPidfdOnce.
gomobile bind \
  -target="$TARGETS" \
  -androidapi "$ANDROID_API" \
  -javapkg=io.nekobox.next.corelib \
  -trimpath \
  -ldflags "-s -w -buildid= -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=${SING_BOX_VERSION} -X runtime.godebugDefault=multipathtcp=0,tlssha1=1" \
  -o "$OUT" \
  -tags "$BUILD_TAGS" \
  .

echo "Wrote $OUT"
echo "sing-box ${SING_BOX_VERSION} is required by core/mobile/go.mod (no replace directives outside this repo)."
