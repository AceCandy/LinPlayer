#!/usr/bin/env bash
# 无需签名、无需媒体账号；只编译运行测试可执行文件，不生成 .app。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$ROOT/scripts/gen-bindings.py" --check
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
xcrun swiftc -swift-version 5 -parse-as-library \
  "$ROOT/bindings/swift/JSONValue.swift" "$ROOT/bindings/swift/CoreClient.swift" \
  "$ROOT/bindings/swift/Commands.g.swift" "$ROOT/apps/tvos/Sources/MediaAPI.swift" \
  "$ROOT/apps/tvos/Tests/CoreClientCheck.swift" -o "$TMP/corecheck"
"$TMP/corecheck"
