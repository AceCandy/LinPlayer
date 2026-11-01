# Apple TV 与当前 Android TV：功能、UI 差距及实施边界

## 基线与结论

- 用户要求：检查当前 Apple TV 与 Android TV 差距，在功能和 UI 上向 Android TV 完整对齐。
- 审查基线：`91a3aa85`，本轮开始工作树干净。Android TV 正式入口是 `TvRoot → PhoneRoot(tv=true) → ui/pages`；不能使用已删除的旧 TV 浏览页或 10 月 24 日截图推断当前效果。
- 最新 Android TV 已包含 10 月 27–31 日浏览、焦点、详情、搜索、设置和播放器改动；tvOS 正本主要记录到 10 月 24 日。两者“页面同名”不代表布局、操作或能力相同。
- 本文是源码审查和实施记录，不是整机验收。本轮已提交工作树中的产品代码改动；没有出包、安装、提交或推送。
- `docs/DESIGN.md` 描述 Apple 官网营销视觉，不是本应用电视界面的设计权威。本任务按当前 Android TV 实现、`UI_TV.md` 最新修订和 tvOS 平台约束判断。

## 已有能力：保留并回归

tvOS 已有登录/多服务器与线路、首页/媒体库、收藏分类、服务端历史已看、独立观看历史、搜索、详情/人物/合集、普通排行榜/日历、基础播放、音轨/文本字幕、倍速/画面/章节、同季上下集、自动跳过、进度上报及隐私请求隔离。不能重复开发这些基础路径。

- `ProductViews.swift:339–345,401–404` 已经使用 `emby.listWatched`，支持 Movie/Series；`:436–463` 的 `emby.watchHistoryList` 是另一项仍有效的本机观看历史能力。
- `BrowseViews.swift:751–757`、`ProductViews.swift:382–385` 已有临近末尾焦点触发续页，同时保留手动按钮；差距是触发/呈现，不是“完全没有分页”。
- `PlaybackController.swift:218–249` 已有章节区间读取和自动/手动跳过；缺失的是新的标记编辑与播放内开关等交互。
- Android TV 隐藏下载：`PhoneRoot.kt:340–343`、`HomePage.kt:1000`，当前 TV 详情也按 TV 条件排除下载。tvOS 无下载不列为新增功能需求。
- 本机浏览、插件、外置扩展组件及 Trakt/Bangumi 账号已经下线，不从旧对齐清单恢复。

下文未加目录前缀的 Swift 文件均在 `apps/tvos/Sources/`，Kotlin 路径相对于 `apps/android/app/src/main/kotlin/xyz/linplayer/app/`。

## 功能差距

| 编号 | Android TV 当前行为 | tvOS 当前差距与依据 | 实现与验收要求 |
|---|---|---|---|
| F1 隐私导航 | 聚合页保留，仅显示本服流影和成人榜单；设置入口隐藏 | `ProductViews.swift:104–109` 私密时隐藏聚合、仍显示设置 | 保留当前服聚合入口，匹配设置显隐；分支必须先于普通聚合缓存/请求，验证零跨服请求与退出恢复 |
| F2 流影 | `ui/pages/AggregatePage.kt:87–101` 私密入口；`NativeLiuyingContent.kt:103–108` 拒绝普通空间并只读能力缓存 | `AggregateView` 只有普通榜单、日历、跨服续播；Swift 无流影页面 | 原生单屏播放、筛选、前后切换、收藏分页指定点播、循环/连播、静音、独立倍率及两页有界窗口。普通 feed 不等于普通空间，不在普通空间新增入口 |
| F3 弹幕 | 自动匹配/搜索/选集、更换、开关、延迟、区域/透明度/字体/速度、更多样式、热力图 | `PlayerView.swift:224–241` 无入口；Swift 无 `danmaku.*` 消费 | 复用核心 resolve、generation、布局与热力图；宿主负责实测文字宽度和同步绘制，换集/离页清理，不能只加设置菜单 |
| F4 复杂字幕 | MPV/Media3 轨道、字幕样式及格式支持 | `PlaybackController.swift:112–116,138–154,382–394` 过滤文本 codec，遇非纯文本 cue 关闭字幕；详情也筛掉复杂格式 | 保留音轨/字幕身份和时轴。PGS 映射视频有效区域及字幕画布，支持同时多 cue；ASS 需完整渲染而非输出原始事件字符串或剥除特效。详见下节 |
| F5 手动片头片尾 | `tv/TvPlayerParts.kt:687–720` 本季/本集、两个常用标记、自定义区间折叠、清除和继承说明 | tvOS 只有 `chapterInfo` 与跳转，没有 `getSkipRange/setSkipRange` | 保持核心作用域与尾部偏移语义；采样点击时真实位置；保存/刷新期间禁止自动跳转；保存不 seek；刷新失败撤下旧区间并可重试 |
| F6 更多与媒体信息 | `TvPlayerParts.kt:656–683,769–779` 集中画面、版本、上一集、章节、开关、信息 | tvOS 将大量按钮直接横排；无媒体信息页 | 只显示实际 Aether 会话/当前版本资料，未知字段省略。禁止读取 `player.opts` 的 MPV 状态冒充 Aether 运行信息 |
| F7 下一集与接续 | 播放末段/片尾区间提示、提示替代下一集按钮、焦点恢复；已实现队列接续 | `PlayerView.swift:149–174` 仅 ended 后出现倒计时卡；`AppModel.swift:171–181` 每次先关闭并重建播放器 | 提示窗口与“片尾后仍有内容”分支一致；保留同季队列与停止屏障。Aether 预准备/复用能力另核实，不能仅改延时宣称无缝 |
| F8 倍速与字幕设置 | TV 倍率列表 0.25–4.0；字幕大小及记忆 | `PlayerView.swift:249–256` 仅 0.5–2.0 子集（受引擎上限约束），无字幕大小入口 | 在真实引擎能力内暴露相同档位，失败回显实际值；临时加速不写普通偏好，流影独立；样式不覆盖 ASS 自带样式/位图 |
| F9 搜索语义 | `ui/pages/SearchPage.kt:168–176,197–210` 普通搜索传 `types` 和可选库内 `parent_id`，响应中的 Episode 单列；聚合走 `source.aggregateSearch`，只传 `query`，逐来源消费 partial | `ProductViews.swift:270–274` 聚合走 `emby.aggregateSearch`，当前服无库参数；历史在提交后隐藏 | 对齐库内范围、普通搜索类型、当前查询与 partial、来源身份及返回状态。聚合核心 `core/aggregate/sources.go:135–142` 固定 Movie/Series，不按界面类型筛选、不搜分集；不能强行设置 `include_episodes=true` |
| F10 手机遥控/扫码加服 | Android TV 设置和登录有手机承接 | `AppModel.swift:62–69` 只处理用户状态和图片通道事件，无 companion 事件接线 | 需连通键、开详情、账号变更、真实播放状态和暂停/seek/停止，不能以二维码可打开作为完成 |
| F11 备份还原 | Android 设置提供现有格式导出、预览、导入 | tvOS 设置没有入口，系统文件交互需适配 | 核心 `prefs.backupExport/Preview/Import` 已支持 content；手机承接传输需鉴权、用户发起、导入预览与凭据保护，不能直接把含账号备份贴进二维码 |
| F12 设置与关于 | `SettingsPage.kt:152–224` 外观/播放器/mpv/弹幕、手机遥控、网络、内容数据、关于更新 | `ProductViews.swift:780–815` 缺弹幕/遥控/备份；有旧“扩展与账号”标题，“关于”仅两行 | 分组和全部可生效的通用设置对齐；mpv 专属项受播放架构决策约束；更新不挂接错误平台安装器 |

## UI 与遥控差距

| 编号 | 当前差距 | 源码/最新契约依据 | 可观察验收 |
|---|---|---|---|
| U1 首页导航 | tvOS 顶栏固定叠在所有根页；确认首页仅选 Tab，没有首页刷新 | `ProductViews.swift:87–139`；Android `UI_TV.md:38–43,55–75` | 首页导航随内容离屏，回顶完整恢复；获焦切栏、确认刷新、防重；详情返回保留原位置 |
| U2 Hero | tvOS 背景、播放按钮、轮播指示器都可聚焦，聚焦暂停轮播 | `BrowseViews.swift:350–436`；Android `HomePage.kt:739–742`、`UI_TV.md:16` | Hero 只展示，完整移出焦点链；离屏/后台暂停仍有效，首个内容操作可达 |
| U3 密度与焦点 | tvOS 固定 250/400 点卡宽、adaptive 250 网格，1.025 缩放和细描边 | `BrowseViews.swift:215–278,737–742`；Android `ui/components/Cards.kt`、`tv/SharedBrowse.kt`、`UI_TV.md:64–75` | 按有效窗口/列数校准；放大只改绘制，不改搜索边界，不盖标题/邻卡；不能机械复制 dp 数值或只改 scale |
| U4 标题与库卡 | tvOS 栏目标题恒定竖杠，库卡文字/图片形式仍为旧版 | `BrowseViews.swift:204–211,306–320`；Android 当前 `MediaRowHeader` 和共享库轨 | 操作标题才可聚焦、Down 进入首卡，非操作标题不占焦点；库图成功/缺失/失败三态符合当前 Android |
| U5 详情影院布局 | tvOS 固定 460 点头图只放标题，元信息/参数/主操作纵向堆到图下；剧集无等价首屏参数组 | `BrowseViews.swift:810–915,1004–1021`；Android `UI_TV.md:77–87`、`DetailPage.kt` | 背景左侧信息、右侧画面，标题/评分/类型/简介/主操作/参数层级一致；电影与真实待播分集可直接选版/轨道起播 |
| U6 简介与选季 | tvOS 非空简介都给展开按钮，三行原位展开；分集后另铺季海报轨 | `BrowseViews.swift:916–951`；Android `DetailPage.kt:1268–1296,1470` | 两行简介按实际溢出决定全文弹窗；紧凑季入口、首屏分集及返回焦点，保留实际季列表信息 |
| U7 搜索 | tvOS 单列、重复标题，历史只在未提交时显示 | `ProductViews.swift:191–253`；Android `SearchPage.kt:385–391` | 左控制右结果、历史持续可用、按右栏宽度排卡、右键进结果/左键回控制、返回恢复 |
| U8 设置 | tvOS 单列大面板，全部分组同时显示 | `ProductViews.swift:772–824`；Android `SettingsPage.kt:218–240` | 左分组右入口，去重复大标题；二级页保存与错误语义不变，方向键和返回恢复 |
| U9 播控布局 | tvOS 左上标题/规格、常驻暂停/大量文字按钮、进度在操作上方；无常驻系统时钟与收起态底线 | `PlayerView.swift:29–73,177–241`；Android `TvPlayerParts.kt:166–310`、`UI_TV.md:89–98` | 底部标题与纯图标操作在进度上方，时间在下；常驻时钟、隐藏只读底线；方向唤醒/确认暂停/预览确认各自独立 |
| U10 面板与返回 | tvOS 当前每个面板直接回各按钮，无更多/弹幕子层级 | `PlayerView.swift:81–85,123–140,244–306` | 子面板先回父面板并恢复入口；播放页失败仍能重试/退出，不能让未知 panel 落入字幕分支 |
| U11 焦点与滚动 | tvOS 没有当前 Android 横轨边界、标题首卡、回顶取消、私密顶栏等同级证据 | `ProductRoot` / `MediaRow` / `EpisodeBrowser`；Android `tv/SharedBrowse.kt`、`SharedTvBrowseTest` | 用实际遥控键测首末卡、连续/反向键、返回与弹层；采用 tvOS 原生焦点机制，不照搬 Compose 的像素算法 |

## 播放与系统边界：不能用假开关补齐

### Aether 字幕

固定版本为 `apps/tvos/dependencies.lock.json` 的 AetherEngine 7.39.0，本轮 `fetch-tvos.py --verify` 校验通过。下述依据是本地已校验依赖，而非主仓提交被误当成依赖提交：

- `Sources/AetherEngine/PlayerState.swift:1435–1515` 定义富文本 run、定位与 text/richText/image cue；`:1565–1582` 定义位图、归一化坐标和 canvasSize。
- `docs/formats.md:516–520`：`preserveASSMarkup=true` 返回原始 ASS event lines、header 和字体附件，需要宿主 ASS renderer；不能将 `.text` 原样交给 SwiftUI Text。
- `apps/tvos/dependencies.lock.json` 已锁定 Libass 0.17.5 等二进制输入，可以进一步验证复用现有 libass 的 tvOS 接入，避免自己实现不完整 ASS parser；这不是 renderer 已接通的证据。
- PGS 需要多对象叠加和视频有效区域映射；只保留最后一个 bitmap、按全屏宽高直接相乘，会丢字幕或在裁切/黑边片源上错位。
- 在支持路径与测试就绪前保留现有格式限制，不能删除过滤后宣称字幕兼容完成。

### 手机遥控与备份

`core/companion/companion.go:36–55,249–286` 的手机控制转发现有核心命令，`core/player/transport.go:140–156,321` 的普通控制/状态面向 MPV；tvOS 视频实际在 Swift 持有的 Aether 中。必须补宿主控制与状态桥接，包含应答、失败和退出收尾，不能只处理 `companion.key`。

备份 content 命令在 `core/prefs/transfercmds.go:78–159`，现有 companion 白名单没有备份命令。增加传输涉及凭据，沿用显式配对和预览确认；私密空间不能开放普通跨服遥控/备份数据。

### 更新与引擎专属功能

`core/system/update.go:155–167` 只区分 Windows/Android，其余返回 Linux 资产；`update_install.go:574–605` 未提供 tvOS 安装流程。关于页需要明确适用的分发方式，不能直接调用当前通用安装器，也不能仅改标题就标为“更新对齐”。

Android 的 Media3/MPV 切换、mpv 配置、着色器/OpenCL 补帧不能直接作用于 Aether。平台性能/HDR/DV也不能由菜单存在推断。产品边界已确定为维持 Aether；实现可支持的等价能力，明确排除 MPV 专属配置、着色器和补帧，不将其静默加入 tvOS。

## 实施顺序与变更范围

1. **浏览与导航**：`BrowseViews.swift`、`ProductViews.swift`、必要 `MediaAPI.swift` DTO；先完整一条“首页→库→详情→起播→返回”再处理搜索/设置。复用身份、图片、分页和保存逻辑。
2. **播放器 UI 与已有核心功能**：`PlayerView.swift`、`PlaybackController.swift`；重组 OSD/面板、标记编辑、真实媒体信息、下一集提示。先覆盖停止/保存/晚到响应，保持声音和续播正确。
3. **流影与隐私发现**：在现有根导航和 Aether 会话上增加原生流影页面；复用 `extCapabilities/extLiuYingItems/extLiuYingLabels`，请求始终经 `MediaAPI.request`。独立倍率/循环/静音与普通播放隔离。
4. **弹幕与字幕**：核心布局规则复用；tvOS 新增必要的绘制层、测宽与时钟适配。ASS 使用完整 renderer，PGS 保留 cue/画布信息，不以文字替身验收。
5. **手机遥控、备份、关于更新**：仅在协议需宿主适配时修改 companion/核心，补双方契约回归；不修改 MediaStationGo、不新造 Emby 端点。
6. **整体验收**：扩充 `CoreClientCheck.swift`、`VisualCheck.swift` 和真实播放器检查；必要时调整 tvOS 检查/链接脚本，生成绑定由命令表产生。每个切片可独立回滚，最后复查所有页面与隐私模式。

所有功能需覆盖调用方、DTO、测试夹具、Xcode文件引用和类型检查脚本，不能让截图替身隐藏正式源码错误。当前不改变 Android TV 产品行为，不升级固定依赖，不出包或安装。

## 验证与当前状态

本轮已执行：

- 两轮分工只读源码核查，主线程按关键出处复核，并修正“流影在普通空间也可进入”“聚合必须 include_episodes=true”等错误推断。
- `python3 scripts/fetch-tvos.py --verify`：通过，固定输入有效。
- `python3 scripts/gen-bindings.py --check`：通过，当前 **233 条命令，ABI=1**；历史 333 条不是当前基线。
- `python3 scripts/check-tvos-speed-controls.py`：通过，仅静态接线。
- `python3 scripts/check-android-args.py`：通过，151 处参数调用；不作为 tvOS 验证。
- 最终差异检查和独立报告复核结果记录在任务 `implement.md`。

待执行：

- 新实现的定向回归，Apple 主机正式 `check-tvos-ui.sh` 类型检查、命令/DTO回归；本地未发现 swiftc。
- 同一夹具生成当前 Android TV 与 tvOS 深浅主题配对图，覆盖首页、库、详情首屏/下半页、搜索、设置、私密聚合、流影、OSD、面板、加载/失败/大字号。
- 实际遥控器、真实媒体的 SRT/ASS/PGS、暂停/seek/切集、前后台、双服务器身份与服务端进度读回；模拟器图不能替代。
- 验证完成前不声称“完整对齐”。

## 等待确认的边界

已向用户发出播放能力范围问题，尚未收到回答。推荐维持现有 Aether 视频链路，纳入完整字幕渲染、弹幕、流影与 UI/通用功能；MPV 专属配置/着色器/补帧和 tvOS 自更新按平台等价方式单独确定。选择复制 MPV 专属能力则需要另行验证双引擎、渲染、框架冲突、打包与真机色彩，不能计为普通界面修改。
