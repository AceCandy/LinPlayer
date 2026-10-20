# tvOS 与现行 Android TV 的对齐依据

## 基准与状态

用户要求 tvOS 沿用现有产品功能和页面，只作必要的平台与遥控适配。本文记录调研时的差距，不是当前实现或验收结果；后续源码实施进展以 implement.md 为准。

正式 Android TV 入口：`apps/android/app/src/main/kotlin/xyz/linplayer/app/tv/TvApp.kt:89` 的 `TvRoot` 调用 `PhoneRoot(app, tv=true)`。`PhoneRoot.kt:67` 起的 imports 和 `:323` 起的 NavHost 指向 `ui/pages`；`tv/SharedBrowse.kt:69` 的顶栏共用该导航栈。旧 `tv/HomePage.kt`、`tv/DiscoverPage.kt`、`tv/ServersPages.kt` 等独立浏览实现不作为本次产品清单依据。

范围对照同时读取 `docs/go-migration/UI_TV.md` 的当前实施基准、共享页面源码及 `UI_TVOS.md`。旧设计里“Android TV 独立首页”“tvOS 只做最小版”的描述不能覆盖最新用户要求。

## 逐页映射

以下 Android 页面均位于 `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/`，播放器除外。

| 页面/能力 | 现行基准及关键出处 | tvOS 当前差距 | 计划承接 |
|---|---|---|---|
| 根导航 | `../tv/SharedBrowse.kt:69`：服名、首页、收藏、聚合视界、搜索、设置；共享 PhoneRoot 栈 | 只有首页 NavStack 和账号 sheet | 统一原生顶部导航，各栏目保留自己的页面与焦点，详情/播放全屏 |
| 首页 | `HomePage.kt:140`、`:202`、`:317`：横幅、媒体库、继续观看、各库最新、合集、可用插件栏目 | 仅继续观看/媒体库；独立视觉版式 | 对齐当前共享首页的栏目、卡片、层级与用户状态操作；各区独立加载、屏外最新轨按需加载 |
| 媒体库 | `LibraryPage.kt:151`：层级分页、服务端排序/筛选；`PhoneRoot.kt:339` 实际路由 | 固定名称排序和简单分页 | 保持 `emby.listItemsPage` 的嵌套 query，补对应排序/筛选/返回恢复 |
| 详情 | `DetailPage.kt:430`、`:463`、`:691`：资料、版本/字幕、季集、收藏/已看、演职员/相似内容 | 仅基础文字、版本、季集与播放 | 按当前共享详情布局与语义补齐；写入成功后刷新当前及返回页面，TV 不显示下载 |
| 搜索 | `SearchPage.kt:127`、`:393`：当前服/聚合搜索、类型、历史/人物条件 | 无页面 | `emby.search` / `source.aggregateSearch` / `emby.personItems`，保留真实参数与跨服身份 |
| 收藏 | `ListPages.kt:113`、`:139`：分类、排序、分页、数据源收藏分支 | 无页面 | `emby.listFavorites` 及已有来源收藏；按实际库归属分组，不猜 ID |
| 聚合视界 | `AggregatePage.kt:79`、`:111`、`:161`、`:207`：按服统计与续播，排行榜/日历快捷入口，TV 隐藏下载 | 无页面 | 复用 `emby.aggregateOverview` 的 `server_name/counts/resume`，先切服再开详情；不造全服去重池 |
| 排行榜/日历 | `PhoneRoot.kt:365`、`:366`：共享 RankingPage/CalendarPage | 无页面 | 使用现有扩展来源与主服；分页失败保持旧页，日历只显示明确时间 |
| 服务器/线路 | `ServersPage.kt:128`、`:161`、`:439`：账号卡片、切服/编辑/主服/聚合/删除、线路同步测速切换 | 只有登录与当前账号退出 | 调用现有 account/source 命令，管理状态与会话/图片身份原子切换 |
| 设置 | `SettingsPage.kt:130`、`:225`：播放与外观、网络、内容与数据、扩展与账号、应用 | 无页面 | 保留当前分类和分节，逐项记录核心/本机状态和生效点；引擎/平台专属项采用明确映射 |
| 私密空间 | `../data/AppState.kt:66`、`:177`、`../tv/SharedBrowse.kt:69`：分类验证、当前服限制、入口/退出、身份代次 | 固定 `is_adult=false`，无空间状态/入口 | 全量分类校验后进入；所有请求、图片、状态与路由绑定空间代次，不开放跨服/本地历史 |
| TV 播放 | `../tv/TvPlayerPage.kt:302`、`:494`、`../tv/TvPlayerParts.kt:681`：选集、同季下一集、版本、轨道/字幕、倍速、比例、章节 | 只有基础播放/seek/文本字幕/音轨 | 复用现有停止屏障与核心目标，扩展 Aether 控制 UI；不借 mpv 命令假控 Aether |
| 手机伴侣 | `../tv/TvApp.kt:96`、`core/companion/companion.go:103`：Go HTTP 服务、手机扫码配对与遥控 | 未接 UI/导航事件 | 复用现有 companion 命令/鉴权与配对，不建立未鉴权的调试入口；真机网络/前后台另验 |
| 插件/扩展 | `PhoneRoot.kt:358`、`SettingsPage.kt:184`：插件页、贡献入口/设置、通用宿主 | tvOS 明确关闭插件启动且无宿主 | Go/goja 通用运行时与原生声明式 UI 分别核查；能力按真实宿主报告，网页/JAR/Python 单列差异 |
| 文件/导入导出 | `SettingsPage.kt:168`、`PhoneRoot.kt:353`：文件浏览及备份入口；Android TV 加服可选本机卷 | 无页面/导入流程 | 先明确 tvOS 沙盒和输入来源；不能把 Android 存储卷路径或系统选择器直接搬过来 |

## 固定 AetherEngine 能力

当前依赖为 7.39.0；本地固定源码 `Sources/AetherEngine/AetherEngine.swift` 提供 `setRate(_:)`、`videoGravity`，章节通过 `mediaChapters`。客户端尚未调用这些功能。音轨切换依赖真实 TrackInfo.id，字幕预选/记忆必须绑定实际版本和轨道身份；复杂字幕渲染、弹幕叠层与可用轨道仍需独立验证。

播放器迁移保持核心的选版/续播、`emby.reportProgress` 与 `player.stopPlayback`；同季切集、自动下一集、版本切换都经过原会话停止屏障，实际末集返回详情，不叠播放器或遗留旧上报。

## 平台证据与待决项

在实际远程构建环境明确指定当前 Xcode 的 `DEVELOPER_DIR`，通过 SDK 27 的 `swiftc -typecheck -target arm64-apple-tvos18.0` 无产物检查：

| 检查 | 真实结果 | 可得结论与限制 |
|---|---|---|
| `import WebKit` | `no such module 'WebKit'` | 当前 tvOS SDK 不能直接使用 WebKit 实现流影/网页插件；不将其它平台头文件当作 tvOS 支持证据 |
| `UIDocumentPickerViewController(forOpeningContentTypes: [])` | 类及初始化器均 `unavailable in tvOS` | 不能沿用系统文档选择器实现文件/备份导入；不等于所有沙盒文件能力不可用 |
| 插件通用运行时 | `core/plugin/rt/runtime.go:119` 使用 goja；`host.go:89` 启动插件 | 核心业务有可复用实现，但不证明宿主网页/JAR/Python 或全部插件兼容 |
| 伴侣服务 | `core/companion/companion.go:103` 使用标准 TCP/HTTP，`:156` 通过总线执行 | 有可复用核心；实际局域网可达、配对、后台停服/恢复和遥控事件尚未验证 |

用户待决：流影与依赖网页的插件是允许手机承接，还是要求先研究电视内等价方案。已发出问题；无答复时保持待决，不默认删掉或擅自选择替代。

Android 专有存储卷、JAR/Python 组件、mpv/Media3 配置、更新器、系统截图/权限等逐项建立能力映射。未知项不等同于不支持；仅有命令绑定也不等同于实际可用。

## 验证边界

本次只读代码/规范与 SDK 类型检查，不生成 .app，不访问媒体服务或用户账号，不改 Android/桌面，不执行发布。既有签名最小版仍保留，首装空会话修复及 Keychain 数据不回退。实现前先收敛平台差异，最终按 PRD A1–A14 验收，阶段完成不能冒充整体完成。
