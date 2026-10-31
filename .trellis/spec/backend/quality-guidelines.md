# 核心质量检查

## 编码与生命周期

- Go 修改按现有包风格并 gofmt，不增加未请求的抽象或静默回退。
- handler 的 context.Context 沿调用链传递，失效请求不得继续覆盖当前状态。
- 图片通道：Emby 登录/重登使用 `AllowEmbyDefault` 转发尺寸；第三方图床使用 `AllowDefault` 保留签名 query。同 URL、同请求头合并回源，单等待者取消不影响其他等待者，全部离开或服务关闭才取消；排队取得名额后复查缓存。
- Emby `/Items/.../Images/...` 的HTTP 200一像素PNG属于缺图占位，不能作为成功图片缓存或供图；回源时返回加载错误让宿主回退，命中旧占位时只清除该key的内存/磁盘缓存。`/img-cache`仍只返回未命中，不得为修复轮播而回源；其他图床的一像素图片保留原行为。`TestEmbyPlaceholderFailsAndRecovers`、`TestOldEmbyPlaceholderCacheEvicts`和`TestOtherOnePixelImageKeepsCaching`覆盖恢复和边界。图片HTTP状态、可解码尺寸、代理缓存与最终呈现分开核实，不能仅凭Primary请求或上游200判定图片已展示。
- 播放问题先核查 libmpv 配置、日志与时序；不复制播放器已经提供的能力。
- 修共享函数前核查全部调用者，包括不同宿主与入口。

## FFI / 绑定

- C 字符串入参立即复制；lp_next_event 返回的 C 字符串由宿主 lp_free 释放。
- 事件泵只允许单消费者，不在页面另启动 lp_next_event 消费者。
- `core/ffi/abi.go` 的 LP_ABI 是唯一真值；导出签名、事件信封、错误形状或 surface 语义的破坏性变化才递增。
- 生成器读取 ABI 与命令表，生成 C#/Kotlin/Swift 绑定；命令名是字符串，必须跑命令表、Go 和三端绑定的五方对账。

## 门禁与证据

- 日历无需赞助或订单：`sync.traktCalendar` / `sync.bangumiCalendar` / `sync.calendarLibrary` / `sync.calendarDue` 不检查旧订单字段。个人 Bangumi 日历始终要求账号，只返回在看条目；公共日历使用 `emby.extCalendar`。参数校验和提醒开关保留；`TestCalendarWithoutSponsorOrder` / `TestBangumiCalendarRequiresAccount` / `TestPersonalCalendarWithoutSponsorOrder` 验证无订单、无账号不出网和真实 HTTP 个人条目过滤。
- 核心变更：`bash scripts/check-core.sh`，包含 go vet、无缓存 go test、出库、FFI 头文件、C# 核心契约与差分对账。
- 命令表或绑定变更：`bash scripts/check-bindings.sh`。
- Emby 相关改动核查 `core/cmd/diffcheck/corpus/` 的既有差分语料及相关测试；真实 API 联调按 [基准](../shared/product-and-api.md) 执行。
- 回归先证实原故障会失败，再验证修复；未执行的设备播放、服务端联调必须说明。
- 依赖MSG扩展能力的跨包测试须在隔离配置后，通过真实登录或普通刷新命令 `emby.extCapabilities(refresh=true)` 建立同一服务器/用户的缓存；仅在HTTP mock中提供capabilities响应不等于缓存已就绪。保留图片授权、弹幕合并/单路失败及取消断言，不得靠放宽断言或绕过能力检查修绿。

依据：[ABI](../../../core/ffi/abi.go)、[FFI](../../../core/ffi/main.go)、[核心门禁](../../../scripts/check-core.sh)、[绑定门禁](../../../scripts/check-bindings.sh)。

- 聚合总览每服的统计与继续观看共用单服总时限（现有20秒），不能只依赖连接/响应头/响应体空闲超时；持续有数据的慢请求也须受总时限约束。单项失败保留另一项及健康服结果；继续观看超时返回可见错误。回归使用真实HTTP挂起请求，不仅给父ctx加短deadline（后者无法验证handler自身是否设置超时）。

- mpv起播字幕在 SubLang/SubRegex 均为空时优先明确简体标记，与Android `preferredTrackIndex` 一致；显式正则/语言及关闭字幕始终优先，不把chi/zho推断成简体。外挂字幕沿用FILE_LOADED时序；`Test字幕默认简体不覆盖显式偏好` 覆盖默认、正则、语言和关闭。

## 场景：详情预热取消与正式起播

### 1. 范围 / 触发
离开详情、新详情预热、正式起播和停播必须取消旧视频预热；默认后台模式包含尚未完成的 PlaybackInfo。return_media 的媒体查询由页面命令 context 所有，停止/替换预热不取消查询；查询完成仍返回版本，但已取消的预热不得恢复或替换正式播放代理。

### 2. 签名
`prefs.preloadItem`、`prefs.preloadCancel`、`Play` / `PlayResolve`、`Stop`；内部 `cancelWarm()` 与 `proxyFor(ctx, upstreamURL, prefs, readAhead)`。

### 3. 契约
默认预热模式保持原响应；可选 return_media 模式见下方契约，无新增环境变量。预热拥有独立可取消 context，不能使用命令返回即失效的请求 context。取消覆盖后台取流、代理初始化、字节预热；return_media 查询使用页面命令 ctx，页面取消时终止查询。共享代理和已有缓存保留，正式播放仍独立解析 PlaybackInfo。

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

- 准备代理接管和详情预热代理复用时，原地恢复当前服务器预取开关、线程数与码率窗口；已建立连接也读取原子策略快照，最多4个worker槽，关闭预取仅单块窗口。不得重建代理或重复探测丢掉已准备字节。`TestPreparedProxyPromotesExistingStream` 挂起首块验证接管前不抢跑、接管后四路并发及完整供给。
- 两核共用StartCached；身份摘要包含固定server/user/item/source，授权token与线路不参与稳定键，持久目录再结合可信版本和总长：优先强ETag，无ETag时允许Date至少晚于Last-Modified 60秒的有效修改时间；弱ETag不回退。后续块响应须匹配同一验证器及有效范围，no-store拒绝写入。每次起播重新解析授权与探测；206范围/长度/版本校验必须先于live.feed，条件Range回200或版本变化拒绝拼接。无可信版本只会话内缓存；no-store、HLS/DASH清单不持久化。
- 4MiB块保持边收边吐与头尾固定槽；完整块附64字节位置/哈希头，临时写→Sync→关闭→rename才发布，恢复与读取校验损坏，索引不保存URL或账号原文。单片数据+元数据≤128MiB，全局默认1GiB含会话ring/持久块/索引/在途临时写，TTL7天与LRU在访问/配置时执行。
- 磁盘读写/清理/淘汰共用屏障，活动条目可安全失效重取，不能无限pin超预算；写盘失败由连接内有界ready载体继续供给，消费后释放。Close取消origin与连接并等worker退出，再关盘，保留完整持久块。清理关闭活动临时文件兼容Windows，旧句柄禁写。
- worker认领inFlight和推进fetchCursor必须同锁原子完成；否则供给端会误判被淘汰而重复下载。取数取消后已收到的完整已验证块可提交，残块不可发布。
- prefs.media_cache_bytes=0或64MiB～4GiB整数，旧配置默认1GiB；setter部分更新保留省略字段、保存失败回滚，再收紧预算。真实HTTP回归覆盖授权更新、账号/版本隔离、弱/缺失ETag、错误Range、跨进程恢复、损坏/半写、单片/全局预算、并发弱校验流、TTL/LRU、活动清理与Stop；运行prefetch/player/preload竞态检查。

- 首页缓存轮播通过本地数据通道/img-cache读取，GET/HEAD仍校验X-LP-Token与来源白名单并使用既有尺寸key；Get2L未命中404，禁止调用sharedImage。普通/img仍可回源。独立路由保证旧核心不认识新入口时不误下载。缓存候选与渲染都必须使用/img-cache，不能探测命中后改回普通/img；TestCachedImgNeverFetches与TestCachedImgKeepsAuthenticationAndOriginAllowlist覆盖回源计数、清理、磁盘/HEAD与授权。

## 首页媒体展示可靠性

Item的可选`unplayed_count_known`仅在Series且服务端当前用户UserData明确提供非负UnplayedItemCount时为真；保持旧unplayed_item_count字段，不由Played补出统计。`has_backdrop`取BackdropImageTags、`has_logo`取ImageTags.Logo，缺失时省略，旧宿主忽略可选扩展。HomeUnplayedCountPresence/HomeImageCapabilities覆盖缺失、零、正数、负数与图像来源；差分对账仍保护旧字段。CommunityRating仍为rating，未确认来源不能作为豆瓣；独立DoubanRating来自MediaStationGo豆瓣快照，核心列表与详情保持同名可选字段，不增加douban_rating别名；只透传0–10分，缺失/null/越界省略，不新增评分请求。TestDoubanRatingKeepsServerFieldName验证原字段、通用评分不变与旧响应兼容。

## 全端展示快照契约

普通区与私密区统一使用 Go 持久展示缓存；`is_adult` 只参与空间隔离，不决定是否落盘。账号、服务器与空间之间不能混用快照，统一清理同时覆盖各空间。`TestPrivateSnapshotsPersistWithSpaceIsolation` 覆盖私密磁盘恢复、账号/服务器/空间隔离及清理。迁移以用户确认的统一策略为准，不把旧宿主特例自动提升为跨端要求。

Android 手机/TV 通过 `displayBlock`，桌面通过 `CallDisplayAsync`，tvOS 通过 `MediaAPI.request(snapshot:)` 消费核心快照。宿主 BrowseCache/DetailCache/MetaCache 已移除；页面状态与图片解码仍由 UI 持有。

手机、TV、Windows/Linux共用Go `displaycache`：首页、库/收藏/搜索、筛选、详情/季/分集、人物/推荐/合集、统计及聚合的展示读取均接入核心快照，普通区与私密区均持久化。无时间过期，每个核心缓存目录最多1000条，按最近读取淘汰，单条最多512KiB；超过单条预算仍实时展示。1000条是请求结果数，不是影片数。轮播候选池也计入此预算；Android 启动后台清理废弃 detail-v1/browse-v1 目录。旧桌面 meta 目录不再读写，可由统一清理缓存移除。

缓存键按实际服务器/生效线路、用户和完整查询隔离；不存凭据、URL、文件路径、媒体版本。`server_id`在盘上仅存摘要，读时按当前账号还原。详情预览不含续播位置、收藏或已看状态。核心旧快照经既有`partial`交付，最终`result`始终为本次请求的真实结果/错误，不能将快照作为播放、续播目标、换源匹配或权限判断。

`emby.cachedItems` 只读当前身份/空间的核心轮播候选，不访问服务器；核心合并成功浏览结果和 setPlayed 的稀疏更新，池按 ID 去重与字节预算淘汰，不作为播放/权限真源。池键同样包含屏蔽版本。状态写入成功后失效当前账号/空间的请求快照，保留候选池；generation 拒绝迟到读回写，独立清理代次阻止清理前状态写入恢复候选。缓存键采用身份散列前缀和请求散列后缀，旧版本快照自然冷失效，未迁移旧宿主缓存文件。

桌面 `emby.seasonEpisodes(all=true)` 在核心按 200 条聚合完整季，默认模式仍分页；后页错误不发布半份成功结果。超过共享单条预算时仅实时返回，不持久化。回归：candidates_test.go、season_snapshot_test.go、tools/homecheck 与 tools/seekcheck --privacy-only。

各端展示调用先消费快照再应用实时结果；网络失败保留旧展示，鉴权/不存在/无权限/无效参数不能回退成成功。分页快照和实时页替换同一范围；离页/取消/切换身份拒绝迟到展示。清理推进generation、移除内存/磁盘并拒绝清理前请求回写。聚合单服错误不能覆盖成功快照；流式`source.aggregateSearch`仅缓存Emby展示行，实时行按服务器替换旧行，播放换源分支不使用快照。


## 弹幕样式契约

1. 范围：当前安装的全局Prefs与共享排版，手机提供设置UI；匹配/时间偏移保留当前内容语义。
2. 接口：player.getDanmakuStyle / player.setDanmakuStyle新增roll_lines与prevent_overlap，命令表为真源。
3. 数据：danmaku_roll_lines默认20，0隐藏滚动；danmaku_prevent_overlap默认true，旧配置缺字段沿用默认，显式0/false必须保留。
4. 校验：roll_lines只接收0–20整数，prevent_overlap只接收bool，非法值返回E_INVALID；样式命令与弹幕总开关共用锁串行，保存失败回滚Prefs，不发布新排版；总开关失败也不能遗留内存新值。
5. 排版：滚动行数同时受范围、字号、可见高度与上限约束；防重叠开启时三类共享纵向空间；有宿主实际几何时滚动按进场间距和追尾检查连续同轨，固定/跨类型及无测量旧调用仍独占到离场，拥挤略过；关闭轮流分配各类行，允许重叠，0行仍隐藏。
6. 回归：danmaku_options_test.go覆盖持久化、单项保留、失败回滚、非法值、0行、密集开关和三类互斥。
7. 正反例：宿主使用核心lane与生命周期绘制；不能把搜索多源候选当作全部已加载来源，或仅改变UI读数却不重新取得排版。

## 切核明确续播位置

`player.play` 可选 `resume_exact=true` 要求非负有限 `resume_secs`，缺失/负数/非数值拒绝；`from_start=true` 优先从零开始。切核时跳过服务端、主服、跨服续播覆盖和已看归零，但保留主服 Link 准备与上报。省略时保持原续播语义，两内核共用同一 play 链路，不用加载后 seek 补救。`primary_progress_test.go` 通过命令入口验证主服与跨服模式下零、非零、接近片尾及从头优先；`resume_test.go` 验证非法明确位置。

- 下一集准备与视频预热分阶段：prepareNext仅解析/探测，warmPrepared按完整Session和request_id匹配启停；暂停仅取消读取子context，保留target/代理/已完成块，恢复不重复ResolveStream或probe，消费/过期/取消关闭父context。TestPreparedWarmWaitsAndPausesWithoutResolvingAgain覆盖信息阶段零视频块、错误身份拒绝、暂停中断尾部请求、恢复和消费后失效；故障注入自动Warm时必须失败。

## 手机流影专用接管契约

1. **范围**：`core/player/liuying_*`为手机流影独立准备池；共享正式play/stop内部显式分支，不改变普通单槽、TV或桌面默认调用。
2. **签名**：`player.liuyingOpen/Prepare/Warm/Cancel/Play/Progress/Stop`字段以COMMANDS.md为准，Stop新增可选retain_request_id、响应retained；均经bus精确媒体分类注入私密身份。Open只接受generation=1，后续generation为1至2^31-1整数，pos非负有限，depth仅1/3/5。
3. **契约**：owner绑定完整Session、空间与单调代数；最多五项含在途，停止移交瞬间可暂存第六项；2分钟过期、request一次消费。正式Play固定exo/流影倍率/从头，复用同一target/proxy并返回实际consumed_request_id和playback_id；发布前同锁复核owner、revision、当前播放及cache epoch。同范围有效点播Stop可把代理退回准备池，正式Playing/Stopped照常；保留沿用最初解析期限，不因回滑延长。当前非空拒绝覆盖，Stop/Progress绑定report实例，不能仅凭item或PlaySessionId。
4. **校验与错误**：非隐私身份E_UNSUPPORTED；非法参数、未知/关闭owner和已有正式播放E_INVALID。整代Cancel关闭owner，更高代也不能复活；新页面必须新owner。Warm旧请求matched=false；旧Progress reported=false；旧Stop幂等应答不表示已匹配或停止新目标。准备失败不发Playing，起播错误返回宿主，不改MPV。保留拒绝（过期、清理代数、范围/身份/选版变化、实时源、禁预载、窗口满）返回retained=false并释放，默认未传保留字段仍关闭代理。
5. **正常/基本/错误**：命中原位提升且不重复取流/探测；未命中正常冷解析；普通播放提交后旧流影解析不得替换其target/proxy。有效回滑不重复PlaybackInfo/probe/已缓存完整块，过期后正常重新解析。退出取消未消费资源，正式会话仍按原停止屏障上报，不丢Stopped换速度。
6. **测试**：`liuying_test.go`覆盖七命令真实bus/scope、五项与普通单槽隔离、HTTP字节复用/重进、实时源、禁预载、迟到解析、关闭owner和旧Stop；prefetch的`speculative_test.go`覆盖四块共享额度、暂停/恢复/提升及清理前解析/probe/磁盘创建/在途块写入。新增RetainedStop/RetainedWindow/RetainHandoff测试验证同proxy、真实HTTP供给、租期不续、五项+移交一项与旧计时器隔离；SpeculativeDemotion/ConcurrentPromotion验证正式降级取消读取、恢复四块预算及并发不泄漏。并发检查需列出包与实际失败，不能用局部race替代整库结论。
7. **正反例**：错误是Cancel只关当前代、下一条迟到Prepare用更高代重新创建owner；正确是已关闭owner永远失效。缓存epoch在解析前捕获、ClearCache同锁推进、持久与临时创建同锁检查；失配不可fallback绕过。ReadControl在分配块前认领共享四块额度，取消收尾才归还，Promote唤醒等待者；已播代理退回时Demote取消在途正式/已提升投机读取；再次Warm需显式许可，Promote不提前归还已认领块。过期回调核对记录指针，不按可再次使用的request_id误删新记录；普通未传ReadControl/Epoch仍走原路径。

## 详情版本与预热共用响应

`prefs.preloadItem(return_media=true)` 返回本次 `MediaVersion[]`，使用与正式解析相同的 DeviceProfile。只等待 PlaybackInfo，视频地址探测与字节预热仍在后台；关闭预热也须返回版本，失败不可变成成功空列表。响应仅存本次任务，正式播放仍重新获取 PlaybackInfo；旧模式保持立即返回状态。手机和桌面可播详情使用该模式，旧核心返回状态对象时才回退 emby.itemMedia。桌面同一页面重绘不重复加载版本。TestDetailMedia*覆盖一次请求、首选版本、关闭预热、错误、页面取消、停止/替换预热与正式起播不打断版本查询、返回不等待视频探测与正式解析重新请求。

## 首页剧集实时选集

`emby.seriesPlaybackTarget({series_id,is_adult?})`经既有会话/空间包装执行，返回真实Episode Item，不加入displaycache白名单。按实时季顺序读取完整分页，过滤屏蔽后选首个未看集；全看完返回首集。仅明确成功的空Items数组回退剧ID，季JSON缺数组/非对象条目、HTTP失败、重复/不完整分集或取消都不得猜目标。`SeriesPlaybackTarget`相关回归覆盖跨季、全已看、空季、失败、取消、超过200集与格式错误；异常季响应测试先复现错误再修正。

## 自动弹幕候选为空

同源自动匹配按原分数降序尝试，最高候选成功返回空弹幕时继续后续达标候选；同一分集ID仅请求一次，新增回退排除标题明确属于其它季的候选；该源的一轮搜索与取弹幕共用8秒时限（手选记忆读取另有时限）。低分候选不请求，网络错误保留原有下一源回退，不放宽季集匹配。TestResolveSkipsEmptyCandidateWithinSource覆盖首空次有、重复召回与低分拒绝；该用例已在旧实现失败，实际用户来源仍需联调。宿主保留弹幕关闭状态，不因新增来源自动打开。

## 弹幕显示区域与宿主测宽

### 1. 范围
手机、TV、桌面弹幕排版和显示；不改匹配、存储格式或总开关。

### 2. 签名
`player.danmakuLayout()`回复增加`area_height: f64`；`player.setDanmakuStyle`仍部分更新，旧行数字段与0隐藏语义保留。

### 3. 契约
区域同时限制三类轨道；底部从区域下沿排起，核心分轨与宿主绘制使用相同area_height（res_y单位），整行及边距不得越界。宿主兼容缺字段回复时退回res_y。播放页调整区域一并传三类上限20，不在配置加载或单项字号更新时抹去旧偏好。

### 4. 校验与错误
沿用核心数值校验和保存失败回滚；UI仅按成功回值更新。设置读取失败禁用并提示重试；旧布局缺区域字段不应使弹幕消失。

### 5. 案例
1/4屏时滚动/顶部/底部均位于上方区域；全屏仍按全高排版。旧零行数配置加载时保留，用户改区域后解除限制；保存失败不得改变这些字段。

### 6. 回归
`TestAreaBoundsAllDanmaku`覆盖四档区域、极端字号及混合模式。Android `DanmakuLayerTest`和桌面`seekcheck --danmaku-only`验证真实字宽、居中、尾部离屏、区域及暂停重绘；界面额外覆盖慢保存/连按/拖动与菜单重开。

### 7. 错误与正确做法
错误：核心w估宽直接用于居中/离屏，或仅移动宿主底部位置而不改核心避让。正确：宿主按实际字体测宽并缓存，核心与宿主共享区域边界；未知真实字宽和视口时保留整段占轨，不能根据估宽放宽防重叠。


## 连续弹幕的测量与排轨

1. 范围：手机、TV和桌面实测排版；业务分轨仍由Go核心负责，不引入新依赖或改变语料时间。
2. 签名：`player.danmakuLayout(prepare?: bool, revision?: i64, viewport_width?: f64, widths?: f64[])`；prepare回复增加revision和needs_measurement=true，最终回复不需要再次测量。
3. 契约：prepare必须返回合并后的完整候选和最终文字(含×N)，不能先丢弃拥挤项；宿主按实际字体/字号/粗体和高度测宽，宽度与视口按res_y换算并按原候选顺序提交。排版前后检查修订，结果不写全局视口状态。
4. 校验：视口须为有限正值，widths须等长且有限非负；非法或过期为E_INVALID。间距用时间比例分别乘视口/字宽，避免有限大数的中间运算溢出。
5. 案例：同轨前条未离场时，入口及前条离场时均有半字安全间距即可接入；拥挤直接略过，不排队篡改时间戳。固定和跨类型保留存续期避让；旧无参调用兼容保守排版。
6. 回归：TestMeasuredRollReusesOccupiedLane旧整段占轨时只能接入2/16条；TestMeasuredRollPreventsLongTextCatchup与TestMeasuredMixedModesStaySeparated覆盖追尾/混排；TestMeasuredLayoutSnapshotContract验证完整候选、非法几何与过期拒绝。DanmakuGeometryTest和seekcheck --danmaku-only覆盖实际测量接线、尺寸变化、取消和迟到结果。
7. 正反例：不能只修绘制字宽却仍整段占轨，也不能按估宽放宽避碰。正确做法是完整候选实测后分轨；宿主在后台测量，换源/样式/尺寸时清旧布局并拒收旧结果，零尺寸等待、不逐帧跨FFI。

- 弹幕实测布局支持可选until（有限非负、含终点），合并先于截取，保留全部历史候选及原顺序；widths匹配同一前缀，revision仍校验。禁止仅回看最长存续期后独立重排，排轨依赖可沿历史连续传递。TestMeasuredPrefixMatchesFullLayout验证80秒慢速、混合模式、合并、顺序与防重叠开关下的首批/整集一致性。宿主先显示当前位置加30秒前缀，再复用同一测宽缓存后台补齐；全量语料和后段续播准备仍存在，不宣称已全程窗口化。

## TV 默认渲染与性能快照

1. 范围：TV发行包的MPV默认缩放，以及Android媒体信息；不改硬解、杜比软解、字幕、VO和Surface绑定。
2. 签名：宿主lp_init复用platform，经SetHostPlatform(string)在开放命令前传入player；player.opts仍无参，追加frame-drop-count/decoder-frame-drop-count字符串字段，无ABI变化。
3. 契约：仅android_tv且ensureConfDir无有效目录时注入轻量缩放、保留fruit抖动；已有自定义配置不能混入默认profile残余，Surface重绑不重设。计数来自各自Prop，不相加，不新增轮询。
4. 校验与错误：Prop不可用返回空串，客户端只显示非负整数，0是有效值；非法/缺失隐藏。既有配置过滤和写入规则不变，选项设置失败保留错误日志。
5. 案例：无自定义TV设置bilinear缩放；手机/桌面和有效自定义配置不注入；{"frame-drop-count":"0","decoder-frame-drop-count":""}仅显示渲染0帧，不伪造解码计数。
6. 验证：TestTvRendering*用真实libmpv初始化回读验证缩放、抖动、其它播放选项及自定义配置；TestPlayerOptsIncludesDropCounters经真实命令注册核验字段。宿主探针不替代Android驱动或目标电视性能验证。
7. 反例与正例：不能把菜单倍率当内核实际倍率、把未知计数补0或覆盖用户配置；应分别回读实际字段并仅为无配置TV提供默认值。
