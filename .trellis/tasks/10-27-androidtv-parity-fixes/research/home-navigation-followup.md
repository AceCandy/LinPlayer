# TV首页反馈与共享浏览自适应：研究及修订方案

日期：2026-10-27。先研究和复现，随后按用户确认的边界实施并回归。

## 用户要求

1. TV轮播仅展示/自动播放，不点击、不聚焦。用户后续明确：不隐藏库卡时顶栏首页 ↔ 媒体库卡 ↔ 继续观看，隐藏库卡时顶栏首页 ↔ 继续观看，不改内容顺序。
2. 对照其它TV项目后，用户选择黄色底部短线标记当前栏目，移动焦点到其它栏目立即切页，不再按确认；否定实心焦点块提案。
3. 同意叠图白色前景和轻暗衬、滚离头图恢复主题前景，之后实际看效果。
4. 排查真机继续观看按下仍在本排跳动的问题。
5. 核查首页媒体库及横轨之外的TV共用浏览自适应；按窗口适配，不换另一套固定尺寸，不能拿手机适配或库列表网格作为全面完成依据。

## 当前实现证据

- `HomePage.kt:673`头图整块`pressable`；TV仅额外监听焦点并暂停轮播，未移除点击。用户最新要求将覆盖旧的TV头图点击语义；手机不变。
- `HomePage.kt:429`外层LazyColumn、`:455`库卡、`:472`续播、`:487`各库最新。库卡受 `UiPrefs.hideHomeLibraries` 控制；此前忽略该开关而建议整体调序，是错误理解。开关的隐藏续播标题、头图空间补偿也需保留。
- `HomePage.kt:891`的`libraryCardSize`仅非TV宽屏放大，TV回退158×90dp。
- `Cards.kt:410`的无参`posterLayout()`在TV直接返回null；`:442`回退PosterW=104dp、ThumbW=194dp，因此TV首页竖海报104×156dp、续播横图194×109.125dp。图片请求高度在`:435`仍固定220/330px。
- `Cards.kt:454`的LpRow没有显式上下行路由；`:495`给首页卡挂`homePosterEntrance`，手机/TV均调用。
- `PosterMotion.kt:248-264`的绘制层根据横向边缘裁切比例施加持续缩放和纵向位移。这不是只有加载时执行的淡入，左右边缘卡片的焦点矩形也随之改变。
- 之前R2修复的是LibraryPage网格，不是上述首页横轨。VisualAlignmentTest上一轮截图的续播数据仅一张，不覆盖一排多卡的错误纵向跳转。

## 按键复现和对照

使用正式TvRoot、960×540、Robolectric SDK36、系统非触摸模式、单个固定轮播、两个媒体库、原有6张resumeItems及后续latest。没有直接向目标RequestFocus。

序列：顶栏首页→下(头图)→下(剧集库卡)→下(第2张续播)→右×3(第5张)→下。

- 正常动画：最后一次Down从“黑暗森林”跳回同一排“谎言的代价”，`!hasAnyAncestor(home.resume)`期望失败。真机报告的“在几张续播里跳”已在本地复现。
- 系统动画倍率0的同数据/同按键对照：Down进入下一轨“电影”标题，离开续播的断言通过。
- 两组都已发送`emby.listLatest`，不能简单归咎于服务端没有下一排数据。
- 当前证据指向横轨边缘形变与默认二维焦点搜索相互影响；关全部动画的对照不是只关边缘形变的因果隔离。修复时仍需保留其它动画，仅对TV验证去掉改变焦点几何的边缘形变，并核对未组合/慢加载的下一轨。
- 本次2项探针为1红1绿，不是“回归全绿”。临时探针从测试源目录移除；原样源码、normal/no-motion截图和焦点轨迹保留于已忽略的`apps/android/app/build/home-focus-probe/`，方便实施时恢复红测。临时首轮原始轨迹已删除；后续轨迹只记录请求次数，不记录参数。
- 标准JUnit XML位于`apps/android/app/build/test-results/testDebugUnitTest/TEST-xyz.linplayer.app.tv.TvHomeFocusProbeTest.xml`；提取的用例名、通过/失败和断言证据另存探针目录`result-summary.json`，防止下一次测试覆盖后丢失证据。独立只读复核确认实际TvRoot按键及结论边界，原报告缺少结果文件入口的问题已补正。

## 外部对照

通过Gread读取源码，并查看各仓库提供的截图。它们是默认分支材料，不保证截图与源码处于同一版本，不能称作现场运行验收。不搬入其依赖或交互模式。

- Google JetStream，`android/tv-samples` main，核对提交`15ac8df7df91a07450751a5fe8c6fd17a6a4297f`：
  - `JetStreamCompose/jetstream/src/main/java/com/google/jetstream/presentation/screens/dashboard/DashboardTopBarItemIndicator.kt`使用单一背景块，导航获焦时高亮、离开导航时减弱；不是描边与短线同时叠加。
  - `DashboardTopBar.kt`通过onFocus切栏目、onClick向下进内容。这与本项目当前“确认才切页”不同；用户后续明确要求栏目聚焦切页，但不代表授权照搬所有动作入口行为或该项目视觉。
  - 官方合成截图：`JetStreamCompose/Screenshots.png`。
- Jellyfin Android TV，`jellyfin/jellyfin-androidtv` master，核对提交`cdc13db37f2101f98982536ca0158377c2ed311e`：
  - `app/src/main/java/org/jellyfin/androidtv/ui/shared/toolbar/MainToolbar.kt`区分activeButton颜色，焦点恢复由工具栏持有。
  - `ui/base/button/Button.kt`分别提供普通/聚焦背景与前景，当前页状态不等于焦点状态；使用现有Button，不叠多套指示装饰。
  - 仓库商店图`fastlane/metadata/android/en-US/images/tvScreenshots/1.png`是旧视觉资料，不能代表当前MainToolbar的准确布局。
- 当前项目依赖：Compose BOM 2026.08.00，实际foundation/ui 1.12.0，tv-material 1.1.0。Context7的官方焦点文档仅辅助理解，不直接套用参考仓库或最新文档API。
- 参考图保存在已忽略的`build/tv-home-references/`；来源为上述仓库，未生成替代参考图。

## 补充自适应核查（静态源码，不是渲染验收）

路径均相对 `apps/android/app/src/main/kotlin/xyz/linplayer/app/`。主线程已用当前源码抽查核心计算和调用点，不直接采纳探子的所有风险推断。

| 项目 | 当前证据 | 判定与处理 |
|---|---|---|
| 首页媒体库卡 | `HomePage.kt:893` 仅非TV宽屏放大，TV固定158x90；`:913`请求高度固定260 | 确定未接窗口适配，库卡和占位一起改，封面保留Fit |
| 共用横轨 | `Cards.kt:410-444` TV绕过posterLayout，固定104/194dp及330/220px | 确定缺口；影响首页、聚合等LpRow调用，不逐页复制规则 |
| 搜索/媒体库/收藏网格 | `LibraryPage.kt:379` 已按窗口扣TvPageSide计算；`SearchPage.kt:320`、`ListPages.kt:200`仍经posterImageHeight | 列数已适配；取图仍被TV固定值覆盖，修共享函数时核对实际单元宽和外层边距 |
| 人物作品网格 | `PersonPage.kt:183-185` TV默认6列，插件列数覆盖后用GridCells.Fixed | 确定与共用列数规则不一致；接已有规则，保留插件覆盖，补不同窗口测试 |
| 数据源分类/搜索 | `DataSourcePages.kt:247`、`:336`使用GridCells.Adaptive最小宽108/160dp | 已有列数自适应；最小宽不是固定成品宽，不能仅凭常量判错；待实图核对密度 |
| 详情头图 | `DetailPage.kt:1287`、`:1441`TV最小高220dp，手机走detailHeroMin | 最小高不等于固定高；低矮窗口列为渲染检查，不声称已溢出或直接改布局 |
| 设置 | `SettingsPage.kt:132`走manageListWidth，`Base.kt:516`最大720dp | 已有居中限宽，不把最大宽度当不适配 |
| 插件弹窗 | `tv/TvPluginDialog.kt:60`期望宽560、高上限520，`:75`输入宽512 | 仅静态风险：不能仅凭width断言越过父约束；需渲染确认窄/矮窗口按钮和输入可达 |

尺寸核验使用实际容器与密度；不把旧TV侧栏的 `TvDim.contentW/contentStart` 当正式共享页宽度。`Cards.kt:423` 的 `gridCellWidth` 当前仅扣网格padding，修改TV取图规则时须同时核对宿主 `TvPageSide`，否则会把非实际单元宽带入请求像素。

## 顶栏入口核查

- 可见顺序由 `tv/kit/TvKitShell.kt:80` 决定：服务器、首页、收藏、聚合视界、搜索、设置；首页按条件追加刷新，私密隐藏设置。`railItems` 中媒体库/下载不是可见入口。
- `tv/SharedBrowse.kt:118-125` 中首页/收藏/聚合走switchTab；搜索/设置走navigate。服务器还有私密退出语义，刷新为独立回调，不能无条件把所有获焦事件接onSelect/onClick。
- 当前黄色短线已存在，但外层仍有胶囊焦点底和描边；修订是简化为用户选择的短线状态，而非新增另一套选中装饰。
- 快速切栏目、二级页返回、顶栏与内容恢复列为按键验收。不能仅凭 `LaunchedEffect(Unit)` 的存在就声称每次重组都会重新请求焦点，也不能将代码推断写成已复现故障。

## 修订方案（已实施）

- 轮播保留自动计时、离屏/后台暂停，TV去掉整卡点击/焦点；手机手势、点击保持原样。
- 按既有隐藏开关保留库卡→续播顺序，不为隐藏态的上下路径重排显示态；顶栏进入首条可用内容，轮播不挡路。
- 当前栏目黄色底部短线，去掉胶囊/描边叠加与实心块提案；首页/收藏/聚合获焦立即切页，焦点进入内容后短线继续表示当前页。
- 叠图时使用白色文字/图标和轻暗衬，滚离头图恢复主题前景，不逐像素RGB反相或动态采样；不将所有共享焦点框全局改白。
- TV轨道左右在本排、上下离开本排；先最小隔离边缘形变再验证，保持其它动效与手机行为。慢加载、空轨及离屏下一轨不能困住焦点；仅有必要才补页面内行间路由。
- 复用现有窗口计算，补库卡、共用横轨/图片请求、人物网格；轨高/骨架/文字/焦点留白同步，库卡Fit不变。不同宽高与密度测试证明自适应，不用某张960dp截图上的固定数值替代算法。
- 数据源、详情、设置和插件弹窗先核验真实几何与按键，已有适配保持，不机械消灭固定dp；不扩大到播放器或重设计所有浏览页。

## 已确认边界

首页/收藏/聚合视界聚焦即切页，服务器、搜索、设置、刷新仍按确认触发，避免遥控路过进入管理页、刷新或在私密空间退出。

## 收尾

前轮31文件定向隐私扫描和diff空白检查通过；本轮产品改动已通过定向共享TV/视觉/隐私/手机回归，保留截图于忽略目录并已停止Gradle后台。多卡正常动效回归已由原诊断红转为通过；真实设备、遥控手感及实服联调仍未验。
