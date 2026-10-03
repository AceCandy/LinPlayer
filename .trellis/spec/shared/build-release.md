# 构建与交付

## 工具链

- 工具链与缓存放 `.toolchain/`，Go 使用 `GOTOOLCHAIN=local`；`scripts/env.sh` 配置项目缓存，存在本地 Go / zig 时才覆盖编译器路径。
- `scripts/fetch-toolchain.sh` 当前下载 Windows 工具链；不能把它描述为 Linux / Android SDK 的通用安装器。Linux 环境按已有 CI / 本地工具链配置，Android cgo 使用 NDK clang。
- 新环境核查 `scripts/check-toolchain.sh`；Go `c-shared` 必须实际经过 cgo，MSVC 不能代替所需 C 编译器。
- Android 需要 JDK、SDK / NDK；项目目录已有工具时显式设置 `JAVA_HOME`、`PATH`、`ANDROID_HOME`、`GRADLE_USER_HOME`，不把本机绝对路径写进仓库。
- 版本来自根 `VERSION`；脚本支持的 `LP_VERSION` 覆盖遵循发行流程，不另造版本权威。

## 最终交付路径

| 平台 | 默认入口 | 最终目录 / 文件 |
|---|---|---|
| Windows | `bash scripts/pack-win.sh` | `build/pack/LinPlayer-Windows-v${LP_VERSION}.zip` |
| Linux | `bash scripts/pack-linux.sh` | `build/pack-linux/LinPlayer-Linux-v${LP_VERSION}.zip` |
| Android 手机 | `bash scripts/pack-android.sh` | `build/android/app-arm64-v8a-release.apk` |
| Android TV | `bash scripts/pack-android.sh tv` | `build/android/app-tv-armeabi-v7a-release.apk` |

Windows / Linux 脚本支持显式输出目录，但默认交付遵守本表。APK ABI 与 TV 文件名是更新器契约，不擅自改名。

`build/core/`、桌面 `bin/obj`、`apps/android/app/build/outputs/apk/` 是中间位置。
**对用户提供安装包一律链接最终交付目录。** 即使直接编译，也必须归档本次对应 ABI 的产物，核对文件一致性并重新验签；不把目录里其它端的旧包当成本次新包。

## 必要检查

| 改动 | 检查 |
|---|---|
| Go 核心 | `bash scripts/check-core.sh`：vet、无缓存测试、出库、FFI 头文件、C# 宿主契约与差分对账 |
| 命令表 / 绑定 | `bash scripts/check-bindings.sh`：生成产物、双端编译、命令集合四方对账 |
| 桌面代码 | 对应平台 pack；风格检查 `bash scripts/check-style.sh` |
| Android 命令调用 | `python3 scripts/check-android-args.py`，另运行相关 Gradle 回归 |
| CI / 构建配置 | `bash scripts/check-workflows.sh`：shell 语法、审查门禁与编译期凭据接线 |
| 新增提交内容 | `bash scripts/check-secrets.sh` / `--staged` |
| UI / 播放链路 | 对应平台真实渲染、焦点/按键与设备播放验证；按 [外壳质量规范](../frontend/quality-guidelines.md) 区分层级 |

脚本依赖 `python` 的环境应先配置可用解释器；本机只有 `python3` 时可调用同一 Python 检查入口，不修改门禁判据、不跳过检查。

## 出包验收

- Android release 使用实际 signingConfig；校验 APK 签名、ABI / ELF、播放库是否真实包含，R8 不得裁掉 JNI 入口。`pack-android.sh` 检查 v1 证书、v2/v3 签名块和整数 MiB 体积上限 60。
- Windows 绿色包自包含，核心库 / 播放库在 exe 同目录；验证壳 `version` 与本次版本相符。
- Linux 只能在 Linux 出包；核心库不直接链接 libmpv 的 DT_NEEDED，系统运行时提供 libmpv。脚本检查图标、版本及能力命令冒烟。
- 桌面打包前删除暂存 `userdata/` 和开发头文件；测试账号、用户日志或运行数据不得进入包。
- 编译期凭据按 [构建接线正本](../../../docs/go-migration/BUILD-SECRETS.md) 注入；存在文件不代表已接线，验证实际产物与工作流。
- 构建、签名和渲染通过不代表真实设备验收通过，交付说明必须列出未覆盖项。

依据：[Windows](../../../scripts/pack-win.sh)、[Linux](../../../scripts/pack-linux.sh)、[Android](../../../scripts/pack-android.sh)、[版本规范](../../../docs/VERSIONING.md)、[构建故障记录](../../../docs/lessons/build-release.md)。
