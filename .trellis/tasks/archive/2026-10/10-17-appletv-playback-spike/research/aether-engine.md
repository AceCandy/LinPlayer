# AetherEngine 初步评估

## 范围

用户询问 tvOS 能否接入 AetherEngine。先核查公开源码、接口和历史工具链兼容性；用户升级系统后，使用固定 7.39.0 默认组件构建独立真机探针。没有替换产品播放器，也未接入 Go ABI；各阶段结果见下文。

## 已核实

- 对应项目为 superuser404notfound/AetherEngine，提供 Swift 播放引擎及 UIKit / SwiftUI 播放表面。
- Package.swift 声明最低 tvOS 18，swift-tools-version 为 6.1。README 的测试工具链为 Swift 6.4 / Xcode 27，明确较旧工具链未测试，源码至少需要 26.2 SDK。升级前的 Xcode 16.2 / tvOS 18.2 SDK 不能直接构建该主线。包的 macOS 15 deployment target 是 macOS 应用运行要求，不能把它当作构建 tvOS 应用的宿主系统要求。
- README 提到支持旧部署目标的项目可固定 6.x；这不等于 6.x 兼容当前 Xcode。后续历史 tag 静态核查见下文，没有找到可直接使用的兼容发布版本；未实际编译或验收二进制依赖。
- 公开接口包括 URL 与 HTTP headers 加载、初始位置、seek、进度、字幕轨道与字幕 cue。字幕 cue 由宿主绘制。架构上可评估由 Go 核心提供服务端播放信息、Swift 宿主接管播放；尚未验证实际命令、事件与进度上报衔接。
- 主路径通过 FFmpeg 解复用、HLS-fMP4 与进程内回环 HTTP 服务交给 AVPlayer / AVPlayerLayer；软件 dav1d fallback 使用 AVSampleBufferDisplayLayer。它不依赖本轮出问题的 libmpv / GLKit 渲染路线，但不能由此承诺颜色、HDR 或杜比视界全部正确。
- 项目采用 LGPL-3.0 加 Apple Store / DRM Exception；该例外不覆盖依赖。FFmpeg 动态框架及其它依赖须分别遵守授权。正式集成前固定版本再核查。

## 判断

具备接入 tvOS 宿主播放器的公开接口，值得做独立对照。没有证据将其评为“最好”。历史版本核查没有找到旧 Xcode 16.2 可直接使用的发布版本；保留旧环境则需要单独做源码与依赖兼容适配，不能声称仅固定旧版即可。现有 videotoolbox-copy 已获得同片段人眼无偏色反馈，可保留为对照基线。

## 历史版本核查（2026-10-18）

- 只读扫描 443 个数字版本 tag，最早为 1.0.0，commit `a7fabcf0973099d1e9488c2baa97926082e9d827`，最新核查版本为 7.39.0。
- 1.0.0 的 `Sources/AetherEngine/Video/VTCapabilityProbe.swift:22-23`、`:57-58` 已调用 `VTRegisterSupplementalVideoDecoderIfAvailable`，分别注册 VP9、AV1。虽然在 `#available` 分支内，但这仅限制运行时执行，不会让旧 SDK 的编译器认识缺失的符号。主代理直接复核该固定 tag 文件确认。
- 因此最早发布版也存在当前 tvOS 18.2 SDK 无法直接编译的源码；未发现满足“不改源码、直接使用”的发布 tag。1.0.0 的 Swift tools 6.0 与 tvOS 16 声明不能改变此结论。
- 1.0.0 的 Package.resolved 固定 FFmpegBuild revision `75600a51a02d122a89aea612809dcbdadb6d16cf`，依赖也声明 Swift tools 6.0；这只是 manifest 读取兼容证据，不证明依赖二进制能链接运行。
- 本轮为静态核查，没有构建、修改源码、下载二进制或实机验证。适配旧 SDK 需要单独验证其它新 API、Swift 语法、依赖与播放回退，不能承诺删除两次调用就能完整运行。

## 旧版与新版能力差异

以下是版本演进对比，不是当前工具链可用版本推荐：

- 1.x 已有原生 AVPlayer 与软件解码路径、HDR10/HDR10+/HLG、杜比视界 P5/P8.x、文本与位图字幕；音频包含可复制流与桥接路径。基础格式能力并非只有新版才有。
- 2.x / 3.x 增强 HDR/DV 输出路由、拖动、直播与 DVR，并修复大文件字幕预读导致的内存增长等稳定性问题。
- 5.x 重做内嵌字幕的包缓存和按播放位置解码，改善拖动后的字幕恢复，减少额外连接与读取；不是单纯多支持几种字幕格式。
- 7.x 后期新增 TrueHD Atmos / DTS:X 对象音频处理与 P7 FEL 双层合成等能力。7.37 的 FEL 路线为主动启用，受设备解码预算限制；不能等同于所有 P7 文件无条件播放为完整双层 DV。
- 7.39.0 将对象音频、FEL 双层合成、部分 DV 静帧处理改为默认关闭的可选构建组件。比较能力必须同时注明构建选项，不把最新版默认配置当成全功能配置。
- 对常见 SDR/HDR 电影，早期已有基础能力；对字幕/seek 稳定性、复杂原盘、对象音频等，跨到早期版本的差距实质明显。该判断来自发布说明，尚未经本项目真机复测。

## 新工具链构建实测（2026-10-18）

- 用户完成升级后，SSH 确认 macOS 27.0.1、Xcode 27.1、tvOS 27.0 SDK、Swift 6.4，首次初始化检查返回 0。全局 xcode-select 仍指向旧 Xcode，本轮仅以 DEVELOPER_DIR 显式使用新版，未修改全局选择。
- xcdevice 查询已找到 available 的 Apple TV 真机，设备为 tvOS 27.0。没有重新配对、没有启动模拟器。
- 临时 UIKit 宿主固定 AetherEngine 7.39.0 默认组件，调用原生播放表面、load/play/pause/seek/stop 与外部 SRT cue 接口；尚未连接 Go ABI，不是核心与新引擎的整合验收。
- Mac 直接解析远程 Git 依赖长时间停留在 fetching，主动停止该次解析。改由开发机下载固定 tag 的源码与依赖，再通过输入归档 SHA-256 校验传到 Mac，改为本地包路径。没有改引擎实现；依赖为 FFmpegBuild 3.7.1、LibDovi 2.1.0、SMBClient 0.3.1，另提供上游可选包 LibTrueHD 2.3.1、LibDTSX 2.0.0，未启用对应 traits。归档校验用于传输一致性，不声称存在上游公布的独立资产校验值。
- 首次 unsigned build 返回 0，确认引擎、依赖和临时宿主可以在本轮工具链下编译、链接。随后 signed build 返回 65，多个动态框架在 CodeSign 阶段出现 errSecInternalComponent；没有到达严格验签、安装或启动。
- 同一 SSH 连接的独立签名探针也失败，有效身份枚举为 1，security show-keychain-info 返回 36（交互不允许）。已请用户在 Mac 本地解锁登录钥匙串并允许签名访问，并打开钥匙串访问界面；没有索取密码或批量修改私钥 ACL。
- 前置服务检查发现当前最近更新的首个电影候选实际时长与此前样本不同，不能视作同片。临时宿主选择逻辑要求匹配先前 H.264、8-bit、BT.709 与源时长特征；该选择与播放器运行阶段尚未执行，不能宣称重放了先前片源。前置 HTTP 认证已正常登出。
- 临时宿主及编排脚本经独立只读审查，补充了样本约束、字幕验收条件与错误域输出限制；Python 语法检查通过。开发机 Python 3.11 与 Mac Python 3.9.6 不支持所用 tarfile filter 参数，已改为先检查归档成员路径及链接范围再解包，离线构建实际通过该步骤。
- 首次失败构建的远程临时工程、依赖副本、构建缓存与产物已自动删除，检查该轮临时目录残留为 0；该轮未安装应用。无凭据脚本与约 165.3 MiB 的可重复构建输入保留在已有忽略规则覆盖的 `.toolchain/tvos-aether-probe/`，便于恢复签名后重试；不入仓。可选工具安装尝试已停止，无本轮调试服务在运行。
- 该轮仅编译/链接通过，后续签名恢复与真机结果见下文；不将此前编译通过记为播放验收。

### 钥匙串状态判断修正

- 用户提供的界面确认登录钥匙串在 GUI 中已经解锁。此前把远程 security show-keychain-info 返回 36 作为继续要求 GUI 解锁的依据不充分；36 表示当前调用的交互访问受限，不能等同于 GUI 钥匙串处于锁定状态。
- 复测确认 SSH 与控制台为同一用户，默认钥匙串和用户搜索列表包含登录钥匙串，显式指定该钥匙串仍能枚举 1 个有效签名身份，但实际 codesign 仍报 errSecInternalComponent。未发现需要重新配对或重建证书的证据。
- 排查规则：先区分 GUI 锁定状态、SSH 调用上下文和实际私钥签名，不反复要求用户在已解锁的界面寻找解锁入口。用户随后在交互式 SSH 终端自行输入密码执行 security unlock-keychain；构建连接复验 show-keychain-info 返回 0，独立 codesign 和严格验签均返回 0。不要索取密码或直接批量改私钥 ACL。

### 签名恢复与 Scene 生命周期修正

- 签名恢复后 unsigned build、signed build、codesign --verify --deep --strict 均返回 0，内嵌 9 个动态框架；临时应用已成功安装到真机。
- 首次 launch 命令返回 0，但没有任何应用执行标志。读取最新 Probe 崩溃报告，定位为 UIKit 的 UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption 检查触发 EXC_BREAKPOINT / SIGTRAP；尚未执行到 AetherEngine 初始化，不能记为引擎崩溃或播放成功。该轮已登出、卸载、删除远程临时工程。
- 临时宿主改为 AppDelegate 配置 UIScene、SceneDelegate 使用 UIWindow(windowScene:) 创建窗口，并补充 UIApplicationSceneManifest。编排器必须读取 automated_pass=true done=1，不能只凭启动命令返回 0 判断通过。
- 修正后已观察 application_started、scene_started 与播放阶段标志；播放约 71.598 秒，原生 AVPlayerLayer ready=true，format=sdr，音频轨道 2 条。暂停漂移 0 秒，恢复 3 秒内前进约 2.995 秒，跳转后约 125.800 秒且原生层仍 ready；外部测试 SRT 返回 2 条 cue，可见 cue 采样 211 次，关闭后 cue 数为 0。应用输出 stop=1 automated_pass=true done=1，launch 返回 0，自动播放、暂停、恢复、跳转与字幕条件均通过。用户随后确认本次 SDR 样本颜色与实际声音正常，未观察到此前偏色；这是人工观察结果，不仅是音频轨道枚举或渲染层 ready。
- 片源按 H.264 / 8-bit / BT.709 / 源时长 4911.082 秒筛选，匹配先前对照样本特征；没有持久化媒体身份，不能仅凭时长宣称核对了同一媒体 ID。
- 播放器已停止，服务端登出成功，卸载返回 0，远程临时目录自动删除后独立查询残留为 0。没有本轮调试服务继续运行；本地忽略目录中的无凭据可重复构建输入保留，Python 字节码缓存已删除。
- 本轮只验证默认组件下的这一 SDR 样本与外部测试 SRT。Go ABI 整合、进度保存、冷启动续播、内嵌字幕、HDR / DV、对象音频与长期稳定性均未在 AetherEngine 验证，不能据此宣布正式 tvOS 客户端完成。

固定来源：

- https://github.com/superuser404notfound/AetherEngine/blob/1.0.0/Sources/AetherEngine/Video/VTCapabilityProbe.swift#L22
- https://github.com/superuser404notfound/AetherEngine/blob/1.0.0/Package.swift
- https://github.com/superuser404notfound/AetherEngine/blob/2.0.0/CHANGELOG.md
- https://github.com/superuser404notfound/AetherEngine/blob/3.0.0/CHANGELOG.md
- https://github.com/superuser404notfound/AetherEngine/blob/5.0.0/CHANGELOG.md
- https://github.com/superuser404notfound/AetherEngine/blob/7.39.0/CHANGELOG.md

## 来源

- https://github.com/superuser404notfound/AetherEngine/blob/main/Package.swift
- https://github.com/superuser404notfound/AetherEngine/blob/main/README.md （Requirements、License、播放器与字幕示例）
- https://github.com/superuser404notfound/AetherEngine/blob/main/Sources/AetherEngine/AetherEngine.swift （架构注释）
- https://github.com/superuser404notfound/AetherEngine/blob/main/docs/api.md
- https://github.com/superuser404notfound/AetherEngine/blob/main/LICENSE

## 后续核心整合

用户同意继续后，完整 Go 核心 + AetherEngine 7.39.0 的临时真机探针已完成起播、暂停/恢复/跳转、服务端进度读回以及新进程/全新数据根续播验证，详见 [核心整合与续播实测](aether-core-resume.md)。此前独立探针的未接 ABI 边界仍按其对应阶段解释，不把独立播放证据与后续整合证据混写。
