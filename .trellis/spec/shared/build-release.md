# 构建与交付

## 工具链

- 工具链与缓存放 `.toolchain/`，Go 使用 `GOTOOLCHAIN=local`；`scripts/env.sh` 配置项目缓存，存在本地 Go / zig 时才覆盖编译器路径。
- `scripts/fetch-toolchain.sh` 当前下载 Windows 工具链；不能把它描述为 Linux / Android SDK 的通用安装器。Linux 环境按已有 CI / 本地工具链配置，Android cgo 使用 NDK clang。
- 新环境核查 `scripts/check-toolchain.sh`；Go `c-shared` 必须实际经过 cgo，MSVC 不能代替所需 C 编译器。
- 远程 Apple 签名预检必须使用将实际执行构建的 SSH 连接：先查 `security find-identity -v -p codesigning`，再对临时可执行文件执行 `codesign --sign <identity> --timestamp=none <temporary-executable>` 与 `codesign --verify --strict <temporary-executable>`。证书有效或用户在本地解锁，不代表新 SSH 连接可访问私钥；`errSecInternalComponent` 时应对比钥匙串访问与连接上下文，避免盲目重配对或批量修改私钥 ACL。一次连接成功不能外推所有连接。
- `errSecInternalComponent` 不是“钥匙串没解锁”的唯一证据。先检查登录钥匙串是否在搜索列表、证书是否在该钥匙串，并显式指定钥匙串做临时签名；若 `security show-keychain-info` 返回 `User interaction is not allowed`，记录 SSH 会话交互受限。用户 GUI 解锁后仍失败时，不重复同一建议；让用户在其终端交互执行 `security unlock-keychain`，密码不经聊天或持久日志，再实际验证。未经确认不要批量改私钥 ACL。
- Apple 签名的 Team ID 从匹配应用、有效期与当前证书的描述文件 `TeamIdentifier` 核实；不能从开发证书显示名称的括号后缀推断。名称后缀可能不是签名团队，错用会报 No Account / No profiles，即使私钥预检已通过。真实标识仅运行时传递，不入仓。
- Android 需要 JDK、SDK / NDK；项目目录已有工具时显式设置 `JAVA_HOME`、`PATH`、`ANDROID_HOME`、`GRADLE_USER_HOME`，不把本机绝对路径写进仓库。
- 版本来自根 `VERSION`；脚本支持的 `LP_VERSION` 覆盖遵循发行流程，不另造版本权威。

## Android 手机快速出包（Linux）

适用于本地工具链及签名材料已就绪、用户只要求打手机包的场景。从仓库根目录执行；其它机器先核实实际工具目录，不照搬不存在的路径。

```bash
(
  set -e
  export JAVA_HOME="$PWD/.toolchain/jdk-21.0.2"
  export ANDROID_HOME="$PWD/.toolchain/android-sdk"
  export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
  export GRADLE_USER_HOME="$PWD/.toolchain/gradle"
  export PATH="$JAVA_HOME/bin:$PATH"

  test -x "$JAVA_HOME/bin/java"
  test -x "$JAVA_HOME/bin/jlink"
  test -x "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"
  test -f apps/android/keystore.properties
  java -version
  (cd apps/android && ./gradlew --version)

  time bash scripts/pack-android.sh arm64-v8a
)
```

- **先确认环境再编译**：预检失败先补齐工具或签名材料；核对 Java 与 Gradle 输出使用项目 JDK 21，不让系统默认 JDK 抢先。签名与编译期凭据仍按本页验收要求，不打印配置内容。
- **复用缓存**：固定 `GRADLE_USER_HOME` 为已有的 `.toolchain/gradle`，保留 Go / Gradle 增量缓存；普通出包不运行 `clean`、不清缓存、不强制刷新依赖或重跑全部任务。Wrapper 版本以仓库配置为准，缓存缺失仍可能下载，不为提速换 Gradle 版本。
- **只调用一次完整入口**：`pack-android.sh` 已包含 libmpv 准备、Go 核心构建和 `assembleRelease`，不要先逐项构建再调用它。只出手机 arm64，不顺带出 TV / 模拟器包，不绕过原生库、形态、签名结构和体积门禁。
- **失败按阶段定位**：若只看到“核心层编不出来”，在相同环境运行 `bash scripts/build-core-android.sh arm64-v8a` 查看真实错误；若是 `JdkImageTransform` / `jlink` 错误，先核对 JDK，不先删 SDK 或缓存。只交付 `build/android/app-arm64-v8a-release.apk`，不把同目录旧 TV 包当成本轮产物。

2026-10-25 实测：系统默认 JDK 22 在 `jlink` 阶段失败，Gradle 报耗时约 3 分 15 秒；切换项目 JDK 21 后约 3 分钟出包成功。失败轮还下载了 Gradle 9.7.1，未使用项目 Gradle 缓存。后续固定上述环境以避免相同失败和重复下载；这不是耗时承诺，也未采集 Kotlin / R8 等任务级耗时，不能据此认定内部瓶颈。

## 最终交付路径

| 平台 | 默认入口 | 最终目录 / 文件 |
|---|---|---|
| Windows | `bash scripts/pack-win.sh` | `build/pack/LinPlayer-Windows-v${LP_VERSION}.zip` |
| Linux | `bash scripts/pack-linux.sh` | `build/pack-linux/LinPlayer-Linux-v${LP_VERSION}.zip` |
| Android 手机 | `bash scripts/pack-android.sh` | `build/android/app-arm64-v8a-release.apk` |
| Android TV | `bash scripts/pack-android.sh tv` | `build/android/app-tv-armeabi-v7a-release.apk` |
| tvOS（开发中） | 用户直接要求后 `bash scripts/pack-tvos.sh` | `build/tvos/LinPlayer-tvOS-v${VERSION}.app`（开发签名；未发布） |

TV可用 `bash scripts/pack-android.sh tv arm64-v8a` 显式出64位测试包，产物为 `build/android/app-tv-arm64-v8a-release.apk`。TV参数决定界面形态、ABI参数决定CPU架构，二者不能互相替代；出包必须校验Manifest中的TV_BUILD标记，禁止复制手机包改名当TV包。

Windows / Linux 脚本支持显式输出目录，但默认交付遵守本表。APK ABI 与 TV 文件名是更新器契约，不擅自改名。

`build/core/`、桌面 `bin/obj`、`apps/android/app/build/outputs/apk/` 是中间位置。
**对用户提供安装包一律链接最终交付目录。** 即使直接编译，也必须归档本次对应 ABI 的产物，核对文件一致性并重新验签；不把目录里其它端的旧包当成本次新包。

## 必要检查

| 改动 | 检查 |
|---|---|
| Go 核心 | `bash scripts/check-core.sh`：vet、无缓存测试、出库、FFI 头文件、C# 宿主契约与差分对账 |
| 命令表 / 绑定 | `bash scripts/check-bindings.sh`：生成产物、C#/Kotlin 编译、命令表/Go/C#/Kotlin/Swift 对账 |
| 桌面代码 | 对应平台 pack；风格检查 `bash scripts/check-style.sh` |
| Android 命令调用 | `python3 scripts/check-android-args.py`，另运行相关 Gradle 回归 |
| CI / 构建配置 | `bash scripts/check-workflows.sh`：shell 语法、审查门禁与编译期凭据接线 |
| 新增提交内容 | `bash scripts/check-secrets.sh` / `--staged` |
| UI / 播放链路 | 对应平台真实渲染、焦点/按键与设备播放验证；按 [外壳质量规范](../frontend/quality-guidelines.md) 区分层级 |

脚本依赖 `python` 的环境应先配置可用解释器；本机只有 `python3` 时可调用同一 Python 检查入口，不修改门禁判据、不跳过检查。

## 出包验收

- Android 原生库使用 `useLegacyPackaging=true` 压缩入 APK，安装时由系统解压；验收压缩方法及最终 Manifest 的原生库提取配置，不能把下载体积等同安装占用。
- Android release 使用实际 signingConfig；校验 APK 签名、ABI / ELF、播放库是否真实包含，R8 不得裁掉 JNI 入口。`pack-android.sh` 检查 v1 证书、v2/v3 签名块和体积上限：arm64手机64MiB（界面使用系统字体），其它ABI60MiB。
- Windows 绿色包自包含，核心库 / 播放库在 exe 同目录；验证壳 `version` 与本次版本相符。
- Linux 只能在 Linux 出包；核心库不直接链接 libmpv 的 DT_NEEDED，系统运行时提供 libmpv。脚本检查图标、版本及能力命令冒烟。
- 桌面打包前删除暂存 `userdata/` 和开发头文件；测试账号、用户日志或运行数据不得进入包。
- 编译期凭据按 [构建接线正本](../../../docs/go-migration/BUILD-SECRETS.md) 注入；存在文件不代表已接线，验证实际产物与工作流。
- 构建、签名和渲染通过不代表真实设备验收通过，交付说明必须列出未覆盖项。

依据：[Windows](../../../scripts/pack-win.sh)、[Linux](../../../scripts/pack-linux.sh)、[Android](../../../scripts/pack-android.sh)、[版本规范](../../../docs/VERSIONING.md)、[构建故障记录](../../../docs/lessons/build-release.md)。

## tvOS 开发检查

固定输入由 `scripts/fetch-tvos.py` 准备，依赖和缓存处于 `.toolchain/tvos/`。Apple SDK 主机先执行 `build-core-tvos.sh` 生成完整核心及真实 C 头文件，再执行 `check-tvos-ui.sh` 编译依赖模块、整页面类型检查和 Swift 回归；这两个检查入口不生成 .app。

出包需运行时提供 `LP_APPLE_TEAM` / `LP_APPLE_SIGN_IDENTITY`；`pack-tvos.sh` 在同一连接预检私钥、构建、严格验签、检查 28 静态输入的符号隔离及 9 个 Aether 动态框架闭包后归档，不安装。早期最小版已完成开发签名、严格验签与更新安装；本次产品对齐版在用户解锁后也已完成开发签名、严格验签、更新安装和启动命令检查。正式应用的完整 Keychain / 遥控 / 播放验收及第三方发行许可仍未通过，不能把类型检查作为交付完成。详见 [tvOS 开发入口](../../../apps/tvos/README.md)。
