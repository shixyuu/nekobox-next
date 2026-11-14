#!/usr/bin/env bash
# Build the official sing-box libbox plus the in-repo ByeDPI engine into an Android AAR.
# Pins: sing-box v1.14.4, Go 1.25.5, gomobile v0.0.0-20261011034528-197770444867.
set -euo pipefail

SING_BOX_VERSION=v1.14.4
GO_VERSION=1.25.5
GOMOBILE_VERSION=v0.0.0-20261011034528-197770444867
ANDROID_API=26
# Tags from sing-box release/DEFAULT_BUILD_TAGS_OTHERS, without with_usbip
# (that tag pulls platform code we do not ship on Android).
BUILD_TAGS="with_gvisor,with_quic,with_dhcp,with_wireguard,with_utls,with_acme,with_clash_api,with_tailscale,with_ccm,with_ocm,with_cloudflared,badlinkname,tfogo_checklinkname0"

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
  # sing-box 1.14.4 declares go 1.25.5 in its go.mod.
  python3 - <<PY
have = tuple(int(x) for x in "$have".split(".")[:3])
need = tuple(int(x) for x in "$GO_VERSION".split("."))
raise SystemExit(0 if have >= need else 1)
PY
}

if ! go_is_new_enough; then
  echo "Installing Go ${GO_VERSION} (current toolchain is too old for sing-box ${SING_BOX_VERSION})."
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
go install "golang.org/x/mobile/cmd/gomobile@${GOMOBILE_VERSION}"
go install "golang.org/x/mobile/cmd/gobind@${GOMOBILE_VERSION}"
gomobile init

mkdir -p "$ROOT/app/libs"
gomobile bind \
  -target=android \
  -androidapi "$ANDROID_API" \
  -javapkg=io.nekobox.next.native \
  -o "$OUT" \
  -tags "$BUILD_TAGS" \
  .

echo "Wrote $OUT"
echo "sing-box ${SING_BOX_VERSION} is required by core/mobile/go.mod (no replace directives outside this repo)."
