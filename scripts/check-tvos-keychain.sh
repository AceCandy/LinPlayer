#!/usr/bin/env bash
# 隔离的模拟器应用验证真实Keychain辅助函数；不用媒体账号或正式应用签名。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SIM="${1:?指定已启动的 tvOS 模拟器 UUID}"
SDK="$(xcrun --sdk appletvsimulator --show-sdk-path)"
WORK="$(mktemp -d)"
APP="$WORK/KeychainCheck.app"
RUN="$(uuidgen | tr '[:upper:]' '[:lower:]')"
ID="org.linplayer.keychaincheck.$RUN"
KEY="fixture-$RUN"
DATA=""
INSTALLED=0
mkdir -p "$APP"
run_phase() {
  local phase="$1" launch pid
  rm -f "$DATA/Documents/$phase.done"
  launch="$(SIMCTL_CHILD_LP_CHECK_NAME="$KEY" SIMCTL_CHILD_LP_CHECK_PHASE="$phase" xcrun simctl launch "$SIM" "$ID")"
  pid="${launch##*: }"
  for _ in {1..30}; do
    if [ -f "$DATA/Documents/$phase.done" ]; then return 0; fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "Keychain测试进程提前退出：$phase" >&2
      tail -n 8 "$DATA/Documents/stderr" >&2; return 1
    fi
    sleep 1
  done
  echo "Keychain检查超时：$phase" >&2; return 1
}
cleanup() {
  local result=$?
  trap - EXIT
  if [ -n "$DATA" ]; then
    xcrun simctl terminate "$SIM" "$ID" >/dev/null 2>&1 || true
    if ! run_phase cleanup; then result=1; fi
    xcrun simctl terminate "$SIM" "$ID" >/dev/null 2>&1 || true
  fi
  if [ "$INSTALLED" -eq 1 ] && ! xcrun simctl uninstall "$SIM" "$ID" >/dev/null; then
    echo "Keychain测试宿主卸载失败" >&2
    result=1
  fi
  rm -rf "$WORK"
  exit "$result"
}
trap cleanup EXIT
python3 - "$APP/Info.plist" "$ID" "$WORK/Entitlements.plist" <<'PY'
import plistlib, sys
with open(sys.argv[1], 'wb') as f:
    plistlib.dump(dict(CFBundleIdentifier=sys.argv[2], CFBundleExecutable='KeychainCheck',
        CFBundleName='KeychainCheck', CFBundlePackageType='APPL', CFBundleVersion='1',
        CFBundleShortVersionString='1.0', MinimumOSVersion='18.0', UIDeviceFamily=[3],
        LSRequiresIPhoneOS=True, UILaunchScreen={},
        UIApplicationSceneManifest=dict(UIApplicationSupportsMultipleScenes=False)), f)
with open(sys.argv[3], 'wb') as f:
    # 模拟器使用每轮独立访问组，不借用正式应用的签名身份或存储。
    plistlib.dump({'application-identifier': sys.argv[2], 'keychain-access-groups': [sys.argv[2]]}, f)
PY
xcrun clang -target arm64-apple-tvos18.0-simulator -isysroot "$SDK" -fobjc-arc -DKEYCHAIN_APP_CHECK \
  -I "$ROOT/core/statefile" "$ROOT/apps/tvos/Tests/KeychainCheck.c" "$ROOT/apps/tvos/Tests/KeychainAppCheck.m" \
  -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __entitlements -Xlinker "$WORK/Entitlements.plist" \
  -framework UIKit -framework Security -framework CoreFoundation -o "$APP/KeychainCheck"
codesign --force --sign - "$APP"
xcrun simctl install "$SIM" "$APP"
INSTALLED=1
DATA="$(xcrun simctl get_app_container "$SIM" "$ID" data)"
run_phase save
xcrun simctl terminate "$SIM" "$ID"
# 仅删除此测试宿主的可重建缓存，保留其应用身份与Keychain访问组。
rm -rf "$DATA/Library/Caches"
mkdir -p "$DATA/Library/Caches"
run_phase verify
xcrun simctl terminate "$SIM" "$ID"
run_phase missing
echo "模拟器Keychain写入/覆盖、跨进程清缓存读取、删除后新进程缺失检查通过。"
