# 产品范围与 API 兼容基准

**LinPlayer** —— 第三方媒体播放器。**只做 Emby**(2026-09-04 定的范围):
网盘、局域网源(SMB/WebDAV/FTP)、Ani-RSS 全部不做,已从代码里删净;
2026-11-01 按用户确认彻底删除本机文件浏览、插件系统、扩展组件及 Trakt / Bangumi 账号，旧本机与插件源账号在升级读取配置时删除，备份导入不恢复这些账号。
Windows 的 RIFE / DRBA 外置补帧组件、下载与安装管理及播放选项一并删除；Android 内建 OpenCL 补帧和其它画质增强保留，旧外置组件文件不自动删除。
保留 Emby 浏览播放、弹幕、下载、跨服搜索 / 换源 / 续播以及服务端提供的公共榜单和日历；Bangumi 元数据识别不是账号同步，不随之删除。
Android 手机 / TV 与桌面设置的更新入口统一放入「关于与更新」，包含版本、检查更新、更新渠道、自动检查和 GitHub 代理。tvOS 外壳不在本次修改范围。

覆盖端:**Windows / Linux / Android 手机 / Android TV**。tvOS 已授权建立最小客户端，当前开发中、正式应用未验收；iOS/macOS 暂不做。

播放引擎是 **libmpv**,不是自研解码器 —— 涉及播放的问题先想"mpv 该怎么配",别先想"我们该怎么算"。tvOS 宿主播放采用 AetherEngine，业务仍走 Go 核心。

## 0.1 ⚠️ 架构迁移:Rust 栈已于 2026-09-04 删除

技术栈是 **Go 核心层 + C#(Avalonia) Windows 外壳**。

- 目标架构、迁移方案、任务清单:[`docs/go-migration/`](../../../docs/go-migration/README.md)
- **旧 Rust + React/Tauri 栈已删除。** 当前 apps/android 是重新实现的 Kotlin/Compose 外壳。
  需要查旧实现时:`git show rust-final:<路径>`,或 `git log -- <路径>`。
- `core/**` 的注释里原先有 57 处「移植自 `crates/…`」的溯源引用,已随本次删除一并清掉 ——
  注释现在只讲**这段代码本身**为什么这么写,不再指向仓库外的东西。
- **当前 Windows / Linux / Android 手机 / Android TV 均有实现和出包流程。**
  平台状态以 README「各端状态」为准;构建通过不代表真实设备验收通过。

## 0.2 当前产品方向与 Emby API 基准

本节是用户确认的当前产品约束。历史功能清单与草稿记录已有实现,不代表必须扩大产品范围;后续方案和取舍必须遵守本节。本节不授权直接删除功能、迁移数据或改动服务端。

### 产品定位与体验优先级

- 以 **Emby 浏览与播放**为核心,参考 Yamby / Hills 这类客户端的产品方向,核心目标是**快、丝滑、稳定**。未经具体对照核实,不要臆测它们的功能或照搬界面。
- 主流程是连接服务器 → 浏览媒体库 / 继续观看 → 打开详情 / 选集 → 播放。页面、导航和默认设置优先服务这条流程,减少进入播放前的步骤。
- 保证播放正确性、安全和数据一致性后,优先启动速度、首屏速度、起播速度、滚动与焦点响应、拖动进度和切集体验,再考虑附加功能数量、复杂动画及视觉装饰。
- 弹幕、下载、排行榜和公共日历等附加能力,不得阻塞启动、媒体库首屏或起播,不得强制登录额外服务后才能完成核心播放。其初始化和数据请求应按需进行;调整现有功能入口或默认行为前需确认具体范围。
- UI 线程不得执行阻塞网络、磁盘或重计算;列表使用分页和虚拟化,图片异步加载并复用缓存。请求按当前可见内容和用户操作安排,离页或切换账号后取消失效请求,避免重复拉取、旧响应覆盖和无界预加载。
- 性能改动必须记录所涉及路径在同设备、同网络、同媒体库条件下的前后数据:冷 / 热启动、首屏可操作、点击播放到首帧、拖动进度后恢复、切集耗时,以及大列表滚动 / TV 焦点表现。记录请求数、内存和失败情况,避免以降低正确性或后台资源失控换取表面速度。先建立基线,不凭空宣称性能达标。

### API 兼容基准

- Emby API 对接以本机 **`/vol1/1000/ssd/workai/MediaStationGo` 当前实际支持的实现**为准。开始相关工作时记录其提交与相关工作树差异;本节不是完整 Emby 官方 API 的支持承诺。
- 优先核查该项目 `internal/handler/emby_routes.go`、`emby_routes_lowercase.go` 及相应 handler、`internal/middleware/emby_auth.go`、`internal/model/emby_*` 和相关测试。`web/src/pages/embyApiCatalog.ts` 可辅助查阅,与代码不一致时以实际 handler 和测试为准。
- 逐项确认 HTTP 方法、根路径 / `/emby` 前缀、鉴权方式、请求参数、分页与总数、响应字段 / 空值、错误状态,并核实播放地址、重定向、Range、字幕和播放进度上报的具体语义。
- **路由存在不等于业务能力完整。** 兼容占位、固定空数组或无内容响应不能作为真实能力启用依据;缺失或降级能力应明确呈现,不能吞错伪装成成功,也不能套用其他 Emby 服务端的假定补齐行为。
- 优先闭环登录、媒体库 / 继续观看、详情 / 季 / 分集、图片、PlaybackInfo / 媒体流、字幕 / 音轨、Sessions 播放开始 / 进度 / 停止,以及已看与收藏状态。客户端字段解析与播放决策须依据服务端实际返回。
- 服务端暂缺的接口先列出差距与客户端降级方案;不擅自修改 MediaStationGo 或引入虚构端点。参考项目路径不可用时,说明无法核实的部分,不要以一般 Emby 知识替代验证。
- 媒体库网格按 `ParentId` 浏览直属内容（`Recursive=false`），不固定 `IncludeItemTypes`，也不按库名或 `CollectionType` 猜测查询类型；内容类型与层级以服务器返回为准，避免电影库被误查成剧集列表或剧集库展开成散装分集。分页、排序与用户条件筛选仍沿用同一查询范围。
- 收藏查询使用 `Filters=IsFavorite,IsUnplayed&Recursive=true`，不固定 `IncludeItemTypes`；分页与全量入口一致，由服务端返回支持的收藏类型，避免混入不支持的类型导致整页为空。
- 涉及 API 行为的修改,需要契约回归和与 MediaStationGo 的真实联调。使用脱敏数据;mock / 单测、编译和出包通过不能替代实际登录、浏览、起播、拖动进度、续播、换集与上报验证。

- `/Users/{user}/Items` 列表返回404时可回退同服务器、同前缀的 `/Items?UserId=...`；原ParentId、类型、Fields、排序和分页参数全部保留，两次请求均鉴权并使用同一context。401/403/5xx、取消、成功空结果不触发回退，条目详情路径不改写，替代请求失败保留其真实错误。`items_route_test.go`覆盖前缀、查询、分页、鉴权、失败与取消。基准为MediaStationGo fa61d42当前工作树的同一Items handler；未验证问题服务器，不能将mock通过当成实服404已解决。
- 空季回退和接口404是不同故障分支，诊断与回归须分别覆盖；非登录请求的404仅说明内容或接口未找到，不直接指责服务器地址错误。

## Emby 跨服换源

- `source.switchCandidates` 用配置的 `Resolve` 将当前会话线路归一到账号主键，不能把当前服的备用线路当成其它候选服。
- 选中候选先切活跃账号，再刷新并核对目标会话的生效线路和用户；完成后重建首页根与目标详情，不保留旧服详情返回栈。
- 切服提交期间阻止关闭换源弹窗；会话读取失败不进入目标详情，也不保留跨服旧栈。空间变化的迟到操作仍应取消。
- 回归：`core/aggregate/sources_test.go`、手机 / TV 共用 `SwitchSourceTest`、桌面 `tools/seekcheck --discovery-only`；这些检查不代替真机或生产服务器验收。

## MSG 流影与能力缓存

`/emby-ext/v1/capabilities` 只在成功登录时请求一次，普通首页主动刷新时异步后台更新一次；其余入口只读按服务器/用户隔离的持久缓存，不使用 TTL 自动探测。刷新失败保留有效旧缓存；旧令牌迟到响应不得覆盖新账号凭据取得的能力。缓存不是播放授权，服务端仍实时检查权限。

MSG 标准 Emby 返回对所有播放器一致，不按客户端名称或 UA 隐藏库。仅 LinPlayer 隐私首页排除 `library_type=liuying` 库及 `ly-work-` 内容；聚合视界依据缓存 `liuying.enabled` 进入原生单屏随机播放，而非根据库是否存在决定能力。资料中心永远只提供资料，未入库视频不能成为候选，不读取来源播放地址，也不回退上游网页。候选来自当前用户可见有效库绑定，播放沿用标准 PlaybackInfo；状态仅收藏、已看/未看，无续播断点。支持普通/18+、舞蹈/直播、频道和曝光筛选，耗尽有界窗口再取下一页/新轮次。此次覆盖桌面及Android手机/TV，不新增tvOS入口或多屏。

### 隐私命令策略与流影回归

1. 范围：隐私空间调用本服流影、榜单及能力缓存，必须同时经过 `bus.scopeArguments` 和 Emby 命令处理器。
2. 签名：只精确放行 `emby.extCapabilities`、`emby.extLiuYingItems`、`emby.extLiuYingLabels`、`emby.extRankings`、`emby.extRankingItems`、`emby.extResolve`；不放开整个 `emby.ext` 前缀。
3. 契约：`request_scope.private=true` 固定当前服务器、用户和令牌并设置 `is_adult=true`；能力读取不出网。
4. 错误：其他扩展或跨服身份仍 `E_UNSUPPORTED`；私密能力 `refresh=true` 在命令层返回 `E_INVALID`，不发HTTP。
5. 案例：当前服流影和黄果 AI、JavRanking 榜单正常读取；榜单只使用当前服并按空间过滤来源；未知扩展、跨服和私密刷新仍拒绝。
6. 回归：`TestPrivateLiuYingExtensionScope` 覆盖精确策略，`TestLiuYingExtensionLibraryOnlyContract` 覆盖私密刷新不出网；桌面 `seekcheck --liuying-only` 经过真实HTTP/FFI验证缓存只读、联合筛选和取消。手机/TV测试不能替代这条核心入口链路。
7. 禁止仅直接调用 handler 或使用页面替身测试判定隐私接线完成；正确做法是补带 `request_scope` 的策略测试和真实命令通道集成检查，防止业务实现存在却被统一策略提前拦截。

### 普通收藏与流影收藏隔离

1. **范围**：普通收藏与流影收藏独立展示，不能因首页已隐藏流影库就假定跨库收藏也隔离。
2. **入口**：`emby.listFavorites` 的分页和全量结果均复用 `withoutLiuYing`；流影仍用 `emby.extLiuYingItems(favorite=true)`，不改收藏写入。
3. **契约**：排除 `LibraryType=liuying` 或 `ly-work-` ID；`next_index/has_more/total` 保留服务端原始分页语义，total不是过滤后的精确数量。普通收藏缓存键使用 `|without-liuying|unplayed` 后缀隔离旧混合快照。
4. **校验与错误**：请求错误照常返回；过滤空页但 `has_more=true` 时仍允许续页，不伪装成全部为空，不删除或迁移收藏数据。
5. **案例**：混合页只显示普通收藏；纯流影页可继续读取后续普通收藏；流影专用查询仍保留流影项与收藏状态。
6. **测试**：`Test收藏排除流影且保留原始分页` 覆盖两类身份、状态与分页/全量；`TestFavoritesIgnoresLegacyMixedSnapshot` 验证旧快照不读取、不发partial且新快照可保存；`TestLiuYingExtensionLibraryOnlyContract` 验证专用收藏返回。
7. **反例与正例**：只在界面删卡片、用过滤后数量推进游标或继续回显旧缓存均不完整；正确做法是核心统一过滤、保留原始游标，并隔离旧展示缓存。


### 未看完收藏与历史已看

1. 范围：普通 Emby 收藏只显示未看完，历史已看固定当前服务器/当前账号，跨媒体库但不跨服；流影收藏独立不变。
2. 签名：`emby.listFavorites` 保留数组/分页双契约；`emby.listWatched(type, start_index?, limit?, sort?, sort_by?, sort_order?, is_adult?)` 返回 `{items,total,next_index,has_more}`，type 仅 Movie/Series。
3. 契约：收藏 Filters=IsFavorite,IsUnplayed；已看 Filters=IsPlayed + IncludeItemTypes，不设 ParentId。Series 明确未看集数优先于 Played，缺统计不按默认零推导完成。完成后不写取消收藏，新增未看集后实时刷新恢复未看完。
4. 校验与错误：非法类型 E_INVALID 且不出网；原始游标在屏蔽/流影/状态复筛前计算，过滤空页不表示末页。缓存按命令/类型/页/身份隔离，收藏新后缀不读取旧全部收藏快照。
5. 案例：半看电影留收藏；当前上线集全看完的剧进入已看，新集后退出；未收藏的已看作品同样显示。源收藏、源历史及浏览播放命令已移除，旧本地收藏文件不自动删除或转为已看。
6. 测试：`TestFavoritesHideCompletedWithoutChangingCursor`、`TestWatchedPageCurrentUserTypeAndNewEpisode`、`TestFavoritesIgnoresLegacyMixedSnapshot`、`TestWatchedSnapshotsSeparateTypeAndIdentity`；手机 `watchedEntryUsesCurrentServerAndOnlyMovieSeriesCategories`、`watchedFilteredEmptyPageKeepsServerCursor`。真实服务器新增集后的更新仍需联调。
7. 正反例：不能将本地播放历史改名作为全部已看，不能给普通 Items 传 IsPlayed=false 代替 Filters；应使用服务端筛选后分页，再按可信状态复筛。服务端基准为 MediaStationGo 4097a299 工作树，未修改该仓。
