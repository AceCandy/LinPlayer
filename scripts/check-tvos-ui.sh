#!/usr/bin/env bash
# 依赖模块编译 + 正式页面类型检查；不链接、生成或签名 .app。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$ROOT/scripts/check-tvos-speed-controls.py"
CACHE="$ROOT/.toolchain/tvos"
python3 "$ROOT/scripts/fetch-tvos.py" --verify
python3 "$ROOT/scripts/test-tvos-loading-check.py"
[ -f "$ROOT/build/tvos/core/lpcore.h" ] || { echo "先执行 build-core-tvos.sh 生成真实头文件。"; exit 1; }
SDK="$(xcrun --sdk appletvos --show-sdk-path)"
LOADING="$ROOT/.toolchain/tvos-loading-check"
python3 "$ROOT/scripts/build-tvos-loading-check.py" appletvos "$LOADING"
SCRATCH="$ROOT/.toolchain/tvos-swift-check"
xcrun swift build --package-path "$CACHE/Dependencies/AetherEngine" --scratch-path "$SCRATCH" \
  --cache-path "$ROOT/.toolchain/tvos-swift-cache" --triple arm64-apple-tvos18.0 --sdk "$SDK" --target AetherEngine
# 当前 SwiftPM 在 Apple 平台使用 Xcode 构建系统，debug 链接指向平台产品目录。
MODULES="$SCRATCH/debug"
xcrun swiftc -typecheck -warnings-as-errors -swift-version 5 -target arm64-apple-tvos18.0 -sdk "$SDK" \
  -I "$MODULES" -F "$MODULES" -I "$LOADING" -I "$MODULES/include" -I "$ROOT/build/tvos/core" \
  -import-objc-header "$ROOT/apps/tvos/Config/Bridge.h" \
  "$ROOT"/apps/tvos/Sources/*.swift "$ROOT"/bindings/swift/*.swift
bash "$ROOT/scripts/check-tvos.sh"
