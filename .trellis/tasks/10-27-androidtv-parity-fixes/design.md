# 技术方案

## 总体

仅修改 Android 宿主的现有播放状态和共享页面适配；沿用 Go 命令、Navigation Compose、PlayerController、TV 焦点组件和测试夹具。六个里程碑顺序执行，不并行修改实现。

## R1 播放模式

对齐手机固定本次入口 initialMode 的语义，在 TV 播放页保留入口明确指定或进入时读取的模式，后续普通换集/版本控制器不重新读全局偏好。不能将 auto 直接提交核心，也不能为此扩大为跨片永久保留自动回退结果；沿用 PlayerController 的选择、切核和停止屏障。

预计修改 TvPlayerPage.kt、TvPhoneParityTest.kt；覆盖自动切集、手动选集和媒体键等共享入口。

## R2 网格

在现有 posterColumns 的 TV 官方默认分支复用纯尺寸计算，按窗口 dp 和宿主边距确定网格列数，不让 TV 回落手机竖屏三列。插件明确列数覆盖保持原契约。首页横向轨道、手机尺寸和图片通道不重做。

预计修改 LibraryPage.kt，必要时复用/补充 Cards.kt 的既有尺寸函数；测试落在 PosterLayoutTest/SharedTvBrowseTest/VisualAlignmentTest，覆盖媒体库及搜索、收藏调用方。

## R3 详情主操作

保留现有单列 TV 详情、头图和所有播放选项，只在 TV 将主操作移到版本/音轨/字幕选项之前。手机、平板排列不变；不为首屏播放引入双栏重设计或悬浮按钮。

独立复核补测发现：工具栏返回按钮的默认向下搜索停留原处。TV工具栏向下显式连接当前可见且已就绪的主播放操作；加载中、合集或主操作未挂载时不提供无效焦点目标。

预计修改 DetailPage.kt 与共享 TV 浏览/视觉测试，断言 detail.play 完整处于首屏、能用确认键播放，并保留选项可达性。

## R4 标题状态

标题与分集标签按条目初始化/清理，不在同条目的起播 attempt 或 engine 变化时清空；详情请求仍受当前目标的 effect 生命周期约束。换集立即隔离旧标题，避免先返回的详情被后启动的起播流程覆盖。

预计修改 TvPlayerPage.kt 与 TvPhoneParityTest/TvTrackTimingTest，使用既有 CompletableDeferred/CorePort wrapper 控制请求返回顺序，不新增测试框架。

## R5 实际版本规格

OSD 规格由当前条目的版本列表和实际 mediaSourceId 派生，而不是在资料请求返回时计算一次。目标变化清理旧版本身份/列表；找不到实际版本时显示未知/不显示规格，禁止首版本兜底冒认。读取资料与起播继续并发，不增加网络查询。

预计修改 TvPlayerPage.kt，必要时调整 PlayerUi 的派生属性；在播放回归中控制两个返回顺序、换目标及未知版本。

## R6 主动刷新

仅 TV 首页顶栏增加刷新动作，复用 HomePage 的现有 onRefresh；不将刷新浮层放入内容区、不占用服务器菜单键，也不重新启用 PullToRefreshBox。刷新动作由当前导航条目持有，页面挂载登记、离开撤销；普通/私密分支和防重复提交沿用首页原逻辑。

顶栏按钮使用现有 NavigationItem/焦点词汇，与原有索引隔离或追加新动作索引，禁止更改既有导航索引。登记回调需要使用当前回调与有效页面身份，不能持有已离开的账号/空间。

预计修改 HomePage.kt、PhoneRoot.kt、tv/SharedBrowse.kt、tv/kit/TvKitShell.kt，测试 SharedTvBrowseTest 及首页刷新相关回归。不扩展为其他页面的通用刷新框架。

## 兼容、回滚与风险

- 无持久化格式、命令签名、ABI 或核心业务变更；各里程碑可按独立 diff 回滚。
- 主要风险为 TV 焦点顺序、懒列表滚动恢复和播放器异步状态。测试必须走真实组件/按键接线，不只检查 helper 或回调存在。
- 手机/平板保留现状，公共组件变化有平台分支回归；继续尊重插件列数覆盖。
- docs/DESIGN.md 为 Apple 网站分析材料，不作为 Android 本次视觉正本；采用 UI_MOBILE/UI_TV 最新实施基准、当前源码及用户本轮要求。
- 真机解码、系统输入法、HDMI 刷新率和真实服务器契约不在本地模拟测试可证明的范围。

## R7 插件失败换源（用户追加授权）

- 共享弹窗产出内部类型化目标：同源线路 Player、跨插件源 SourceDetail、跨服 Emby Detail。浏览入口沿用共享执行函数；播放器同源目标复用现有停止/换目标流程，跨源目标先登记并等待停止，再经专用 Activity 结果返回。
- 跨源结果不经过 officialRoute，不增加对外插件路由。PlayerLaunchPage 在原交接 entry 仍有效且仍为普通空间时执行目标：跨插件记换源关联并带集序/进度进入源详情，Emby 切服成功后刷新会话进入详情。处理后移除失败的 Player 交接页，保留原浏览返回栈；错误回原页并反馈。
- SourcePlayFailed 优先当前有效进度；尚未起播时回用 src.resume_secs，不把初始化的0当成用户要求从头播放。
- TV失败页追加可遥控的换源按钮，并共用候选弹窗。仅弹窗提供共享 TV 控件模式；候选异步到达后设置初始焦点，返回键先关闭弹窗。
- 候选读取复用 AppState.displayBlock 的页面内 partial 消费/取消保护，错误单独展示和重试，不使用进程作用域向已销毁弹窗写入。
- 同源停止不得吞取消，目标写回前检查协程有效性；源详情的单次自动起播标记使用 rememberSaveable，返回同一详情不再次自动播放。
- 预计改动 DataSourcePages、PlayerPage、TvPlayerPage/TvPlayerParts/TvPlaybackHost、PlayerActivity、PlayerLaunchPage；测试复用 PlayerActivityTest/PlayerLaunchPageTest 并补共享弹窗边界。核心与生成绑定不变。

## R8-R12 修订方案（已确认并实施）

- 范围为 TV 首页/顶栏与共享浏览自适应缺口；不重做播放器或所有页面，不引入依赖，不改变手机/平板交互。旧的首页调序和实心焦点块提案撤销。
- TV头图取消整卡pressable和焦点组，保留自动轮播及离屏/后台暂停。保留 `UiPrefs.hideHomeLibraries` 控制的库卡位置和头图补偿几何；显示时顶栏首页 ↔ 库卡 ↔ 续播，隐藏时顶栏首页 ↔ 续播。上下键不得跳过视觉上仍在前面的库卡，空内容按实际可用行衔接。
- 先用正常动效多卡红测隔离边缘形变：只对TV去掉改变焦点几何的横轨边缘位移/缩放并保留其它动效，检查是否足以修复；如仍有懒列表离屏/慢加载问题，再在页面和现有列表状态内补行间路由，不新增通用焦点框架。行内左右、返回恢复不回归。
- 顶栏沿用现有路由索引；当前栏目使用黄色短线，不叠胶囊底/描边。首页/收藏/聚合视界获焦调用现有 `switchTab`，不重建共享业务状态；避免当前根页重复导航。快速左右切换、进入/离开二级页、返回顶栏与异步结果需按键回归。服务器/搜索/设置/刷新保留确认动作，不把进入私密/退出私密或刷新误接到获焦事件；刷新动作靠近首页排列，避免栏目立即切页后入口不可达。
- 顶栏和叠图内容按所在背景选择白色前景/轻暗衬，滚离头图恢复主题颜色；不逐像素反相、不随每张图片动态采样，也不把所有共享焦点框全局改白。
- 复用现有 `posterLayout(widthDp,heightDp)` 与窗口计算规则，核对TV外层 `TvPageSide` 和横轨/网格自身padding只扣一次。共享卡片宽、行高、骨架、标题与请求像素同步，人物作品列数接已有共用列数规则，插件明确覆盖保留。媒体库图仍Fit，请求高度也随展示大小变化。不能只去掉 `posterImageHeight` 的TV分支而继续用未扣宿主边距的 `gridCellWidth` 冒充实际卡宽。
- 已确定缺口在HomePage库卡、Cards横轨/取图、PersonPage作品列数；LibraryPage/搜索/收藏列数已适配，不重复重写。数据源Adaptive最小值、详情220dp最小头图、设置720dp最大行宽、弹窗560dp期望宽不是仅凭常量就能判错；窄/矮窗口先渲染，确认内容或操作不可达才修。
- 预计触达HomePage、Cards、PersonPage、SharedBrowse、TvKitShell及必要的PosterMotion/PhoneRoot；测试扩展SharedTvBrowseTest、VisualAlignmentTest、PosterLayoutTest、PersonPageTest及手机宽屏/动效回归。后续若渲染发现其它确定缺口，先记录影响，不借本轮重设计整页。
- 研究证据与参考版本见`research/home-navigation-followup.md`。本节描述的行为已落到生产代码，真机遥控与实服联调仍未验证。
