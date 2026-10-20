# Android TV 现行共享设置页清单（只读核查）

## 入口与边界

- `PhoneRoot(tv=true)` 登录后仍走 `MainShell(tv)`；该壳把 `Route.Settings` 直接挂到 `ui.pages.SettingsPage(nav)`，`Route.SettingsSub` 挂到 `SettingsSubPage(nav, it)`。证据：`apps/android/app/src/main/kotlin/xyz/linplayer/app/PhoneRoot.kt:133-135,367-368`。因此本清单以 `ui/pages/SettingsPage.kt` 及其子页为准；旧 `tv/SettingsPage.kt` 不是此路径。
- 共享根页/子页源码注释明说手机页面分组入口、二级页，`PhoneRoot(tv=true)` 没有替换这些 Composable。共享页源码中没有 `LocalTvMode` / `tv` 条件分支；唯一 Android 版本条件是 Android 12 Monet 提示（`:334`），子页本身另有 Android 系统文件选择、APK 安装等平台动作。
- 有数据条件导致的动态隐藏：无插件侧栏入口时不显示「插件页面」分组（`:155-172`）；只有当前配置有效时显示部分控件（如缓冲容量、mpv.conf 清除）；壁纸模糊控件仅在有壁纸且平台支持时出现（`ThemePicker.kt:95-106`）。这些不是 tv 模式专属隐藏。确认的 tv 模式隐藏项：**无**。

## 分类 / 条目 / 数据来源 / 生效方

| 分类、条目 | 读取 / 写入 | 实际生效方 | 证据与备注 |
|---|---|---|---|
| 播放与外观 → 外观：主题、色系、加载动画、隐藏媒体库海报展示 | `UiPrefs` + Android `SharedPreferences(lp_ui)`；主题另调 `plugin.themes` / `plugin.activeTheme` / `plugin.setActiveTheme` | Android 宿主 UI / 插件主题宿主 | `SettingsPage.kt:290-346`; `data/UiPrefs.kt:18-35,47-66,136-158,175-178`; `ThemePicker.kt:38-69`。Android 本地外观状态，不是 Go 同步配置。 |
| 播放与外观 → 外观：界面主题（插件） | Go 插件命令读取/设置；重启生效 | Android 宿主 + 插件 | `ThemePicker.kt:44-69`。平台值来自 Android `platform()`，不要推断 tvOS 主题可共用或该平台有主题。 |
| 播放与外观 → 播放器：播放内核 | `UiPrefs.engine`，本机 SharedPreferences；选项 `auto` / `exo` / `mpv` | Android 播放宿主选择器（下次起播） | `SettingsPage.kt:393-405`; `UiPrefs.kt:68-78,187-190`。内核选择不是 Go 配置字段。 |
| 播放器：播放缓冲、缓冲目标容量、硬件解码、杜比视界自动软解、跳过片头、跳过片尾、到了就自己跳 | `player.getPlaybackPrefs` / `player.setPlaybackPrefs`；键分别 `buffer_target_bytes`、`hwdec`、`dolby_auto_sw`、`skip_intro`、`skip_outro`、`skip_auto` | Go 配置；播放时 Go/mpv 消费（缓冲为 Android 播放层能力） | `SettingsPage.kt:350-437`; Go 结构/字段 `core/player/prefscmds.go:32-47`，读写注册与白名单字段 `:338-349,351-421`；`core/config/prefs.go:77-80,195-240`。只核实 UI 实际显示的字段；没有把 Go 存在但本页未展示的 `default_speed`、`skip_use_online` 等算作共享入口。 |
| 播放与外观 → mpv 配置：当前配置、导入、清除、预览前 20 行 | `player.getMpvConf` / `player.setMpvConf(text)`；系统文件选择器读文本 | Go 保存/过滤；mpv 播放器消费 | `SettingsPage.kt:772-833`；`core/player/subtitle.go:166-169,401-465`。页面明确提示仅 mpv、退出当前播放后生效。 |
| 播放与外观 → 弹幕：弹幕源列表/启停/排序/编辑/删除/添加，官方源状态，屏蔽词，屏蔽用户 XML 导入 | `danmaku.get/setDanmakuConfig`、`getOfficialDanmaku`、`testSource`、`get/setBlockwords`、`importBlocklist` | Go 弹幕源/过滤配置；播放器渲染弹幕 | `SettingsPage.kt:552-655`。核心 prefs 中明确有 `DanmakuBlockwords` / `DanmakuBlockUsers`：`core/config/prefs.go:129-133`。此子页没有展示旧 TV 设置里的弹幕显示区/字号/不透明度/速度控件，故不应把旧 `tv/SettingsPage` 专有控件混进此共享页清单。 |
| 播放与外观 → 截屏：叠加系统时间及角落、叠加条目艺术字及角落、截长屏按钮；保存位置说明 | `UiPrefs` 本地 SharedPreferences；保存到 Android 相册/旧系统应用目录 | Android 宿主截屏实现 | `SettingsPage.kt:835-866`; `UiPrefs.kt:80-97,160-173`。注释明确截屏链路由 Kotlin/PixelCopy 执行、核心层无消费点。 |
| 网络 → 多线程加载：当前服务器启用、并发连接数 | `prefs.getPrefetchSettings` / `prefs.setPrefetchSettings`；`servers`, `threads`, `cache_bytes` | Go 本地预取代理 | `SettingsPage.kt:885-943`; Go prefs 确认 `prefetch_servers`, `prefetch_threads`, `prefetch_cache_bytes`：`core/config/prefs.go:160-176`。 |
| 网络 → 主进度服：未指定/选择 Emby 账号、同步状态、重试、刷新 | `prefs.get/setPrimaryProgressServer`、`prefs.retryPrimaryProgressSync` + `account.listAccounts` | Go 配置与进度同步 | `ui/pages/PrimaryProgressPanel.kt:31-99`; `core/prefs/primary_progress.go:13-18,21-29,33-81`; prefs 字段 `core/config/prefs.go:149-150`。 |
| 内容与数据 → 已屏蔽的内容 | `emby.blockedList` / `emby.setBlocked(blocked=false)` | Go Emby API / 服务器内容状态 | `SettingsPage.kt:945-973`。 |
| 内容与数据 → 备份与还原：账号、软件设置勾选；导出、预览、还原 | `prefs.backupExport` / `backupPreview` / `backupImport`；Android SAF 文件选择器 | Go 备份格式/加密/合并；Android 宿主文件读写 | `SettingsPage.kt:465-544`; 命令契约 `docs/go-migration/COMMANDS.md:294-295`。 |
| 内容与数据 → 文件浏览 | `Route.Browse`（独立页面） | Android 宿主 + Go 文件浏览能力 | `SettingsPage.kt:173-190`。 |
| 内容与数据 → 存储与数据目录：缓存占用/清理、数据目录、日志目录/导出、发送日志、媒体缓存保留/上限 | `system.dataPaths/cacheSize/clearCache/exportDiagnostics`、`prefs.get/setPrefetchSettings(media_cache_bytes)`、Android 日志/缓存/SAF | Go 缓存与路径 + Android 宿主日志/文件选择/反馈 | `SettingsPage.kt:975-1099`。日志和部分 UI 缓存由 Android 管理；媒体缓存预算字段 Go 有定义：`core/config/prefs.go:177-178`。 |
| 扩展与账号 → Trakt / Bangumi 账号连接/断开 | `sync.traktAccount`, `sync.bangumiAccount`, authorize/device-code/poll/login/logout | Go 凭据与账号 API；宿主展示授权码/Token 输入 | `SettingsPage.kt:1401-1525`。注释限定记进度/收藏由插件做，账号连接留宿主（`:1403-1408`）。 |
| 扩展与账号 → 插件、扩展组件、插件声明的页面入口 | `Route.Plugins` / `Route.Extensions`; `plugin.sidebar` → `Route.PluginPage` | Android 插件宿主 + Go 插件系统 | 根入口 `SettingsPage.kt:155-172,192-203`; 页面列表 `PhoneRoot.kt:367-368`。插件页面仅有入口数据时显示。 |
| 应用 → 更新设置：渠道、启动自动检查、GitHub 代理、当前版本 | `prefs.get/setUpdateSettings`；启动检查 `system.checkUpdate`；Android 安装流程调用下载/进度/安装命令及系统 APK 安装器 | Go 更新设置/检查/下载；Android 宿主安装 APK | `SettingsPage.kt:1101-1148,1286-1399`，Android 安装 `:1222-1265`。 |
| 应用 → 关于：版本、检查更新/展示更新说明 | `system.checkUpdate` + `AppState.caps` | Go 检查；Android 宿主更新对话框/安装 | `SettingsPage.kt:1112-1148`。 |
| 根页插件分节（`settings` 锚点）及外观/播放器/弹幕/存储/关于子页分节 | 插件 Section 声明 / 插件宿主 | Android 插件 UI 宿主 | `SettingsPage.kt:213-214,265-268`。不是 Go 通用偏好项。 |

### Go 可直接复用的已呈现设置

基于 UI 实际命令调用与 Go 当前字段/处理代码，确认可由同一 Go 配置或命令承载的共享项：播放设置上述 6 个字段；mpv.conf；弹幕源/屏蔽列表；多线程预取、媒体缓存预算；主进度服；备份/还原；更新设置；跨服续播（仅旧 TV 页面有，**不在当前共享 `ui/pages/SettingsPage` 中**，见下表）。不得因为 `Prefs` 里还存在字段就自动生成设置项。

明确的跨服续播 API 与字段存在于 Go：Android 旧 `tv/SettingsPage.kt:171-190` 调用 `account.getCrossServerResume` / `setCrossServerResume`；`core/config/prefs.go:145-150` 声明字段。但当前共享 `ui/pages/SettingsPage.kt` 没有该入口；tvOS 现状已有该入口（见下一节），不能误标为 Android 当前共享项。

## tvOS 当前 SettingsView 与差异

当前 tvOS `SettingsView` 在 `apps/tvos/Sources/ProductViews.swift:456-544`。已提供：主题 `@AppStorage("appearance")`；固定文字 `播放内核：AetherEngine`；首选音轨/字幕语言、默认字幕开关（保存到 `prefs.setPrefs`）；服务器/线路页；非隐私空间下跨服务器续播、缓存显示/清理、屏蔽名单、清空搜索历史；服务器管理、退出账号、版本号。屏蔽页在 `:546-571`。

相对 Android 当前共享设置页，tvOS SettingsView 缺少（按共享根页分类）：

- 播放/外观：色系、加载动画、隐藏媒体库海报展示、插件界面主题、壁纸及调节、可选播放内核设置、缓冲、硬解、DoVi 软解、片头片尾跳过、mpv.conf、弹幕源/屏蔽词、截屏配置。
- 网络：多线程加载、主进度服。
- 内容/数据：备份还原、文件浏览、数据/日志目录、日志导出/反馈、媒体缓存保留与上限。tvOS 已有基础缓存显示/清理和 blocked list，不能算缺项。
- 扩展/账号：Trakt/Bangumi 连接、插件、扩展组件、插件页面入口。
- 应用：更新设置（渠道、自动检查、代理）、检查更新/下载流程。tvOS 当前只显示版本文本。

tvOS 已有但**不属于当前 Android 共享设置页**的项目：首选音轨/字幕语言及默认字幕（tvOS `:478-485`；写 Go `prefs.setPrefs`，与 `docs/go-migration/COMMANDS.md:314` 契约一致）；隐私空间切换（`:490-493`）；清空搜索历史（`:506`）；退出当前账号（`:509-512`）；跨服务器续播（`:495-496`）。这些是 tvOS 当前既有能力，不能仅凭它们存在就归入“共享设置”。

## 证据边界 / 未核实

- 本清单比较的是 `PhoneRoot(tv=true)` 实际采用的共享 Compose 设置页与当前 tvOS `SettingsView`；未将旧 `apps/android/.../tv/SettingsPage.kt` 的丰富设置当作当前共享源。
- 命令契约/Go 字段可证明配置是否由核心层接受与持久化；具体 tvOS 播放器 `AetherEngine` 是否消费某些 Android/mpv 播放字段，需检查 tvOS 播放实现，不能由 Go 字段存在推断。特别是 Android 的 `hwdec`、mpv.conf、Android 播放缓冲和本地 `UiPrefs.engine`，不能直接当作 tvOS 可复用项。
- 未逐条核查所有插件的 platform 声明和 tvOS 原生文件选择/截屏能力；插件主题/壁纸在 tvOS 的平台支持、Android SAF / APK 更新 / PixelCopy / 日志目录等宿主能力，都需要单独验证后才能决定是否移植。

## 本轮实现更新

以上“tvOS 当前”表是修改前基线。本轮已将外观/播放器/多线程加载/主进度服/存储与数据目录/关于拆为二级页，根页使用共享分组名称。新增隐藏首页媒体库海报、预取启用与连接数/缓存预算、主进度服选择/同步状态/重试、媒体缓存预算；原语言字幕偏好、主题、跨服续播、缓存清理、账号/私密入口保留。网络设置复用 Go 配置，外观继续按宿主本机保存。私密空间隐藏网络/存储/账号跨服入口。

尚未对齐项包括完整外观主题/色系/壁纸、片头片尾消费、弹幕、截屏、mpv 配置对应的内核能力、备份/文件承接、第三方账号、插件/组件及更新流程。不能因 Aether 内核不同将所有这些差异视为自动豁免，也不能显示未接入的有效开关。最终 Swift 检查及新增契约回归已通过；此前私钥访问阻塞已解除，本轮已签名更新安装并执行启动；真实设置操作与生效仍未验。
