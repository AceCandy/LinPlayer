#!/usr/bin/env bash
# 临时模拟器测试宿主：正式浏览页面 + 合成核心/图片，不链接播放引擎或生成正式交付包。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SIM="${1:?指定已启动的 tvOS 模拟器 UUID}"
WORK="$ROOT/.toolchain/tvos-visual-check"
APP="$WORK/VisualCheck.app"
ID=org.linplayer.visualcheck
LOG="$WORK/runtime-stderr.log"
OUT="$ROOT/build/visual-alignment/tvos"
mkdir -p "$APP" "$OUT"
python3 "$ROOT/scripts/fetch-tvos.py" --verify
LOADING="$ROOT/.toolchain/tvos-loading-simulator"
python3 "$ROOT/scripts/build-tvos-loading-check.py" appletvsimulator "$LOADING"
cp -R "$ROOT/.toolchain/tvos/Loading" "$APP/"
mkdir -p "$APP/VisualFixtures"
cp "$ROOT/tools/visual-fixtures/catalog.json" "$ROOT"/tools/visual-fixtures/*.png "$APP/VisualFixtures/"
SDK="$(xcrun --sdk appletvsimulator --show-sdk-path)"
xcrun swiftc -parse-as-library -D UI_RENDER_CHECK -swift-version 5 \
  -target arm64-apple-tvos18.0-simulator -sdk "$SDK" \
  "$ROOT"/apps/tvos/Sources/{AppModel,MediaAPI,ImageLoader,BrowseViews,ProductViews,PlayerView}.swift \
  "$ROOT"/bindings/swift/*.swift "$ROOT/apps/tvos/Tests/VisualCheck.swift" \
  -I "$LOADING" "$LOADING/libLottie.a" -o "$APP/VisualCheck"
python3 - "$APP/Info.plist" <<'PY'
import plistlib, sys
with open(sys.argv[1], 'wb') as f:
    plistlib.dump(dict(CFBundleIdentifier='org.linplayer.visualcheck', CFBundleExecutable='VisualCheck',
        CFBundleName='VisualCheck', CFBundlePackageType='APPL', CFBundleVersion='1',
        CFBundleShortVersionString='1.0', MinimumOSVersion='18.0', UIDeviceFamily=[3],
        LSRequiresIPhoneOS=True, UILaunchScreen={},
        UIApplicationSceneManifest=dict(UIApplicationSupportsMultipleScenes=False)), f)
PY
codesign --force --sign - "$APP"
cleanup() {
  xcrun simctl terminate "$SIM" "$ID" >/dev/null 2>&1 || true
  xcrun simctl uninstall "$SIM" "$ID" >/dev/null 2>&1 || true
  rm -f "$LOG"
}
trap cleanup EXIT
xcrun simctl install "$SIM" "$APP"
DATA="$(xcrun simctl get_app_container "$SIM" "$ID" data)"
rm -f "$DATA/Documents/done"
LAUNCH="$(xcrun simctl launch --stderr="$LOG" "$SIM" "$ID")"
PID="${LAUNCH##*: }"
echo "$LAUNCH"
for _ in {1..180}; do
  if [ -f "$DATA/Documents/done" ]; then
    cp "$DATA"/Documents/*.png "$OUT/"
    echo "原生页面图：${OUT}（合成数据，不替代真机遥控与播放验收）"
    exit 0
  fi
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "测试宿主提前退出，检查断言或运行时错误。" >&2
    tail -n 20 "$LOG" >&2
    exit 1
  fi
  sleep 1
done
echo "页面渲染超时" >&2
tail -n 20 "$LOG" >&2
exit 1
