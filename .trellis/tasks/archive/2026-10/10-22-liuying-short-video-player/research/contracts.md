# 代码事实、约束与设计依据

核对日期：2026-10-22；基线HEAD为58f2494d，生产工作树在本次规划开始时干净。这里只记录源码事实/推断边界，不记录真实服务器、账号、令牌或本地调试数据。

## 1. 当前流影与Android链路

| 事实 | 依据 |
|---|---|
| 流影当前按playbackRevision重建手机PlayerPage，TV走TvPlayerPage | apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/NativeLiuyingContent.kt:303 |
| 候选只保留有界窗口，越页才请求新列表；纵拖结束达到64dp才move | 同文件:122、:149、:203 |
| 播放页读取普通内核选择；偏好按页面重新加载 | apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/PlayerPage.kt:249、:377 |
| 普通下一集准备依赖剧集nextEpisode及片尾60秒，不是流影下一条 | 同文件:533 |
| 起播等待进程级停止屏障，再调用player.play；流影带speed_scope | 同文件:679、:694、:713 |
| Media3依赖已固定1.11.0；不授权为此升级 | apps/android/app/build.gradle.kts:207 |
| ExoPreload只有一个pending，前5秒样本；明确不建第二解码器/输出 | apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/ExoPreload.kt:38、:43、:48、:93 |
| MediaSource命中可直接交给播放器，字幕准备所属媒体并延迟激活 | 同文件:16、:112 |
| ExoSurface使用SurfaceView并绑定player | apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/ExoEngine.kt:284、:373 |
| PlayerController有进程级pendingStop与handoffIn；后者整块NonCancellable，不能不加区分用于长解析任务 | apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/PlayerController.kt:222 |
| 通用PlaybackService是前台服务、系统媒体控制、焦点及状态轮询owner | apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/PlaybackService.kt:50、:65、:99、:113 |
| AppState给调用附加request_scope并拒绝旧空间/会话响应 | apps/android/app/src/main/kotlin/xyz/linplayer/app/data/AppState.kt:183 |

新流影不嵌入PlayerPage，不复制通用页的分集/弹幕匹配/OSD/旋屏/后台服务流程。字幕和选轨的必要低层能力可原样调用，但双槽不能让备用解析器写全局当前libass。

## 2. 核心正式播放与准备

| 事实 | 依据 |
|---|---|
| preparedNext是全局单槽，新prepare关闭旧槽 | core/player/prepare.go:31、:81 |
| 消费要求request_id、完整Session、item/source、选版规则及AdultFilter相符，一次消费 | 同文件:54 |
| prepare遵守PreloadEnabled，仅DirectStream保留缓存代理，不开始播放/记账 | 同文件:74、:106、:110、:114 |
| warm单独启动，暂停保留解析/代理；头部上限8MiB，不等于总内存 | 同文件:132、:152 |
| player.play统一版本、历史、current/currentReport、正式上报、代理接管；engine=exo不调用loadWith | core/player/playback.go:120、:151、:164、:223、:238 |
| PlayResult提供最终PlayURL和外挂字幕，UI不能自行拼上游地址 | 同文件:260 |
| stop清旧current与sharedProxy，但不直接取消未消费preparedNext | 同文件:623 |
| handoff把本次停止报告后台化，下一stop仍awaitStopReport | 同文件:628、:670 |
| 新Playing等待前Stopped完成；不能从异步上报推导连滑不受慢网络影响 | core/player/start_report.go:50 |
| 普通player.play明确校验engine/speed_scope再调用共享play | core/player/transport.go:24 |
| bus媒体分类显式列出player.play/prepareNext等；新命令不能只注册handler而遗漏scope | core/bus/request_scope.go:38、:99 |

推断与设计：多槽必须是独立准备池；正式起播只增加可选内部准备来源及owner守卫，默认保留通用语义。仅按item_id不能保护“同一条目新会话”不受旧stop影响。设计需通过故障回归证明，不能靠注释宣称独立。

## 3. 现有磁盘媒体缓存

| 事实 | 依据 |
|---|---|
| CacheOptions只有Identity/Bitrate/Budget；StartCached每次更新同一全局预算 | core/net/prefetch/prefetch.go:110 |
| 每次新代理先在线probe，持久恢复需可信身份和validator | 同文件:320、:328 |
| no-store/manifest拒绝持久验证，强ETag优先；无ETag时受限Last-Modified | 同文件:425、:433 |
| 后续新读取的网络块范围/版本在live.feed前校验；不能说每次磁盘块命中都重新发HTTP | 同文件:617 |
| 字节身份为固定server/user/item/source，不含授权token和临时线路 | core/player/warm.go:94 |
| 默认总预算1GiB，单媒体128MiB、TTL7天，完整块带哈希、原子发布 | core/net/prefetch/persistent.go:22、:30、:177、:209；core/config/prefs.go:439 |
| 持久/会话ring都计入总预算，LRU可淘汰活动媒体，不能无限pin | core/net/prefetch/persistent.go:252 |
| 统一清理禁用旧disk，防止旧句柄回填；Close仅删除非持久临时文件 | 同文件:59；core/net/prefetch/cache.go:386 |
| paths.MediaCache为数据根cache/media，索引不存授权地址 | core/paths/paths.go:67 |
| Android把filesDir传给核心，Manifest禁止备份；媒体块是原始字节不是密文 | apps/android/app/src/main/kotlin/xyz/linplayer/app/LinPlayerApp.kt:60；apps/android/app/src/main/AndroidManifest.xml:50；core/net/prefetch/persistent.go:231 |
| 全局清理经过prefetch.ClearCache(paths.ClearCache) | core/system/system.go:85 |
| 每条供给连接创建4个worker槽，由policy.threads决定启用数；没有每owner总连接/块额度 | core/net/prefetch/serve.go:75、:428；core/net/prefetch/prefetch.go:132、:366 |
| 块为4MiB，live预分配块容量；sliceFrom/snapshot/持久写盘还可能产生副本 | core/net/prefetch/cache.go:23、:77、:113、:128；core/net/prefetch/persistent.go:231 |
| disk创建与清理同锁，但start在probe后才创建disk，当前没有跨probe的cache_epoch守卫 | core/net/prefetch/prefetch.go:320、:329、:333；core/net/prefetch/persistent.go:60、:80；core/net/prefetch/cache.go:216 |

决策：首版复用上述存储和用户既有总预算，不新增流影独立store、额外512MiB额度或SimpleCache。只改准备窗口就能利用有效本地块；独立namespace若今后确有产品需求，必须同时设计预算/路径/清理，不是添加目录名即可。

清理风险：已注册disk的屏障不自动代表“正在probe尚未注册”的新任务也受保护。设计第6.3节拟增加流影请求显式携带的cache_epoch：解析前捕获，ClearCache同锁推进，持久/临时创建同锁检查，失配不走无守卫fallback；这是新增能力，不是已有保证。普通调用省略守卫保持原路径，网络不进入磁盘锁。

并发风险：4个worker是每连接上限，不能推断所有投机任务最多4块。设计第4.1节拟增加流影可选owner额度，合计4个在途媒体块/16MiB原始载体；副本、样本和codec另计。这一额度及其正式提升语义尚未实现或量测。

## 4. 规范与历史

- docs/go-migration/UI_MOBILE.md:794：既定流影布局、范围、收藏、倍率、手势和每条从头播放。
- docs/go-migration/COMMANDS.md:158：准备、预热、停止handoff及正式play契约；:475为隐私播放历史策略。
- docs/go-migration/SPEC.md:335、:408、:1202：单事件消费者、空间协议、平台/核心职责。
- .trellis/spec/shared/product-and-api.md：候选只当前服有权入库内容，不回上游网页；能力只读缓存。
- .trellis/spec/backend/quality-guidelines.md：持久缓存、准备分阶段与门禁。
- docs/lessons/network.md:425：预加载字节必须被正式播放复用；历史数字不当本任务性能数据。
- docs/lessons/ui-mobile.md:670：手势方向锁定，不能每帧重判。
- .trellis/tasks/archive/2026-10/10-21-liuying-mobile-controls/：已交付的控件/倍率/收藏契约。本任务替换播放链路，不撤销这些产品决定。

## 5. 外部API依据与未验证内容

通过文档检索核对了Android Media3官方DefaultPreloadManager概念、管理/接管和创建说明：播放器与预加载源使用兼容构建器/组件，getMediaSource不代表数据必然已准备好；首帧回调与READY分开。当前工程1.11.0的ExoPreload源码已使用这些核心能力；最新文档中的新API不能直接套入，实施以1.11.0匹配的API/源码与编译为准，不引入实验性renderer预热来叠加解码器。

官方核查入口（不作为1.11.0全部API可用的证据）：

- `https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/manage-play`：getMediaSource可能尚未完成预载，播放器接管源；管理器的remove/reset/release涉及生命周期，不能当成无副作用的取出。
- `https://developer.android.com/media/optimize/audio-focus`：实施核对目标SDK的前台焦点申请限制及失败处理，不借启动后台服务绕过限制。
- `https://developer.android.com/guide/components/activities/activity-lifecycle`、`https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner`：实施核对Activity与进程级事件的时机差异；设计不依赖进程级事件作为即时停播唯一来源。
- Context7的`/androidx/media`检索补充了BasePreloadManager和Builder构建配置的官方release分支源码；它未给出1.11.0特定标签证明，具体移交/释放次序保留为实施准入项。

未安装或验证抖音内部实现；本方案描述目标体验及拟采用技术，不声称复刻其内部架构。未连接实服或真机，未取得切片/首帧/内存基线；双槽SurfaceView预渲染、低内存降级、清理跨代屏障和慢Stopped连滑均为实施技术验证项。

## 6. 规划独立审查

需求与技术两次只读审查已返回；本轮仅修订任务文档。已明确循环与退出重进的边界、实际实时源例外、候选/准备窗口验收，并补充cache_epoch、慢停止屏障有界等待、MediaSource移交失败所有权、投机读取额度、系统事件接线和可重复采集步骤。这些补充均为拟实施契约，未冒充现状或性能通过。

修订后另做一次只读一致性复核，未发现阻塞或明确矛盾；主线程随后完成PRD去重收敛及结构/引用检查。审查结论仅针对规划，不能替代实现审查或真机验收。

上下文注意：frontend/android.md超过单文件自动注入上限。后续实施/审查不能只信截断内容，须按“原生流影”“Media3”“停止屏障”“下一集准备”等实际标题/符号定位并完整补读相关段落；本研究的锚点和结论不取代正式规范原文。

## 7. 实施核验补记

本节记录实施后的变化，前述“当前/拟实现”保留为规划时快照；最终门禁和未验项见../validation.md。

- MediaStationGo只读基准为25babab9；核对的`internal/handler/emby_routes.go`、`emby_routes_lowercase.go`、`emby_playback.go`、`internal/service/emby_playback.go`及`internal/model/emby_item_types.go`无相关未提交差异。其它工作树变化未处理，未修改服务端。
- 标准PlaybackInfo仍沿现有大小写路由/handler进入`PlaybackInfoWithOptions`；它校验所选媒体源/轨道后返回MediaSources及新的PlaySessionId。没有为流影增加服务端取流入口，Sessions上报继续原实现。这里只是源码核验，未连接真实服务。
- 当前服务端媒体模型未提供IsInfiniteStream；客户端缺失按false处理，但RunTimeTicks未知仍禁止未来内容预载，不从“直播”分类猜测实际实时源。
- Android依赖仍为Media3 1.11.0。固定AAR的实际签名/字节码显示：LoadControl的Java default方法不能只靠Kotlin委派；须显式转发生命周期、选轨、back-buffer和启动方法。仅编译曾通过，真实实例启动测试才复现“not implemented”，修复后通过。没有套用其它release分支的新API或升级依赖。
- 已落实专用三项池、双槽、完整身份守卫、cache epoch及四块投机额度；原单槽与缓存默认参数保持。32MiB样本目标和16MiB原始投机载体不是进程内存硬限，codec/副本峰值没有实测。
- Cancel复活问题的根因是只在同generation拒绝取消context，更高代会重建context；已改为先拒绝关闭owner，再处理有效owner的范围递增。两条关闭owner测试先失败后通过，包含迟到解析、Prepare、Play及同owner Open。
- 只读复核与最终本地门禁已执行；真机双槽、低资源、真实首帧/声音及性能基线仍未完成，不将规划审查或注入事件当作设备证据。
