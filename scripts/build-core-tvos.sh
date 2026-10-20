#!/usr/bin/env bash
# 仅在 Apple SDK 主机显式执行：完整核心 c-archive，不生成应用。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"
CACHE="${LP_TVOS_CACHE:-$ROOT/.toolchain/tvos}"
OUT="$ROOT/build/tvos/core"
python3 "$ROOT/scripts/fetch-tvos.py" --cache "$CACHE" --verify
mkdir -p "$OUT"
SDK="$(xcrun --sdk appletvos --show-sdk-path)"
WRAPPER="$(mktemp)"
trap 'rm -f "$WRAPPER"' EXIT
printf '#!/bin/bash\nexec xcrun clang -target arm64-apple-tvos18.0 -isysroot %q "$@"\n' "$SDK" > "$WRAPPER"
chmod +x "$WRAPPER"
GOOS=ios GOARCH=arm64 CGO_ENABLED=1 CC="$WRAPPER" GOTOOLCHAIN=local \
  go -C "$ROOT/core" build -mod=readonly -tags tvos -buildmode=c-archive -o "$OUT/lpcore.a" ./ffi
python3 "$ROOT/scripts/check-ffi-contract.py" "$OUT/lpcore.h"
python3 "$ROOT/scripts/tvos-link-config.py" "$CACHE" "$ROOT/build/tvos/Native.xcconfig"
