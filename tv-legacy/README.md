# LinPlayer TV Legacy (Android 4.4)

Standalone Android (Java + XML/View) project intended for **API 19 (Android 4.4)** TV/box devices.

Goals:
- Per-app proxy only (no `VpnService`): proxy listens on `127.0.0.1` and only this app routes through it.
- Unified `User-Agent`: `LinPlayer/<versionName>` for app HTTP + playback requests.
- Keep mihomo's own UA unchanged (default mihomo/clash.meta UA).

## Open & Build

Open this folder (`tv-legacy/`) in Android Studio.

## Docs

- API / interfaces: `tv-legacy/API.md`

Notes:
- `local.properties` is intentionally not committed; Android Studio will generate it.
- Place an `armeabi-v7a` mihomo binary at:
  - `app/src/main/jniLibs/armeabi-v7a/libmihomo.so`
- mihomo config file is generated at runtime:
  - `<app filesDir>/mihomo/config.yaml`
