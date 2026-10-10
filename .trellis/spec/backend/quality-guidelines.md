# 核心质量检查

## 编码与生命周期

- Go 修改按现有包风格并 gofmt，不增加未请求的抽象或静默回退。
- handler 的 context.Context 沿调用链传递，失效请求不得继续覆盖当前状态。
- 图片通道：Emby 登录/重登使用 `AllowEmbyDefault` 转发尺寸；第三方图床使用 `AllowDefault` 保留签名 query。同 URL、同请求头合并回源，单等待者取消不影响其他等待者，全部离开或服务关闭才取消；排队取得名额后复查缓存。
- 播放问题先核查 libmpv 配置、日志与时序；不复制播放器已经提供的能力。
- 修共享函数前核查全部调用者，包括不同宿主与入口。

## FFI / 绑定

- C 字符串入参立即复制；lp_next_event 返回的 C 字符串由宿主 lp_free 释放。
- 事件泵只允许单消费者，不在页面另启动 lp_next_event 消费者。
- `core/ffi/abi.go` 的 LP_ABI 是唯一真值；导出签名、事件信封、错误形状或 surface 语义的破坏性变化才递增。
- 生成器读取 ABI 与命令表，生成两端绑定；命令名是字符串，必须跑四方对账。

## 门禁与证据

- 核心变更：`bash scripts/check-core.sh`，包含 go vet、无缓存 go test、出库、FFI 头文件、C# 核心契约与差分对账。
- 命令表或绑定变更：`bash scripts/check-bindings.sh`。
- Emby 相关改动核查 `core/cmd/diffcheck/corpus/` 的既有差分语料及相关测试；真实 API 联调按 [基准](../shared/product-and-api.md) 执行。
- 回归先证实原故障会失败，再验证修复；未执行的设备播放、服务端联调必须说明。

依据：[ABI](../../../core/ffi/abi.go)、[FFI](../../../core/ffi/main.go)、[核心门禁](../../../scripts/check-core.sh)、[绑定门禁](../../../scripts/check-bindings.sh)。

- 聚合总览每服的统计与继续观看共用单服总时限（现有20秒），不能只依赖连接/响应头/响应体空闲超时；持续有数据的慢请求也须受总时限约束。单项失败保留另一项及健康服结果；继续观看超时返回可见错误。回归使用真实HTTP挂起请求，不仅给父ctx加短deadline（后者无法验证handler自身是否设置超时）。

- mpv起播字幕在 SubLang/SubRegex 均为空时优先明确简体标记，与Android `preferredTrackIndex` 一致；显式正则/语言及关闭字幕始终优先，不把chi/zho推断成简体。外挂字幕沿用FILE_LOADED时序；`Test字幕默认简体不覆盖显式偏好` 覆盖默认、正则、语言和关闭。

## 场景：详情预热取消与正式起播

### 1. 范围 / 触发
离开详情、新详情预热、正式起播和停播必须取消旧预热，包括尚未完成的 PlaybackInfo；防止晚到请求抢带宽或替换正式播放代理。

### 2. 签名
`prefs.preloadItem`、`prefs.preloadCancel`、`Play` / `PlayResolve`、`Stop`；内部 `cancelWarm()` 与 `proxyFor(ctx, upstreamURL, prefs, readAhead)`。

### 3. 契约
命令字段与响应不变，无新增环境变量。预热拥有独立可取消 context，不能使用命令返回即失效的请求 context。取消覆盖取流、代理初始化、字节预热；共享代理和已有缓存保留，正式播放仍独立解析 PlaybackInfo。

### 4. 校验 / 错误
缺 item_id → EInvalid；预热关闭 → skipped；取消后的取流错误不记失败日志、不发 preload.done。代理初始化失败仍沿用直连回退。

### 5. 正常 / 基础 / 错误案例
正常：同流起播复用代理和缓存；基础：转码继续直连；错误：旧取流在取消后返回，不得创建或发布替代代理。

### 6. 必要测试
真实 HTTP 挂起 PlaybackInfo，分别从离页、正式起播、新详情取消，放行旧响应后断言旧流请求为零、正式代理未替换、缓存文件未删。C27 验证取消后句柄复用；运行 player / preload / prefetch 竞态检查。模拟测试不能证明真机首帧收益。

### 7. 错误与正确做法
错误：只 `preloader.Cancel()`，晚到的 `Warm()` 会重置取消标志。
正确：取流传入独立 warmCtx，关键阶段复查 `ctx.Err()`；取消后等待在途代理创建退出，正式起播再继续。禁止为取消预热而关闭已发布的共享代理。

## 运行期轨道可选语义

`player.tracks` 的 `forced` 来自MPV track-list同名键，以`*bool`和`omitempty`透传：缺失/null为未知且省略，显式false保留。新增可选JSON字段不改变ABI或命令参数；既有default/selected/external不在该规则的本批改动范围。`TestParseTracksForcedJSON`验证真实解析→JSON，禁止把缺失属性补成false。

## Android内核缓冲目标

`Prefs.buffer_target_bytes`为0自动或64～512MiB整数，get/setPlaybackPrefs透传，非法输入拒绝且不改其它字段；保存失败恢复命令前内存偏好。旧/越界配置回自动，未知配置键继续保留。Android的loadWith和本地playFile共用commandLoad，本片demuxer-max-bytes与续播start拼在同一options槽位，回自动不覆盖原conf；桌面platformBufferTarget返回0。Test播放缓冲目标、TestBufferTarget配置兼容、Test缓冲目标兼容两种loadfile覆盖此契约。

## 持久共享媒体缓存

- 两核共用StartCached；身份摘要包含固定server/user/item/source，授权token与线路不参与稳定键，持久目录再结合强ETag和总长。每次起播重新解析授权与探测；206范围/长度/版本校验必须先于live.feed，条件Range回200或版本变化拒绝拼接。无可信版本只会话内缓存；no-store、HLS/DASH清单不持久化。
- 4MiB块保持边收边吐与头尾固定槽；完整块附64字节位置/哈希头，临时写→Sync→关闭→rename才发布，恢复与读取校验损坏，索引不保存URL或账号原文。单片数据+元数据≤128MiB，全局默认1GiB含会话ring/持久块/索引/在途临时写，TTL7天与LRU在访问/配置时执行。
- 磁盘读写/清理/淘汰共用屏障，活动条目可安全失效重取，不能无限pin超预算；写盘失败由连接内有界ready载体继续供给，消费后释放。Close取消origin与连接并等worker退出，再关盘，保留完整持久块。清理关闭活动临时文件兼容Windows，旧句柄禁写。
- worker认领inFlight和推进fetchCursor必须同锁原子完成；否则供给端会误判被淘汰而重复下载。取数取消后已收到的完整已验证块可提交，残块不可发布。
- prefs.media_cache_bytes=0或64MiB～4GiB整数，旧配置默认1GiB；setter部分更新保留省略字段、保存失败回滚，再收紧预算。真实HTTP回归覆盖授权更新、账号/版本隔离、弱/缺失ETag、错误Range、跨进程恢复、损坏/半写、单片/全局预算、并发弱校验流、TTL/LRU、活动清理与Stop；运行prefetch/player/preload竞态检查。

- 首页缓存轮播通过本地数据通道/img-cache读取，GET/HEAD仍校验X-LP-Token与来源白名单并使用既有尺寸key；Get2L未命中404，禁止调用sharedImage。普通/img仍可回源。独立路由保证旧核心不认识新入口时不误下载。缓存候选与渲染都必须使用/img-cache，不能探测命中后改回普通/img；TestCachedImgNeverFetches与TestCachedImgKeepsAuthenticationAndOriginAllowlist覆盖回源计数、清理、磁盘/HEAD与授权。

## 首页媒体展示可靠性

Item的可选`unplayed_count_known`仅在Series且服务端当前用户UserData明确提供非负UnplayedItemCount时为真；保持旧unplayed_item_count字段，不由Played补出统计。`has_backdrop`取BackdropImageTags、`has_logo`取ImageTags.Logo，缺失时省略，旧宿主忽略可选扩展。HomeUnplayedCountPresence/HomeImageCapabilities覆盖缺失、零、正数、负数与图像来源；差分对账仍保护旧字段。CommunityRating仍为rating，未确认来源不能作为豆瓣；独立DoubanRating来自MediaStationGo豆瓣快照，核心列表与详情保持同名可选字段，不增加douban_rating别名；只透传0–10分，缺失/null/越界省略，不新增评分请求。TestDoubanRatingKeepsServerFieldName验证原字段、通用评分不变与旧响应兼容。
