# 播放器可靠性详细设计

## 1. 输入与现状

依据：[七项目研究](../10-07-player-reference-research/research/player-reference-report.md)。核心目标是稳定、快、可定位，技术选型继续沿用 Go/libmpv + Avalonia 和 Kotlin/Compose/Media3。

当前控制矩阵：

| 功能 | 桌面 MPV | Android MPV | Android Media3 | 本批决策 |
|---|---|---|---|---|
| seek/暂停/倍率/音量 | 核心 | PlayerController→核心 | PlayerController→ExoPlayer | 保持现有路由 |
| 字幕/音轨切换 | 核心运行期 ID | 核心；回退按身份 | TrackSelectionOverride | 不跨核复用 ID |
| 字幕/音频偏移 | mpv 属性 | TV 控件限 MPV；手机无此入口 | 未发现偏移补偿；TV 已隐藏 | 记录限制，不冒充支持、不新增界面 |
| SRT/VTT 样式 | mpv | mpv | Compose TextCue | 保持现有配置拥有者 |
| ASS 样式 | libass/mpv | libass/mpv | 自有 libass；描边/粗体有限制 | 不通过 stripping 破坏特效 |
| 图形字幕字号 | 原图限制 | 原图限制 | 原图限制 | 不伪装支持 |
| 章节跳转 | 核心章节+seek | 核心数据+控制器 | 核心数据+控制器 | 不认定 mpv 专属 |
| 手机截图 | 不适用 | PixelCopy | PixelCopy | 不改为核心 screenshot |

核对没有发现必须新增能力注册框架的错误接线；第一阶段只记录支持矩阵，代码从诊断开始。

## 2. 分阶段交付

| 阶段 | 交付 | 依赖及验收 |
|---|---|---|
| A（本批） | 能力核对、Media3 首帧与缓冲分类诊断 | 不影响现有控制；纯判定与真实 listener/effect 接线回归 |
| B | 相对 seek 累加与显示时钟协调 | 核对页面/通知/遥控全部入口；三次 +10s 得到 +30s；旧完成/轮询不能回弹；失败/换片退出 pending |
| C | 轨道语义增强与系列记忆 | 先核实最终 payload 可用属性；保留手选/off；同语言 forced/SDH/位图与无标签/歧义测试 |
| D | 缓冲预算与起播优化 | 先测堆/native峰值、码率、冷/热首帧及重缓冲；必要时才调 LoadControl 或初始化时机 |
| E | HDR/DV、音频直通与路由 | 确认解码器+显示+音频输出能力；真实设备矩阵后启用，TV 验收暂缓 |

阶段 B–E 是后续实现方案，本批不宣称已完成。当前先接 Media3，因为已有可靠首帧事件；MPV/Avalonia 的真实呈现信号须单独确认，不能拿 file-loaded 或 time-pos 替代。

## 3. A 阶段数据流与拥有者

`rememberExoPlayer` → `ObserveMedia3Diagnostics(player)` → 一个 Player.Listener + 每秒状态采样 → `PlaybackHealthMonitor` → 去重后的数值事件 → 现有 Logs。

- 一个播放器实例一个 monitor。在 rememberExoPlayer 的 DisposableEffect 中绑定，先于页面 LaunchedEffect 首次 load；每次媒体 transition 无条件重新 begin，null 媒体则终止。Composable 使用 player key；销毁时撤 listener、取消采样。无进程注册表，无新核心命令。
- 所有播放器 API 在其当前 UI/application looper 读取；Logs 现有实现同步写盘，因此日志通过受管理协程的 IO dispatcher 写入。
- 媒体切换从 onMediaItemTransition 起算，清空首帧/READY/卡顿基线；停止/错误禁用本次诊断，新的切换才重启。
- 每秒通过 LocalLifecycleOwner.lifecycle.currentState 读取页面可见性，并注册 LifecycleEventObserver 在生命周期事件立即清窗口，避免短暂后台落在两次采样之间；任一首帧门控条件不成立立即清窗口，再次全部满足时由第一个样本重新建立时间/位置基线。停止/错误终止当前 episode，下一次媒体 transition 才启用。状态采样包含 active（页面 RESUMED）、playWhenReady、播放抑制、state、position、bufferedPosition、speed、isLoading、是否有选中视频轨、duration。
- 时间来自 SystemClock.elapsedRealtime（单调），不依赖设备系统时间。

## 4. 首帧与 READY

READY 是可播放阶段，不等于画面已显示。首次 READY 记录 `phase=ready media_elapsed_ms=...`；第一次 onRenderedFirstFrame 记录 `phase=first_frame media_elapsed_ms=...`。同媒体 Surface 重建重复首帧不重复计首次值。

READY、有选中视频、想播放、无抑制且页面可见时连续 6 秒未收到首帧，只记 `first_frame_missing`，不猜测是解码器/Surface 故障。切到 BUFFERING、暂停、seek、后台等情况重新开始等待窗口。纯音频不触发这一告警。

测量起点是媒体切换回调，通常位于核心取流之后；不包含点击前/取流时间，不用于声称端到端起播性能达标。暂停/后台仍可能计入阶段总耗时，日志名称不会误称为纯解码耗时。

## 5. 缓冲分类

只在 BUFFERING、想播放、无抑制、前台且不临近 EOF 时检查：

1. 倍率必须有限且 >0、位置非负才分类，否则清窗口。`playoutAheadMs = max(bufferedPosition-position, 0) / speed`。
2. 正在 loading 且可播放前缓冲不足 7 秒：等待数据；连续 12 秒位置没推进时记 `waiting_data`，不判服务器或网络失败。
3. 至少 7 秒前缓冲，或 loader 已停止且至少有正的缓存：数据条件已满足的候选状态；连续 12 秒位置无 >=250ms 进展，记 `buffered_stall`。这仍是症状，不是 renderer 故障证明。
4. 等待数据→数据满足，或相反时重新计时；短小位置抖动不刷新累计基线；倒退 seek/所有 discontinuity 显式清窗口。
5. 倍速改变、暂停/抑制、后台、IDLE、ENDED、错误、切片重置计时。EOF 已知且不足两秒时不诊断；未知时长不造总时长。

7 秒是高于当前默认 rebuffer 启动要求的保守诊断线，不是新 LoadControl 设置；后续调缓冲策略须同步调整判定并重测。本批不使用帧计数判断已出画面后的冻结，避免把时钟前进误认渲染健康。

## 6. 日志与隔离

阶段结果一次一条；某类异常在连续 episode 内只报一次，恢复进展/暂停/seek/切片后可重新报告。只有白名单枚举和数值，不写 mediaItem、URI、异常 message、token、用户/资源 ID。

第一版日志记录当前症状及 cache/speed/loading 的数值快照，不上传、不改导出流程。stop/release 后取消作用域，旧实例不再采样或发新日志。日志回调只构造白名单数值快照，由 player-key 的 Compose 作用域异步 launch(IO)；释放即取消作用域。已经执行的不可撤回数值写盘可完成，其内容不影响任何播放状态。

## 7. 后续阶段的具体约束

- Seek：使用 pending 目标作为下一次相对输入基准，原生请求串行/合并；UI 乐观进度与实际上报位置分开。Media3 seekTo 返回不代表完成，需 discontinuity/实际时钟确认；MPV 核心没有 seek 完成闩或 seeking 字段，依据实际 position 确认并设超时。容差按对应内核语义选择，超时有退出条件。
- 轨道：扩充 identity 前先验证 server streams 与 runtime tracks 是否能提供 forced/SDH/image/codec；缺失保持 unknown，不能补 false。匹配最高分并列拒绝猜选；同片回退与跨集偏好是不同语义，分别测试。
- 缓冲：Java heap、可用内存、native 解码与代理缓存共同预算；字节阈值与时间阈值同时考虑。详情字节预取已存在，先量化 mpv 初始化成本再考虑实例预热，禁止重复暖池。
- 恢复：分类诊断不会直接驱动换核。网络错误允许的重试预算、renderer 局部恢复和兼容换核分别定义；DRM/鉴权/损坏/未知错误维持现有处理。任何新恢复均保护版本、位置、暂停、倍率、音量、轨道和会话 stop 顺序。
- 输出：AudioCapabilities 结果只作为候选，用户直通选项只收窄支持，不能强开。HDR profile、transfer、display 和 surface 输出一致再启用；未知路径有明确降级原因。

## 8. 变更边界与回滚

本批预计新增 `Media3Diagnostics.kt`（纯状态与平台采样）、两个相关测试文件，在 ExoEngine.kt 一个共用入口接入，并更新 Android 规范。本批不改控制器、核心/FFI、UI 控件、LoadControl、服务端或新恢复行为。

回滚删除入口与新观察器即可，未改变任何持久化字段或服务端协议；测试与设计保留可供后续使用。主要风险是诊断误报，不会引发自动控制；通过暂停/后台/seek/EOF/低缓存回归与真机样本逐步校准。
