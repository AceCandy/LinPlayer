# 排查证据与假设边界

## 用户对照

同一播放页在一次网络卡顿后，后续多集自动衔接持续转圈；退出到详情重进后恢复。该对照支持优先研究会话内生命周期，但不能单独证明 Media3 内部缺陷，重进也会改变加载时序与数据缓存条件。

## 当前工程事实

- Media3 固定为 `1.11.0`：`apps/android/app/build.gradle.kts:207`。版本相关行为须对照实际 AAR 或同版本官方源码，不套用默认分支。
- `rememberExoPlayer` 的 player 使用不带目标 key 的 `remember`，普通连播沿用同一实例；页面退出释放 player 和 `ExoPreload`：`ExoEngine.kt:108`、`:195`。
- 普通手机启用 `preload = true`，但流影已独立：`PlayerPage.kt:389`。
- `prepareEpisode` 对同一 `preparedFor` 返回；请求失败将 `preparedResult` 置空而保留条目标记：`PlayerPage.kt:425`。这是当前目标的恢复检查点，不是跨集残留的充分证据。
- 预载缓冲许可采用 30/10 秒滞回并考虑倍速；当前缓冲到片尾时可放行：`PlayerPage.kt:124`。
- 许可关闭会移除 Media3 预载源，再次放行时重建；核心准备结果可保留：`PlayerPage.kt:539`。
- 排队只在片尾 10 秒内且缓冲至尾时尝试；同一 request 只尝试一次，但 `queueAttempt` 按 controller 重置：`PlayerPage.kt:404`、`:557`。不能把它解释为永久关闭后续所有集。
- `ExoPreload` 只判断 pending 身份和管理源是否存在；未登记预载完成/失败监听：`ExoPreload.kt:40`、`:72`、`:93`。源存在不等于样本已经完成加载。
- 只有实际 media transition 才业务接管；正常加载和队列接管都保留单播放器：`ExoPreload.kt:55`、`:112`，`PlayerPage.kt:580`。
- 页面 250 毫秒采样一次 Media3 缓冲，转圈没有额外时间门槛：`PlayerPage.kt:750`、`:1275`。这是视觉放大因素，不足以解释网络恢复后跨多集持续异常。
- Go prepared 的上下文、代理和 warm 标记归单次准备槽；暂未发现网络错误永久修改配置或全局关闭下一集准备的证据：`core/player/prepare.go:18`、`:74`、`:133`。

以上路径均相对于 `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/`，另行写明路径的除外。

## 现有验证与缺口

- `PhoneEnginePlaybackTest.kt:158` 验证缓冲许可纯函数；`:173` 验证 MPV 下预热许可变化；`:280` 用反射状态验证 Media3 完播后停止/起播；`:305` 验证 MPV 队列接管。
- 这些不能证明实际 Media3 样本预载在网络中断后恢复，更没有覆盖同一 manager 连续三个后续目标。
- `LiuyingMediaSlotsTest.kt` 已有本地 `ServerSocket` 测试方式，`ExoAssMergeTest.kt` 已复用 `/subtitles/embedded.mkv`；可以借用测试技法和现有 fixture，不修改流影生产实现。
- `PlaybackBufferTest.kt` 已核查实际 LoadControl 和生产构造接线；不得只改设置回显或断言字段存在。

## 尚待验证的假设

1. 缓冲许可变化取消了在途样本预载，恢复时同一 manager 的调度或当前源状态没有恢复。
2. 预载源失败仍被当成存在的 pending，后续入队绕过了失败恢复；需要证明是否会影响后续目标，而不只是当前下一集。
3. 页面请求失败、取消与排队顺序导致缓存/预载源不再被实际使用；需要真实调用和媒体 transition 对照。
4. 网络恢复后实际缓存余量仍未达到准备条件；退出重进改变了时序，需要与会话状态残留区分。

目前均未作为已确定根因。没有复现前不叠加重试、重建播放器、修改内核参数或隐藏转圈。

## 2026-10-23 底层故障注入结果

- `ExoAssMergeTest` 既有基线通过。
- `ExoPreloadRecoveryTest` 三项通过：当前媒体完成缓冲后中断/取消下一条预载，恢复并连续准备及接管后续条目；预载真实报错后取消重建并完成；当前媒体实际进入 READY、推进后因数据源阻塞进入 BUFFERING，恢复后沿用同一 player/manager 完成后续三条预载与接管。
- 上述接管使用 `seekToNextMediaItem()`，不是页面自然完播；合成 PCM 不证明视频解码或真机首帧体验。
- 首次实验中，预载报错后只对同地址再次 `prepare` 不会恢复；这是相同 pending 的早退行为。生产页面在许可关闭时会 `cancel`，故这个实验不能直接证明用户的跨集故障。
- 测试时钟必须推进 Robolectric 主 looper；仅 `idle()` 不推进虚拟时间，无法等待实际播放时钟和重试。阻塞数据源在清理前解除，避免释放播放器等待加载线程。
- 页面/控制器只读复核未找到已证实的跨集禁用标记；`queueAttempt` 随 controller 重置，预热 collector 也随 controller 重建。继续以页面自然连播测试验证，不把静态候选写成根因。
- 新增 `PhoneMedia3RecoveryTest` 独立承载 HTTP + Compose 自然连播实验，避免向已有大型测试类添加整套网络夹具。它只模拟核心回执，不能替代真实核心上报、代理及设备验收。

## 页面自然连播与复核

- 页面实验通过：`sh6` 自然进入 `sh7`，本地 HTTP 源在 `sh7` 的约 4 秒数据后暂停响应，实际播放器已推进后进入 BUFFERING；恢复响应后，在同一页面、同一 player/owner 内自然进入 `sh8/sh9/sh10`。
- 三个后续条目均在切入前满足 `totalBufferedDuration >= 当前集剩余时间 + 5000ms`；切入理由为 `MEDIA_ITEM_TRANSITION_REASON_AUTO`，页面发送对应 `player.advanceQueued`；切入后时钟继续推进且没有新增 BUFFERING 事件，整个过程 `player.play` 一次、`player.stopPlayback` 零次。离页断言 player 已释放。
- 起初强制要求每条收到 manager `onCompleted`，一次失败、一次通过。该断言不适合已入队源：入队后播放器可接管并继续加载。最终改验切入前实际队列缓冲，不删减“样本已加载”的要求。
- 独立复核确认该实验是 HTTP 响应停顿/恢复，不是连接断开后的网络重试；PCM 不涵盖真机视频解码，FakeCore 不涵盖真实 Go 代理、续播及上报。没有据此认定用户故障已解决。
- `prepared`、`trace` 和转场集合由主线程的页面调用/监听写入；HTTP 线程只访问不可变媒体及 latch。清理以 `finally` 关闭 HTTP/executor、取消测试和收尾作用域。
- 独立 ADB 端口检查无可用设备，检查后关闭服务。未安装应用、未清应用数据。
- 并行任务曾覆盖默认 Gradle 测试结果，导致 `in-progress-results-generic.bin` 缺失；以本任务临时 init script 隔离二进制结果后，三组联合回归 `BUILD SUCCESSFUL`。原始结果保留于忽略的 `apps/android/app/build/test-results/media3-recovery/binary/`。
- 随后的再次复测被另一任务正在修改的 `LiuyingMediaSlots.kt:94` 返回类型不匹配阻断，尚未执行到测试；该失败不算本任务回归失败或成功，不修改流影代码绕过它。
- 已从隔离的成功运行二进制结果单独生成报告：`apps/android/app/build/reports/tests/media3-recovery/index.html`，共 9 项、0 失败、0 跳过（既有字幕预载 5 项、底层恢复 3 项、页面恢复 1 项）。报告与二进制结果均在已忽略的 build 目录，不入仓；生成报告不是重新执行测试。
- 两个新增测试的 diff 空白检查及任务文档空白检查通过。临时反编译材料、Gradle init script 与 ADB 日志已删除；HTTP 测试服务和独立 ADB 服务已关闭。未出包、安装、提交或推送。

## 当前结论与下一步

尚未复现用户的“网络恢复后后续每集持续转圈”，不提交推测性生产补丁。下一步需要问题手机上的应用版本、故障时间附近日志，或可连接设备；对比正常连播、卡顿后连播与退出重进后的起播阶段。

既有日志入口为设置 → 存储与数据目录 → 导出日志；导出包含 app 日志、logcat 和核心诊断，可能含隐私，不应直接入仓或公开粘贴。优先核对 `lp-playback` 的 startup/ready/first_frame 阶段，区分正常重新起播和队列切入后的等待。

已评估规范更新：没有新的生产契约或已证实根因，暂不写入状态管理规范；实验及证据边界留在本任务，避免把尚未证实的归因固化。

## 用户请求手机测试包

- 用户明确授权出包后，标准 `scripts/pack-android.sh` 成功生成 `build/android/app-arm64-v8a-release.apk`，版本 2.0.0（20000），大小 29,718,698 字节；未改版本或生产播放逻辑。
- 独立校验 `apksigner verify` 通过，Manifest 的 `TV_BUILD=false`，ABI 仅 arm64-v8a；核心及 mpv 原生库存在，ZIP 中原生库均压缩。
- 最终归档 APK 与本次 Gradle 产物 SHA-256 一致：`1bdc6e991dba3a7b6b54f3a9020b388709c4665554a7cb04152521cb04e9160e`。
- 此包用于真机复现与导日志，不是已验证修复包。未安装、未做真机播放验收、未重新运行恢复测试，未提交或推送。
