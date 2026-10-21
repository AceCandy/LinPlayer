# 修复手机数据源首页服务器选择入口

## Goal

统一手机端 Emby 与插件数据源首页的左上角服务器选择入口，确保进入 TVBox 源后能切回其它服务器。

## Requirements

- R1: 手机端 Emby 与插件数据源首页的左上角统一作为服务器选择入口，显示当前服务器或源名称。
- R2: TVBox 源首页在加载、成功、空内容和失败状态下均能打开服务器选择并切回 Emby 或其它源。
- R3: 复用现有账号切换与服务器管理语义，不退出账号、不移除订阅、不修改播放或核心命令。
- R4: 详情与分类等二级页面保留原返回行为；不改 TV、桌面或插件页面接管机制。
- R5: 保留普通首页现有隐私空间入口与私密状态下的退出语义，不为插件源新增隐私能力。

## Acceptance Criteria

- [x] AC1: 普通 Emby 首页与 TVBox 数据源首页左上角均有可点击的服务器选择入口，当前名称正确。
- [x] AC2: 进入 TVBox 源后可由该入口选择 Emby 并恢复 Emby 首页，也可切换另一个插件源。
- [x] AC3: 源首页请求未完成、返回空内容或失败时，该入口仍可用。
- [x] AC4: 选择器中的当前项标识与现有服务器管理入口保持一致，切换后无重复首页返回栈。
- [x] AC5: 分类、详情返回，以及普通首页隐私空间入口不回归。
- [x] AC6: 定向回归先在原缺陷下失败、修复后通过；独立复核实际 diff。不自动编译安装包或提交。

## Confirmed Facts

- 用户确认故障发生在手机端，表现为进入 TVBox 源后找不到返回或切服入口，而非卡死。
- 用户要求：“左上角永远是选择服务器的地方”。
- `apps/android/app/src/main/kotlin/xyz/linplayer/app/PhoneRoot.kt:323` 按活动源用 `SourceHomePage` 替代 `HomePage`。
- `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/DataSourcePages.kt:188` 的源首页顶栏只有标题与收藏入口。
- `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/HomePage.kt:356` 已有 `ServerChip` 入口；`:659` 的 `ServerMenu` 为页内私有实现，需小范围共享而非再造菜单。
- `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/HomePage.kt:327` 的切服流程调用 `account.setActiveServer`、清理旧首页展示状态并刷新会话，提取时必须保留这些副作用。
- `apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneHomeRefreshTest.kt:526` 已覆盖普通首页服务器菜单，`:83` 的导航夹具可复用；数据源首页尚缺少对应 UI 回归。
- `docs/go-migration/UI_MOBILE.md:1361` 规定普通首页服名单击切服、长按进入隐私空间，私密状态下点击服名退出。

## Change Boundary

- 行为差距位于手机首页顶栏，不在 TVBox 订阅解析或 Go 核心。
- 预计修改手机 `HomePage.kt`、`DataSourcePages.kt` 与必要的共用选择器代码，使两个首页使用同一菜单和既有切服流程。
- 定向测试沿用 `PhoneHomeRefreshTest` 的 Compose/FakeCore 设施，必要时增加专用源首页测试，不引入测试框架。
- 普通首页切服后的缓存重置保留原有语义；数据源页面切换后的请求与展示以新源身份隔离。
- TV 仍经共用根导航与首页页面进入；手机新增入口应按当前形态隔离，不能将旧 TV 专页误判为唯一生产入口。

## Out Of Scope

- 不把二级页面返回键改成服务器选择器，不调整其它平台布局。
- 不删除源、账号或收藏，不新增数据源、插件仓库与播放能力。
- 不自动打包、安装、提交或推送。

## Verification Limits

- JVM/Compose 回归只证明测试环境中的渲染与导航；真机点击、网络与播放体验另行说明。
- 用户已批准实施并追加授权隔离安装复现；导航修复已完成源码、JVM/Compose 回归与隔离模拟器切服验证，未在用户手机验证。后续提交、归档与手机出包授权及结果见交付记录。

## Verification Results

- 新增源首页回归在修复前因找不到“管理服务器…”失败，修复后通过。
- 完整定向回归通过：`PhoneHomeRefreshTest` 33 项、`PhoneTypographyTest` 3 项、`SharedTvBrowseTest` 9 项，共 45 项，失败与错误均为 0。
- 额外以 `recordRoborazziDebug` 运行深浅主题两项源首页回归并查看四张截图；1.3 倍字号下左上服名、当前项勾选、管理与添加入口均可见，菜单未截断。截图仅保留于已忽略的 Android build 目录，不代表真机验收。
- `python3 scripts/check-android-args.py` 通过，核查 264 处命令参数；`git diff --check` 通过。
- 独立静态复核未发现实质性导航、取消、状态隔离或 TV/隐私兼容问题。
- 根因：插件首页替换 Emby 首页时遗漏宿主切服入口，原首页测试未覆盖活动插件源分支。复用现有选择组件并增加实际源首页导航回归，不扩大播放或其它平台行为。

## TVBox Playback Investigation

- 用户追加要求调查无法播放，随后确认暴风任意作品进入播放器模式后立即退回详情页，不是应用退出到桌面；未提供错误原文。
- 静态核查详情选集、播放路由、`source.playItem` 参数与核心处理器，未确认参数接线错误。
- 对此前提供的两个公开源做只读抽样：暴风一个样本的 HLS 清单与首个媒体分片可读取；非凡两个样本返回 HTML 而非媒体直链，需要解析或 WebView 嗅探。
- 当前 TVBox 插件已有网页提取、配置解析器与嗅探回退；尚未验证手机 WebView 对这些样本的实际解析与解码。
- 播放调查不是修复完成结论。未改动播放代码；后续需结合失败提示和具体源复现，不在文档保存真实播放地址或远程内容。
- 新增 `PlayerActivityTest` 两项现状验证，通过真实手机播放 Activity 和带编码分集地址的插件路由，覆盖无 Emby 会话起播、零进度 EOF 及取流失败。断言命令走 `source.playItem`，EOF 显示失败提示、取流失败保留换源弹窗且 Activity 不结束。它们当前通过，未复现用户的立即退出，不能当作缺陷修复的红绿证据。
- 完整 `PlayerActivityTest` 9 项通过，失败/错误/跳过均为 0；新增测试完成独立静态复核，`git diff --check` 通过。该阶段未修改生产播放逻辑或新增安装包，随后按用户授权进入隔离安装验证。
- 用户手机尚未连接，真实设备解码与退出原因未验证；隔离模拟器结果单列，不代替用户设备验收。

## Installed Playback Reproduction

- 用户进一步确认是返回作品详情页，不是整个应用退出，并明确授权安装测试。此前“不自动打包/安装”限制对本轮隔离复现不再适用；仍不授权发布、提交或修改用户设备数据。
- 使用新建 Android API30 隔离模拟器，不使用已有设备或容器；因当前账号无 KVM 访问权限，软件模式系统服务不稳定，改用临时容器向测试进程提供 KVM，不修改宿主用户组或设备权限。
- 已重编 ARM64 核心与手机调试包，实际安装成功；但应用启动阶段在模拟器 `libndk_translation` 内触发 SIGILL，尚未进入数据源页面。这不是用户所报“播放器返回详情”的复现，不能作为播放根因。
- ARMv7 手机包同样安装成功，但在首页前于核心库触发 SIGSEGV；不能据此判断用户手机故障原因。临时 Go overlay 尝试只在隔离包避开 CPU 探测及自旋计时指令，仍遇转译器不支持的其它指令，已放弃；未改项目工具链或正式核心、播放库。
- 为排查 UI 与 Activity 链路，另建原生 x86 手机测试包，临时使用本仓库历史固定的 `media-kit/libmpv-android-video-build v1.1.11 full`。该库与当前 ARM 正式版不同，不能据此验收正式版解码或认定用户播放故障已解决；正式 ARM 依赖保持原样。
- 自检 `tvbox:` 参数会强制 TV 形态，因此只用其建立测试源；随后不带该参数从普通手机入口启动、加载开发插件，再通过真实 UI 点击源首页推荐、详情和播放。
- 实际点击一部单集电影及一部 22 集剧集的首集，分别观察起播后 13 秒和 12 秒：两者均有视频画面，进程与 `PlayerActivity` 保持存活，未复现立即返回详情；未改生产播放逻辑。仍需用户手机本次失败日志来区分设备解码、版本或其它运行期差异。

## Installed Server Switch Regression

- 真实普通手机界面验证源首页左上可打开菜单并切回假 Emby；从 Emby 再切到源时捕获一次未处理的 `CoreException: 缺少 server 或 user_id`。
- 旧首页轮播 effect 尚未离开组合时，会话可能已切为空；`homeHeroCandidates` 原先仍无条件发送 `emby.heroCandidates`，与下游 `cachedHomeImages` 已有的空会话保护不一致。
- 在候选查询入口增加无 Emby 会话时返回空列表的保护，不吞掉有效会话的命令异常，不改业务、账号或播放逻辑。
- `CachedHomeImagesTest.switchingAwayFromEmbySkipsStaleHeroCandidateQuery` 在未修复代码上以同类缺少会话异常失败，修复后通过；覆盖会话从非空切为空后不再发候选或图片请求。此回归不宣称覆盖任意线程交错。
- 修复后的隔离包实际往返暴风与假 Emby 三次，截图显示两类首页均正确，进程 PID 全程不变，崩溃缓冲为空。独立静态复核未发现有效 Emby 行为回归。
- 根因类别为隐含会话假设与测试覆盖缺口；原导航 FakeCore 未模拟核心对缺少会话参数的拒绝。规范补充旧首页候选查询边界，并保留真实点击验收，避免仅凭菜单存在宣称切服完成。

## Final Verification And Cleanup

- 最终实际执行五组回归：`PhoneHomeRefreshTest` 33、`CachedHomeImagesTest` 9、`PlayerActivityTest` 9、`PhoneTypographyTest` 3、`tv.SharedTvBrowseTest` 9，共 63 项，失败、错误和跳过均为 0；按测试 XML 核实执行数量，不只根据 Gradle 成功判定覆盖。
- Android 参数门禁 264 处通过，`git diff --check` 通过；本轮九个修改文件及任务正文/元数据共 11 个文件的隐私检查未发现新增真实地址或凭据。
- 本轮模拟器容器、临时订阅服务、假 Emby 与空闲 ADB 服务均已关闭；隔离设备数据、订阅、日志、截图、Go overlay、下载的 x86 库及生成的 x86 测试包/原生目录均已删除。保留既有 SDK、正式 ARM 依赖与本次源码改动。
- 此阶段尚未生成正式发布包、提交或推送。用户手机的立即返回详情问题尚未复现，仍需失败现场的脱敏日志；不得将隔离 x86 出画或切服修复解释为正式 ARM 播放已修好。

## Authorized Delivery

- 用户于 2026-10-21 明确要求“提交归档吧 打个手机包”，授权本次本地提交、归档及手机正式出包；不包含远端推送或发布，也不归档其它任务。
- 使用 `bash scripts/pack-android.sh` 重新编译正式 ARM64 核心及手机 Release 包，未使用隔离复现的 Go overlay 或 x86 播放库；版本保持 `2.0.0`，版本码 `20000`。
- 最终交付为 `build/android/app-arm64-v8a-release.apk`，29,619,573 字节（28.25 MiB），与本次 Gradle 中间产物逐字节一致；SHA256 为 `cf1d264c7535d8538f514bbdaf56d5fe1eb6c2195682293dd575c67764b8a725`。
- `apksigner verify --verbose` 验证成功，v2/v3 签名通过；另以 API23 验证 v1 签名通过。后者对 META-INF 元数据的未覆盖警告不影响目标 API24 及以上使用的整包 v2/v3 验签。
- 核对 Manifest：`TV_BUILD=false`、`extractNativeLibs=true`；APK 仅含 `arm64-v8a`，全部原生库均为 AArch64 ELF 且以 DEFLATED 压缩，包含正式核心、mpv 与 FFmpeg 依赖，满足 64 MiB 体积门禁。
- 出包后再次核实五组测试 XML 共 63 项、零失败/错误/跳过，Android 参数门禁 264 处通过；提交前复核实际 diff 与隐私门禁。
- 本任务按导航修复范围交付归档；未修改生产播放逻辑，暴风播放后立即返回详情的问题仍未定位或确认解决。正式 ARM64 包未在用户手机安装验收，本包未配置崩溃上报。
