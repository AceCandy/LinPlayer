# 规划依据与风险核查（2026-10-18）

本文件是静态研究与前置实测的索引，不是正式客户端的验证结果。只读探索经过主线程关键位置抽查；本轮未连接服务端或重新构建探针。

## 核心边界

| 位置 | 事实与设计含义 |
|---|---|
| core/emby/commands.go:83-95 | views 缺省过滤屏蔽库；正式首页不能随意 include_blocked=true。 |
| core/emby/commands.go:137-155 | listItemsPage 从 a[query] 反序列化分页条件；itemDetail 的 with_children 可控制是否全量展开。旧命令表列并非当前 JSON 参数规范。 |
| core/emby/commands.go:180-185 | seriesSeasons(series_id)、seasonEpisodes(parent_id,start_index,limit) 可按季分页。 |
| core/emby/commands.go:283-310 | emby.login 必须传稳定 device_id；认证后核心持久化账号并激活。不能把探针固定字符串或每次新 UUID 当产品设备标识。 |
| core/emby/commands.go:429-436 | emby.logout 的服务端错误转为 server_ok=false，命令仍成功；显式退出需另行尝试本地 removeAccount，不能因服务端失败保留本机账号。 |
| core/account/account.go:26-54 | Info 不带 token/password；不能靠 listAccounts 返回值重新构造完整持久账号。 |
| core/account/account.go:212-233 | setActiveServer 与 removeAccount 经 commit 保存并刷新白名单。 |
| core/player/transport.go:24-64 | engine=exo 是已有宿主播放模式；其它值会落 mpv。 |
| core/player/transport.go:67-126 | stopPlayback 与 reportProgress 接收实际 pos，核心维持播放会话；item_id 可拒绝旧目标报告。reported=true 仍不证明服务端持久化。 |
| core/player/transport.go:223-235,413-440 | setTrack/tracks 操作 mpv，不能控制 Aether 的轨道。 |
| core/paths/paths.go:7-8,22-62 | Apple 数据根由宿主传入，核心统一推导配置/历史/缓存路径。 |
| core/config/config.go:82-120 | 文件缺失才可作为新装；解析损坏不得吞成空配置。Apple 存储后端需要保留同样语义及未知字段。 |
| scripts/gen-bindings.py:9-22,49-56 | 现有生成器输出命令方法/清单/ABI，不生成参数返回 DTO；规划时只有 C#/Kotlin；本任务已扩展 Swift。 |

临时 CoreBridge.swift:41-64 的 call 方法会在等待某一条结果时丢弃其它事件，且要求结果是对象。正式浏览有数组/null/并发及 partial，必须改为常驻单消费者分派，不能直接把该测试桥重命名使用。

## 固定 AetherEngine 证据

先通过 Gread 核查公开仓库结构；精确接口从前置探针 input.tar.gz 中的固定 7.39.0 源码直接读取，未使用浮动 main 的 API 替代依赖版本。

- Dependencies/AetherEngine/Package.swift:1-13：Swift tools 6.1，tvOS 18 deployment target；默认 traits 为空。
- 同包 docs/api.md:804-806：audioTracks 与 activeAudioTrackIndex；selectAudioTrack(index:) 参数是 TrackInfo.id，选择会 reload；remoteBypass 列表仅提供信息，调用不切轨。
- 同包 docs/api.md:813-820：selectSubtitleTrack(index:)、clearSubtitle()、subtitleCues，普通文本/富文本/图像 cue；addExternalSubtitleTrack 为运行时外挂路径。
- 同包 Sources/AetherEngine/AetherEngine.swift:7330-7347：remoteBypass/不可 seek 自定义源明确拒绝音轨切换；不能画无效的可点选项。
- 前置 aether-engine.md:60-67：原生层、暂停恢复/跳转、外部 SRT 显示与关闭及用户画面/声音反馈。音轨只枚举到两条，没有实际切换验收。
- 前置 aether-core-resume.md:33-48：核心整合保存 126 秒、新进程续播至约135秒，两轮服务端独立读回；未重测字幕/声音人工观察。

上游： https://github.com/superuser404notfound/AetherEngine/tree/7.39.0 。Gread 的默认分支查询仅用于定位仓库，不作为版本接口依据。许可为 LGPL-3.0 加 Apple Store/DRM 例外；依赖不自动被例外覆盖，正式依赖获取时逐项保留声明。

## 平台与构建

- 前置 remote_core.py:93-103,129-148：tvOS clang target 下的完整 Go c-archive；MPVKit 28 个真机切片（含 MoltenVK .a）、-load_hidden、最终符号检查。
- 真实可用工具链 macOS 27.0.1 / Xcode 27.1 / SDK 27 / Swift 6.4 / Go 1.27.2；AetherEngine 7.39.0 默认组件。不能把 deployment target 或最小 Go 探针扩展成全应用最低系统实测。
- 正式构建不能依赖未跟踪的临时 runner、自动卸载或缓存归档。依赖版本/来源/校验与脚本需可审查；实际签名私钥权限以执行构建的 SSH 上下文预检。
- 固定版本库最低目标为 tvOS 18，本轮仅真机 tvOS 27 已验。旧版本设备、模拟器、商店分发均未验。

## tvOS 存储与生命周期

Apple《App Programming Guide for tvOS · iCloud Storage》明确“there is no guarantee that information stored on the device will be available the next time a user opens your app”。这是历史文档，不用其旧容量数字推断当前系统；它足以否定“只把 config 放本地缓存即可保证免登”。

- Apple Developer 文档路径：`/library/archive/documentation/General/Conceptual/AppleTV_PG/iCloudStorage.html`。
- Apple Developer《Using the file system effectively》，文档路径：`/documentation/foundation/using-the-file-system-effectively`（当前文档亦说明 Caches 可由系统清理）。

本轮未验证 tvOS 27 Keychain 的真机行为，不能声称持久化已经完成。设计要求先做专用后端门禁：核心保持数据所有权、平台安全存取、缓存删除后恢复账号/设备标识/隐私规则、退出账号后不复活。

前置 CoreProbe.swift:11-34 的 Scene 生命周期仅建窗，关停靠自动测试末尾执行；没有后台收尾证据。正式界面要处理挂起暂停、有限时间上报和前台不自动出声，不能等可靠的终止回调。

前置 remote_core.py 的探针 Info.plist 开全局 NSAllowsArbitraryLoads。正式工程必须按实际核心回环、局域网固定IP媒体、Aether URLSession 和本地网络授权分别验证最小例外；不能据 Go 请求成功断定 AVPlayer 也能访问。

## 本轮边界

本文记录规划时的研究事实；后续产品实现与检查证据见 [implementation-checks.md](implementation-checks.md)。正式应用未出包、未安装，Keychain/遥控/播放/最低系统和长期稳定性仍未验收。
