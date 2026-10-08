# 参考项目播放器源码调研

日期：2026-10-07。LinPlayer 基线：`e0d16855`。调查对象是 `docs/cankao/` 的本地快照，不代表上游最新版本。

## 结论与优先级

LinPlayer 双内核、兼容错误回退、播放状态恢复、手机音频焦点/通知/PiP、预取和会话上报已经有实现。本轮不建议再次重做这些主干。参考项目最有价值的增量是把“能播放”进一步做成“遇到边界情况仍然可靠”。

| 顺序 | 候选方向 | 借鉴来源 | 对 LinPlayer 的意义 | 开始实施前需确认 |
|---|---|---|---|---|
| 1 | 两内核控制能力核对，尤其字幕/音频偏移 | CineIsle | 防止 UI 控件存在但命令作用在另一内核 | 逐端追踪控件→命令→实际渲染，区分普通文本、ASS、外挂字幕 |
| 2 | 首帧与卡顿诊断，再按原因做有限恢复 | Plezy、Moonfin、qEmby | 区分取流慢、数据不足、解码/渲染停滞；减少无限转圈 | 先获得首帧、缓存、时钟、帧计数等可信信号，不凭单一超时换核 |
| 3 | 连续 seek 累加、旧时钟隔离 | Plezy、Plozz | 防止连按快进少跳、进度条回弹、旧 seek 污染新片 | 核对现有各输入入口是否已有目标累加；按内核真实完成语义设计 |
| 4 | 字幕语义匹配和系列偏好 | Plezy、Plozz、Moonfin | 同语言多轨时区分 forced、SDH、位图；跨集降低误选 | 当前匹配已支持标题/语言/序号，补证据充分的差异，不替换整套机制 |
| 5 | 内存约束下的缓冲预算与起播测量 | Plezy、Moonfin、JellyCine | 高码率防断供，低内存防 OOM；区分字节预取和内核预热 | 先测冷/热首帧、堆和 native 内存，确认当前预取缓存及解码器预算 |
| 后续 | HDR/DV、音频直通及路由变化 | Plezy、Moonfin | 设备兼容上限 | 需要显示器/设备/功放矩阵；TV 实测暂缓，本轮不作为立即实施主线 |

这是研究建议，不是已经确认的缺陷列表。检索未定位到的能力须追踪调用链后才可判定缺失。建议下一轮先做第 1 项的播放器能力核对，范围小且可以直接发现两内核实际行为差异，再推进第 2 项。

## 方法与证据边界

- 七个项目分组只读调查，另独立核对 LinPlayer 当前源码与测试定义；主线程抽查了最高优先级机制及反例。
- 参考目录未被 LinPlayer CodeGraph 正确覆盖，参考代码使用限定路径文件检索；LinPlayer 使用 CodeGraph 导航并以工作树确认关键片段。
- 未拉取上游、未构建或运行参考项目、未执行测试、未启动服务、未改播放器或 MediaStationGo。
- “有测试”仅表示找到相关测试源码，不表示本轮跑过。设备表现、兼容率、性能收益均未实测。
- 下文 `路径:行号` 相对仓库根。源码中的大文件只调查与播放主线相关的路径，没有声称逐行审计整个项目。

## 七项目概览

| 项目 | 本轮确认的播放路径 | 最值得借鉴 | 主要限制 |
|---|---|---|---|
| plezy | 平台播放器工厂；Android MPV/Exo 与故障回退 | 卡顿分类、seek 合并、语义选轨、输出能力策略 | 设备补丁多，不能整体复制；部分回退比 LinPlayer 白名单更宽 |
| Moonfin-Core | Flutter 播放管理 + Android 原生 Media3/MediaKit | 内存缓冲预算、首帧事件、codec/直通能力探测 | 部分恢复依赖服务端转码；直播规则不属于当前新增范围 |
| CineIsle | MPV/GSY 两个独立 Activity | 功能能力门控、轨道属性、原生系统集成 | 跨 Activity 交接；GSY 底层依赖外部库 |
| Ghosten-Player | Flutter 控件 + 外部 video_player 包 | 手机手势、PiP 事件桥 | 快照缺少实际播放器包，无法据此评价内核可靠性 |
| JellyCine | Android Media3/MPV | 配置签名预热、有限预取、首帧 watchdog | README 回退方向与代码相反；HDR/直通宣传不能全由当前代码证明 |
| Plozz | Apple 多引擎协议与协调层 | 时钟协调、歧义拒选、异步停止与代际隔离 | Apple API 不适用于 Android/Avalonia，只借鉴约束和测试思路 |
| qEmby | Qt/libmpv 渲染与本地 HTTP relay | 真实呈现就绪、GL 生命周期、字幕/弹幕主次契约 | Qt/GL 和 relay 具体实现不能直接搬入现有架构 |

## 1. Plezy：故障恢复最有借鉴价值

### 卡顿不是一种状态

`docs/cankao/plezy/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/BufferingStallPolicy.kt:84` 的 `evaluate` 同时看时钟进展、bufferedPosition、倍率、load control 就绪和 loader 状态。分为 HEALTHY、WAITING、STARVED、STALLED。缓存不足时重置停滞计时，不把长网络等待累计成 renderer 卡死。

特别值得保留的细节：前缓冲需要按播放倍率折算；字节上限满了可能尚未达到时间阈值；loadControl 的旧 false 不能永久阻止诊断。恢复入口见 `ExoPlayerCore.kt:3287`。本轮主线程已抽查该纯策略。

`ResumeStallPolicy.kt:37` 处理另一种故障：恢复后时钟/音频前进，但帧计数没变化。窗口随帧率和倍率变化，临近 EOF 跳过，恢复数量有限。其 250ms 倒退 seek 是特定 codec flush 手段，不应直接当通用自动修复。

相关测试源码：同目录 `src/test/.../BufferingStallPolicyTest.kt`、`ResumeStallPolicyTest.kt`、`LoadControlPolicyTest.kt`。可借鉴“纯判定 + 外部副作用”模式，前提是 LinPlayer 确有此类恢复需求。

### seek 与轨道

- `docs/cankao/plezy/lib/screens/video_player/parts/seeking.dart:14`：native seek 串行化，完成后核对媒体代次；`:54` 相对快进从各输入入口进入同一累加器，避免全部以旧位置计算。
- `lib/services/track_manager.dart:179`：轨道异步到达有等待上限；先应用可用音轨，不因字幕晚到无限等待；5/30 秒为该项目策略，不能直接移植。
- `lib/services/track_selection_service.dart:346`：跨集语义匹配遇到并列最高分不猜选；`:911` 语言规则支持 ISO 变体和地区码；`:939` 明确导航意图、服务端选择、账户偏好与默认的优先级。
- `android/app/src/main/kotlin/com/edde746/plezy/exoplayer/ExoPlayerCore.kt:1285`：错误先尝试局部恢复，HTTP 404/500 不用换核掩盖；剩余错误回退范围比 LinPlayer 宽，不建议照搬。
- `ExoPlayerPlugin.kt:1435`：回退请求保留 URI、headers、位置、自动播放和外挂字幕，并合并重复、拒绝过期代次。

### 缓冲与设备输出

`LoadControlPolicy.kt:148` 按 heap/可用内存限制 target bytes，上限参考 Media3 默认，另有最低预算。有效预读秒数约为 `8 × bytes / bitrate`；拉长 maxBufferMs 并不保证读到那么多。冷启动与 rebuffer 阈值分开，主线程已抽查。

`mpv/SurfaceFrameRateVote.kt:42` 随 Surface 生命周期提交帧率×倍率；`exoplayer/AudioOutputPolicy.kt:92` 按输出路由区分 raw 与 IEC 载体能力；`mpv/MpvPlayerCore.kt:1510` 有按 DV profile/显示能力选择路径。只确认源码机制，未追完全部 EGL/HDR 分支，不证明任意设备有效。

下一集 `lib/screens/video_player/parts/episode_queue.dart:154` 只确认详情元数据预热，不是媒体字节或解码器预开。不能把它当作超越 LinPlayer 现有数据预取的证据。

## 2. Moonfin-Core：内存和输出能力决策

- `docs/cankao/Moonfin-Core/packages/moonfin_native_video/android/src/main/kotlin/org/moonfin/nativevideo/Media3VideoView.kt:1642`：target bytes 按进程实际 max heap 的三分之一约束；低内存设备不拉长时间预算。主线程已抽查，和 Plezy 不同，说明预算应适配本项目而非选一个常量复制。
- 同文件 `:1311` 使用 `onRenderedFirstFrame()` 揭开遮罩并进入帧率处理。`lib/playback/media3_player_backend.dart:136` 有启动/首帧告警阈值。
- `packages/playback_core/lib/src/playback_manager.dart:2729`：非本地且未转码的启动失败，只重新解析一次并要求转码；不能直接假定 Emby/MSGo 都能提供同等转码契约。本轮不改服务端。
- `AudioPassthroughPolicy.kt:13`：用户设置只收窄 sink 原本支持的能力，不能凭设置强开格式；`:98` 可退 DTS core。这比“支持 DTS”标签更准确。
- `android/app/src/main/kotlin/org/moonfin/androidtv/MediaCodecCapabilities.kt:167`：排除软件 decoder 与已知虚报能力。探测结果是路由依据，不是播放成功保证。
- `lib/playback/media3_player_backend.dart:71` 与 `test/playback/dolby_vision_transcode_reason_test.dart:78`：DV 按 profile、显示和解码器给出决策原因。引用全部以 `docs/cankao/Moonfin-Core/` 为根，不能误指 LinPlayer 同名路径。
- `lib/util/subtitle_track_logic.dart:336`：系列偏好用语言、标题和同语言序号；没有语言时不随便猜位置。
- `playback_manager.dart:2925`：进度请求合并，并对结束后迟到请求补 stop；LinPlayer 已有串行进度/stop 和迟到隔离，不需要因此换一套上报框架。

直播首帧、停帧和重连预算有独立实现（`playback_manager.dart:244`），不应拿其 30/15/8 秒直接用于点播。本轮不建议新增直播范围。

## 3. CineIsle：功能入口要与实际内核能力一致

`docs/cankao/CineIsle/app/src/main/java/app/marlboroadvance/mpvex/ui/player/engine/EngineKind.kt:151` 的 `supports` 和 `:176` 的 `supportsButton` 按内核移除不支持的功能入口。主线程已抽查。可借鉴约束，LinPlayer 暂不需要为两内核引入复杂能力注册框架；少量明确条件即可。

这对当前字幕/音频偏移核查有直接价值：不能因为核心拥有某个 mpv 命令，就认定 Media3 也支持。应明确支持的字幕类型，能实现则正确路由，否则界面说明或禁用。

独立复核确认：LinPlayer TV 的 `TvPlayerParts.kt:481` 已在 `exo == null` 时才显示偏移控件，避免了这一入口的错误路由。因此这项是能力一致性核对建议，不能描述成 TV 已存在“无效控件”缺陷；手机与桌面全部入口仍未完整核对。

- `.../ui/player/PlayerActivity.kt:599`：将原 Intent 复制到 GSY Activity，交接队列/位置/Emby 会话信息；不是统一控制器内部无缝换核。
- `PlayerActivity.kt:4009`：MPV load 前应用 headers/Referer；`GsyPlayerActivity.kt:1364` 给另一引擎 headerMap。值得核查两核请求一致性，但不需要替换 LinPlayer 核心统一取流。
- `TrackSelector.kt:69`：读取 forced、hearing-impaired、external、image 等轨道属性；`:219` 默认策略过滤 forced/SDH。过滤 forced 只是此项目偏好，LinPlayer 应区分外语对白辅助字幕与完整字幕，不能一律排除。
- `PlayerActivity.kt:3564` 原生 MediaSession，`:747` 焦点/PiP；这类能力 LinPlayer 已有，不列为新增。

## 4. Ghosten-Player：交互参考多于内核参考

- `docs/cankao/Ghosten-Player/lib/pages/player/singleton_player.dart:22` 调用外部包的 PlayerPlatformView，传引擎/MPV版本/PiP；快照没有实际 `video_player` 源码，因此不能确认错误回退、网络缓存、轨道恢复和音频焦点。
- `lib/pages/player/player_controls_gesture.dart:40`：横向 seek，左右竖拖分别亮度/音量，长按临时倍速，双击分区域跳转/暂停。只能作为交互对照，未核对 LinPlayer 手势全链，不能认定待补。
- `android/app/src/main/kotlin/com/ghosten/player/MainFragment.kt:164` 将 PiP 状态桥接给 Flutter，控制层据此隐藏。
- `lib/models/models.dart:54` 字幕转换先验证 URL/MIME，再处理相对地址；比无条件导入全部 API 字幕可靠。

## 5. JellyCine：预热值得测量，宣传需要校验

`docs/cankao/JellyCine/phone/src/main/java/com/jellycine/app/player/mpv/MpvWarmPool.kt:17` 在偏好 MPV 时异步创建实例；`:82` 取用前按配置签名验证，配置过期则释放。签名包含硬解、输出、缓存和字幕/渲染设置，主线程已抽查。

LinPlayer 已有媒体数据预取且核心管理进程内 mpv。需要先确认现有 mpv 初始化时机、实例是否已经常驻，再测首次初始化成本；不要直接再建暖池。实例预热与 URL/字节预取解决不同阶段的问题。

- `.../ui/screens/player/PlayerViewModel.kt:933`：下一集 Media3 数据预取受 45 秒窗口和缓存预算三分之一限制；可借鉴“有上限、可取消、主播放优先”。
- `.../player/mpv/MpvPlayerController.kt:303`、`:365`：前后缓存字节与时间分别设置；`:191` exact seek。LinPlayer 已有 exact seek。
- `PlayerViewModel.kt:654`：Exo READY、有视频且想播放，但首帧未到时启动 watchdog；`:1534` onPlayerError 也尝试回退。真实方向是 Exo→MPV，与 `README.md:40` 宣称 MPV→Media3 相反。主线程已抽查。
- `MpvPlayerController.kt:280`：PQ/HLG 检测后同用 PQ dataspace；不能直接移植，二者输出语义需分别验证。
- README 无损音频直通描述未在对应 MPV 设置和设备协商中得到完整证明；`PlaybackDeviceProfileFactory.kt:70` 的 ac3/eac3 声明也不能证明 TrueHD/DTS-HD 直通。

首帧超时有诊断价值，但不能把所有错误/超时都自动换核。LinPlayer 当前只对明确兼容错误回退的范围应保留，其他恢复需有独立原因和预算。

## 6. Plozz：时钟与异步会话约束

- `docs/cankao/Plozz/Sources/FeaturePlayback/PlaybackClockReconciler.swift:47`：seek 等待窗口内保持目标，实际时钟进入容差才释放；loading 临时读到零时保留上个有效位置。主线程已抽查。1.25 秒容差依赖其引擎 exact seek 语义，不适合作为所有引擎常量；后续实现还应有失败/取消退出条件。
- `SubtitleMatchScorer.swift:14`：先语言筛选，再按 forced/听障/位图属性评分，最高分并列拒选。主线程已抽查。与现有标题/序号机制结合时，必须保护用户手选和字幕关闭意图。
- `VideoEngine.swift:133` 显式异步 drainTransport；`PlayerViewModel.swift:2159` 先取最终位置、停引擎、drain，再发 stop。LinPlayer 已有 pendingStop/串行上报，借鉴顺序测试即可，勿新增重复会话层。
- `NativeVideoEngine.swift:207`、`PlozzigenVideoEngine.swift:459`：异步加载核对 generation，阻止停播后旧结果复活。
- `NextEpisodeCoordinator.swift:234`：取消后释放未接管的预取会话。若今后预取会创建服务端播放会话，应把此边界纳入验收。

测试依据：`Tests/FeaturePlaybackTests/PlaybackClockReconcilerTests.swift:87`、`SubtitleMatchScorerTests.swift:65`、`PlayerViewModelEOFTests.swift:1147`。只借鉴机制与测试场景，不新增 Apple 平台。

## 7. qEmby：首帧呈现与渲染资源边界

`docs/cankao/qEmby/src/qEmbyApp/components/mpvwidget.cpp:249` 的 `recordVideoFrameRendered` 先要求媒体已加载、输出可用和帧确实渲染，再在 `:44` frameSwapped 回调发呈现就绪。`:224` 换片清除旧状态。主线程已抽查；价值是区分“取流完成、播放器就绪、画面真实呈现”，具体 Qt swap 信号不能直接映射为 Android/Avalonia API。

- `mpvwidget.cpp:175`、`:281`：GL context 销毁时取消更新回调、释放 render context，恢复时要求有效上下文。可供桌面切窗/重建/退出回归设计参考。
- `mpvhttpstreamrelay.cpp:52`、`:200`：绑定回环、随机路径 token、限制 GET/HEAD 和转发头，并清理监听。LinPlayer 已有代理/预取路径，不能据此再建 relay；其头白名单也不足以证明支持全部鉴权场景。
- `playerdanmakucontroller.cpp:899`：内容字幕与 mpv 弹幕分主次轨，native overlay 时清理次轨，避免重复绘制。仅作为已有弹幕/双字幕能力的冲突核查参考，不新增需求。

## LinPlayer 对照与避免重复建设

| 能力 | 当前源码证据 | 本轮判断 |
|---|---|---|
| 双内核和窄范围单次回退 | `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/PlayerController.kt:142`；PlayerControllerTest/PlayerEngineLifecycleTest | 已实现，保留 |
| 位置/暂停/倍率/音量/版本恢复，晚到轨与手选保护 | `PlayerController.kt:117`、`:174`、`:182` | 已实现；属性语义可增强 |
| 标题/语言/同类序号匹配 | `PlayerController.kt:201` | 已有，不重复建议；尚无 forced/SDH/位图进入该身份模型的证据 |
| Media3 普通字幕与 ASS | `ExoEngine.kt:121`、`:182`、`:383`；Libass 路径 | 已实现；偏移跨核等价仍需核查 |
| 字幕/音频偏移核心命令 | `core/player/transport.go:193` | 当前命令写 mpv 属性；Media3 是否另有补偿未确认，优先核对 |
| 手机 MediaSession/焦点/通知/后台/PiP | PlaybackService.kt、MainActivity.kt:195，PlaybackServiceTest.kt | 已有源码及部分测试定义，本轮未实测 |
| 数据预加载 | `core/player/warm.go:163`、warm_test.go | 已实现；不等于解码器预热 |
| Media3 自定义缓冲预算 | `ExoEngine.kt:104` 的 Builder 未显式设置 LoadControl | 所查入口使用默认策略；先测预算，不推断所有平台都无调优 |
| seek 控制器 | `PlayerController.kt:112` 直接转发，Media3 引擎 seekTo | 所查层无累加/确认；需核对 UI/系统各入口后再定缺陷 |
| 章节/片头片尾 | prefscmds.go:426、skipcmds_test.go | 已实现；本轮未完整核对应用内连续播放 |
| 会话和主服进度 | start_report.go、progress_sync.go、相关核心测试 | 已实现，本轮不改服务端 |
| TV 刷新率匹配 | 已有实现与独立任务 `10-07-tv-refresh-rate` | 不重新立项；TV 真机继续暂缓 |
| HDR/DV、直通和路由 | 已有 DV 识别/软解路径；完整输出协商本轮未定位 | 单列设备验证，不能宣称全格式支持或完全缺失 |

## 后续实施的可验证目标

1. 能力核对：同一功能在 MPV/Media3、手机/桌面分别标明真实作用范围；字幕偏移用 SRT/ASS/外挂各一例验证，避免修改无关控件。
2. 诊断：能区分请求解析完成、READY、真实首帧；网络缓存不足不会误判 renderer 卡死，用户暂停/seek/换片/EOF 不触发自动恢复，记录不包含完整鉴权 URL/headers。
3. seek：快速连续三次 +10 秒形成 +30 秒目标；旧采样不回弹；换片取消旧目标；失败退出待确认状态；系统控件与页面行为一致。
4. 轨道：同语言普通/forced/SDH/位图、无标签、重复标签、晚到外挂、用户手选、字幕关闭都有确定结果；歧义不得静默错选。
5. 缓冲：相同资源测冷/热首帧、seek耗时、rebuffer数量、heap/native峰值；低内存与高码率各有样本，再决定预算和是否预热实例。

本轮只新增研究文档与任务材料。结论风险主要来自静态分析不能证明真机效果、参考快照可能过期、部分外部包实现缺失；不把这些不确定处转换成已确认缺陷或性能承诺。

## 独立复核记录

另一次只读审查抽查优先级 1–5、LinPlayer 对照及 JellyCine README 反例，未发现明确错误；确认 TV 偏移控件已按内核门控并补入上文。未复核所有次要引用，没有运行测试或真机验证。工作树检查仅新增本研究任务目录，无产品源码变更。
