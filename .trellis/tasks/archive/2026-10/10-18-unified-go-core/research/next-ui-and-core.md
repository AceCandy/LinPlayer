# tvOS 对齐与后续核心收拢调研

日期：2026-10-18。范围：用户要求调研后续 UI 对齐与业务收拢；只读核查当前工作树，不改产品代码、不打包、不安装。基线有既有未提交改动，本报告中的行号对应当前工作树。

## 结论

展示持久缓存统一完成，不等于候选筛选、播放队列、私密命令策略及页面刷新通知都已统一。下一步优先补齐确定的产品差距，同时分切片收拢业务，不能为了统一再造一套通用页面框架。

Android TV 基准是 TvRoot → PhoneRoot(tv=true) → ui/pages：`apps/android/app/src/main/kotlin/xyz/linplayer/app/tv/TvApp.kt:97`、`PhoneRoot.kt:340,367`。旧 `tv/DetailPages.kt` 和 `tv/SettingsPage.kt` 不作为正式浏览/设置的对齐依据。

## tvOS：确定差距与验收项

### 1. 首页轮播（优先）

- `apps/tvos/Sources/BrowseViews.swift:270`：`heroCandidates(resume + libraries.flatMap { latest[$0.id] ?? [] })`，再筛有图、取前6项。当前不读 emby.cachedItems，合集也未进入候选。
- Android 正式 `ui/pages/HomePage.kt:168` 合并当前栏目及核心候选；`data/CachedHomeImages.kt:83` 负责已看/续播排除、同剧去重等规则。tvOS `MediaAPI.swift:53` 又实现一份几乎相同筛选。
- 建议：统一候选筛选规则与候选来源，再接 tvOS；只读缓存不能触发全库扫描。图像解码、图片按需加载、轮播位置/计时/焦点暂停留在宿主。海报数量与布局限制可以各端不同。
- 验证：浏览非首页媒体库后能成为候选；已看/续播及所属剧排除；空间切换隔离；只有0/1项时不假装轮播；焦点、滚动、后台时暂停，返回不乱跳。
- 5秒推进与焦点暂停已经存在（BrowseViews.swift:245），不能再称为“轮播计时没实现”。

### 2. 详情内容与操作（优先）

- tvOS `BrowseViews.swift:559`：固定560点头图，标题后排元信息、简介、收藏/已看、版本/音轨/字幕资料，再排播放按钮；信息多时首屏播放操作可能靠后。此为布局风险，尚无本批真机复现。
- Android `ui/pages/DetailPage.kt:799`：类型、标签、工作室可跳筛选（工作室使用ID），只在实时结果且 filters 能力允许时可点。tvOS `BrowseViews.swift:569` 仅将类型拼成文本，未呈现同等标签/工作室筛选跳转。
- 建议：先补真实资料与筛选交互，再调整头图、元信息和主操作的层级；保留缓存只预览、实时才能操作的边界，不机械换算手机像素。
- 验证：电影、剧集、分集各一例；长简介、多版本、缺评分/缺图；筛选ID与能力限制；从详情返回原列表卡片。

### 3. 顶栏与滚动（已有实现，待真机）

- `ProductViews.swift:68,89` 默认 selectOnFocus=true，onChange(focused)执行栏目动作；服务器入口显式关闭。符合用户最新要求，不因 Android 用点击选择就改回去。
- `ProductViews.swift:15` 保留已访问栏目；首页是 LazyVStack/横向LazyHStack，`BrowseViews.swift:310` 观察滚动状态以暂停轮播。
- Android `tv/SharedBrowse.kt:41` 有导航entry焦点标记；tvOS 首页横轨目前主要依赖系统焦点与视图存续。不能从源码证明系统恢复失效，也不能据此宣布丝滑。
- 下一次设备验证记录：首页滑到底再向上、轨道末尾进详情返回、切栏目再返回、加载新轨道时的焦点和滚动位置。复现后再决定是否需要稳定高度、显式焦点锚点或其它修复，不先加自定义滚动算法。

### 4. 设置（通用项接线与平台差异分开）

- 正式 Android `ui/pages/SettingsPage.kt:132` 与 tvOS `ProductViews.swift:473` 已采用相似的“播放与外观/网络/内容与数据/扩展与账号/应用”分组。不能用旧TV九栏设置页来判定分组不一致。
- tvOS 播放器设置 `ProductViews.swift:559` 仅音轨语言、字幕语言及字幕开关；Android `SettingsPage.kt:429` 有片头、片尾、自动跳过。tvOS 播放中读取默认倍速与音轨/字幕正则，但此设置页没有相应编辑入口（PlaybackController.swift:142,270,285）。
- tvOS 当前有 Aether 章节面板（PlayerView.swift:161），但没有接核心片头片尾识别/手动区间流程，章节列表不等价于跳过功能。
- 建议先核对通用偏好的读取→保存→实际生效闭环，再加入口；不能只增加可保存的开关。mpv.conf、硬解选项不直接照搬；备份/文件、网页/插件及弹幕分别需要平台能力方案，现阶段不承诺等价支持，也不擅自删掉需求。

## 业务收拢：建议切片

### A. 私密请求规则与状态刷新通知（高优先级）

- Android `data/AppState.kt:179`、桌面 `Core/BrowseSpace.cs:17`、tvOS `MediaAPI.swift:371` 各自维护命令限制与身份参数注入。
- 已有差异：Swift额外禁止 emby.ext 前缀；Android/桌面没有同样的显式前缀规则。Swift覆盖传入server/token/user_id，Android/桌面私密时检查是否与当前身份冲突。该差异不是已经证实的隐私漏洞，但说明统一策略有价值。
- 桌面 source.* 前缀已包含 source.aggregateSearch，不能因后面filtered名单也出现此命令就误判为未禁止。
- 状态通知：AppState.kt:211、桌面 CoreClient.cs:152 识别三种写命令；Swift MediaAPI.swift:400 还识别setBlocked。Go已做快照失效，宿主仍重复维护刷新事件映射。
- 方案：先列实际允许/拒绝及身份矩阵，再将业务判定和带身份/空间的失效范围集中核心。保留宿主取消、路由重建、页面刷新和迟到回调屏障。不要直接删除宿主守卫或粗暴用全局开关影响并发普通/私密请求。
- 验证：三端共用命令矩阵；错误身份、旧空间、失败写入不通知；核心事件与命令结果只刷新一次；非活跃页面返回补验。

### B. 当前季播放队列（高优先级）

- Android `ui/player/SeasonPlayback.kt:10`：查季/剧、季号过滤、校验当前集、排序；分页仍在 `data/AppState.kt:223`。
- tvOS `MediaAPI.swift:433` 重复同一业务并自行分页。
- 桌面 `Views/PlayerPage.cs:1111` LoadEpisodes也自行查详情、分页、过滤、定位下一集。
- Go `core/emby/commands.go:183` 已有 seasonEpisodes(all=true)，但只是给定parent的全量列表；没有替代这些“当前条目→当前季”的规则。桌面详情已用全量命令，不代表播放页也迁完。
- 已见边界不一致：Swift在累计数>=total时结束，total=0也终止；Go全量在total>0才按总数终止。Android有重复ID检测，桌面播放页没有同等检测，Go新全量循环也未做重复ID/无进展检查。
- 方案：复用核心现有分页能力建立当前季队列决策，统一季号回退、排序、当前集校验和重复页/错误总数保护；各端保留选集界面、倒计时及实际切集停止屏障。详情渐进分页不要为复用而改成等待全季。
- 验证：超过200集、缺季ID、未知总数、重复页、空页、当前集缺失、后页失败、取消、同季末尾不跨季。迁移前先补防重复分页测试。

### C. 音轨/字幕偏好决策（中高优先级）

- Go `core/player/applyprefs.go:62` chooseTracks/pickTrack 与 Swift `MediaAPI.swift:228` TrackPreference.preferred 重复正则优先、语言匹配、默认简中和字幕回退。
- Go使用项目media.PickIndex，Swift使用NSRegularExpression；应以同一组规则测试比较，不假定两套正则语义完全一致。
- 方案：核心做“标准化可选轨表+偏好→选择/关闭/保持”的纯决策，宿主保留真实引擎轨ID、可切换性、手选优先、轨表晚到与切换执行。复用chooseTracks相关逻辑，不让Go直接操纵Aether。
- 验证：无匹配、非法正则、字幕关闭、已有选中、同语言多轨、版本变化、外置字幕与不支持格式；mpv/Media3/Aether分别验证实际选轨结果。

### D. 轮播候选策略（与UI首页一起完成）

候选持久池已在Go，但“排除已看与续播、排除其所属剧、同剧去重”的策略还在 Kotlin/Swift各一份。宜在现有displaycache/Emby边界复用数据并统一筛选，不另建首页聚合框架。渐进展示、栏目可见性和图片缓存解码继续留UI。

## 不应重做的部分

- tvOS已调用player.play走Go解析版本/续播/地址（MediaAPI.swift:456），不是另写PlaybackInfo协议。
- tvOS已通过emby.reportProgress上报（PlaybackController.swift:319）；核心transport.go:89使用currentReport并检查item_id，再调用report.progress。不能误判为Swift绕开核心直接上报，也没有本轮证据证明双上报。
- 配置已有核心默认值与校验，不建议再造一套配置系统或通用动态设置页面。
- 首页/详情各区按需加载、焦点、滚动、图片解码及Aether/Media3调用继续属于宿主。

## 文档漂移与后续门禁

`.trellis/spec/frontend/state-management.md:69,73` 仍提旧BrowseCache/DetailCache/MetaCache及“私密元数据只存内存”。与用户已确认的普通/私密统一持久化及当前代码不符，应在下一次实施前同步纠正，不能据此回退产品行为。本轮只记录研究，不更改规范或产品代码。

建议实施顺序：先补核心分页边界并统一当前季队列；同时推进确定的详情筛选与通用设置接线；随后统一候选策略并补tvOS首页；再收拢私密策略/失效事件与选轨决策。私密策略需先形成完整测试矩阵，不能简单移动字符串名单。

若优先解决眼前电视体验，第一交付切片可选详情+首页；滚动/焦点优化须先拿真实设备复现和前后对照。源码不应宣称视觉问题已解决。

## 本轮验证范围

三路只读探索后主线程抽查实际源码，排除了旧TV设置页、顶部聚焦行为误判、source.aggregateSearch限制误判和进度上报误判。未改产品代码，未运行编译/测试、未访问真实服务器、未出包或安装。未启动服务。源码确认只能支持职责/接线结论，不能替代真实电视视觉、滚动及播放验证。
