#!/usr/bin/env bash
# 仅在用户明确要求出包后执行。签名身份与 Team 通过运行环境传入，不入仓。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${LP_APPLE_TEAM:?请在当前连接中指定 LP_APPLE_TEAM}"
: "${LP_APPLE_SIGN_IDENTITY:?请在当前连接中指定 LP_APPLE_SIGN_IDENTITY}"
VERSION="$(tr -d '\r\n' < "$ROOT/VERSION")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
# 同一连接真实调用私钥，不能只凭证书列表或另一终端解锁判断。
printf 'int main(void) { return 0; }\n' > "$TMP/sign-check.c"
xcrun clang "$TMP/sign-check.c" -o "$TMP/sign-check"
security find-identity -v -p codesigning >/dev/null
codesign --sign "$LP_APPLE_SIGN_IDENTITY" --timestamp=none "$TMP/sign-check"
codesign --verify --strict "$TMP/sign-check"
bash "$ROOT/scripts/build-core-tvos.sh"
xcodebuild -project "$ROOT/apps/tvos/LinPlayer.xcodeproj" -scheme LinPlayer \
  -configuration Release -destination generic/platform=tvOS -derivedDataPath "$ROOT/build/tvos/derived" \
  DEVELOPMENT_TEAM="$LP_APPLE_TEAM" CODE_SIGN_IDENTITY="Apple Development" \
  -allowProvisioningUpdates build > "$TMP/build.log" 2>&1 || {
    echo "构建失败；未归档。日志可能包含签名与本地路径，未输出。" >&2; exit 1;
  }
APP="$ROOT/build/tvos/derived/Build/Products/Release-appletvos/LinPlayer.app"
codesign --verify --deep --strict "$APP"
python3 "$ROOT/scripts/check-tvos-symbols.py" "$APP/LinPlayer"
python3 - "$APP" <<'PY'
from pathlib import Path
import sys
app = Path(sys.argv[1])
expected = {'AetherLib' + name + '.framework' for name in
            ['avcodec', 'avformat', 'avutil', 'swresample', 'swscale', 'dav1d', 'avfilter', 'zimg', 'zvbi']}
actual = {p.name for p in (app / 'Frameworks').glob('*.framework')}
if actual != expected: raise SystemExit('动态框架闭包不符，禁止交付。')
if not (app / 'Notices/dependencies.lock.json').is_file(): raise SystemExit('第三方声明资源缺失，禁止交付。')
PY
OUT="$ROOT/build/tvos/LinPlayer-tvOS-v$VERSION.app"
rm -rf "$OUT"
ditto "$APP" "$OUT"
codesign --verify --deep --strict "$OUT"
echo "签名应用已归档至 build/tvos/LinPlayer-tvOS-v$VERSION.app；未安装。"
