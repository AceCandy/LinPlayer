# 外壳质量检查

## 渲染与交互

- 当前桌面是 Avalonia，Android 是 Compose。旧 CDP / WebView2 / Tauri invoke / DOM 焦点方式只用于 rust-final 历史代码，不用于验收当前原生页面。
- 桌面运行真实窗口核查布局、命中、DPI 与遮挡；视频层额外核查原生子窗口和 mpv 日志。
- 手机渲染覆盖真实尺寸、安全区、深浅主题和设置切换；Robolectric 只证明该环境中的组件表现，不能代替真机系统适配。
- 对齐参考播放器须按片源与布局核对正常播放的整页：视频、控制、进度及存在的下方内容面板；比较使用相同尺寸/字号/主题，等待态截图与“按钮存在”断言不能替代整页视觉验收。真实页通过递增播放采样进入播放态，视频背景及测试图片须标明来源；参考收起/手势语义须实际核实，静态展开图只证明展开形态。
- 播放器“竖屏”必须区分设备方向与片源比例；至少覆盖9:16和16:9片源在两个方向的全屏区域。元数据接线用`Route.Player.ar=0`与当前实际版本的Video宽高，不能拿路由hint冒充已读取媒体尺寸。`Osd.portrait`仅决定窄屏控件排列，不能按片源比例恢复顶部小窗和常驻选集。
- 方向切换回归保留同一Activity/Composition并同时变更配置和窗口尺寸，断言play一次/stop零次；Media3额外核对实际SurfaceView尺寸。测试宿主须处理与生产相同的configChanges，配置驱动不等同系统旋转；合成窗口背景不等同解码画面。
- 片尾提示回归须比较出现前后的滑杆和底栏按钮边界完全相同，并核验卡片与所有交互命中不重叠；侧边安全区须真实注入并断言控件边界发生对应位移，卡片宽度须计入末端inset；仅“按钮存在”不足以发现底栏被挤窄。全屏竖屏同时检查中央退进与右侧倍率的命中、上下集/标题/进度/底部操作的顺序、锁定恢复、片尾卡片、面板和错误页实际点击。冻结Compose时钟时不可直接等待滚动动画，滚动验证须推进时钟。
- Popup是独立窗口；与播放页入口比较时用实际WindowManager.LayoutParams的x/y加boundsInRoot换算，不能把Popup的boundsInWindow当作Activity坐标。截图捕捉真实Popup，关闭测试覆盖窗口返回/外点及退场后移除；冻结时钟需先确认状态已应用到窗口，不能用旧节点存在得到假绿。组件位图未可靠保留绘制层透明度时，仅能证明开合生命周期，不声称渐隐像素或真机流畅度已验。
- TV 静态截图不证明焦点正确；遥控器方向、确认、返回、滚动与换集需按键回归。
- TV详情须从播放行用方向键下到参数、简介和演员，再上返首屏；电影路径不为没有内容的季/分集锚点创建连续零高度懒列表项，否则离屏焦点搜索可能停在参数行。插件有注入时仍保留锚点。参数组向上退出先滚回首屏再聚焦播放，不能让主操作留在屏外。TV详情不显示屏幕返回按钮，需覆盖加载中、合集、插件接管主操作时遥控返回仍退一级，手机返回按钮保留。
- 焦点恢复要覆盖“返回时控件暂时禁用，异步刷新后重新启用”。`pressable` 把 `enabled` 交给 `tvFocus`，仅在可用且保存的焦点ID仍匹配时恢复；用户已移动到其它控件则不得抢回。`SharedTvBrowseTest.playbackReturnRestoresFocusAfterDelayedDetailRefresh` 走真实 Activity 结果回传与延迟详情，不用同步替身证明异步路径。
- TV首页纵向焦点回归不能只放一张续播卡：至少包含可横向滚动的多张续播、后续内容轨和离屏目标，从中间及边缘卡分别按上/下，断言离开当前轨且不回跳本排。正常动效为必测，关动画只用于对照诊断；绘制层缩放/位移也可能改变焦点几何，不能因代码没有消费方向键就认定不会干扰遥控。
- TV首页焦点方案先核对 `UiPrefs.hideHomeLibraries` 的两条分支：显示时首页顶栏 ↔ 媒体库卡 ↔ 继续观看，隐藏时首页顶栏 ↔ 继续观看；两态均用纯方向键回归，并覆盖续播为空。不能忽略设置，为满足隐藏态路径而重排所有用户的内容，或让焦点跳过仍可见的库卡。
- TV首页回顶测试先建立首屏、用方向键真实下滚，再上返并断言当前首页栏目获焦、轮播回到原始坐标，随后再次下行/上返；显示/隐藏库与续播有/无四种组合都覆盖。禁止在上返后直接 `RequestFocus` 导航来掩盖方向键失败。首排卡片可能与悬浮顶栏重叠，不能用列表偏移为零来判断是否可上返；正常搜索可上行时仍不能跳过内容行。
- TV无外框方案须保留清晰焦点与业务选中提示，覆盖反色底上的文字/图标、卡片片名与版本对勾；输入边界和进度提示不应一起删。主导航栏目按透明底、短线及聚焦字重验收，动作入口按反色验收。TV Material控件的像素测试应通过实际 `TvRoot` 和方向键验证；裸共享主题下仅语义 `RequestFocus` 可能没有同样的交互绘制，不能据此宣称真实导航聚焦已验。
- TV禁用按钮可能继续保留焦点；菜单键等自定义按键回调必须与点击/长按共用 `enabled` 门控，不能仅依赖 `Surface(enabled=false)`。`TvFocusStyleTest.disabledTvButtonDoesNotRunMenuActionAndCanBeEnabledAgain` 覆盖启用→禁用但仍聚焦→重新启用时菜单动作的计数。
- TV 的 Popup 按键回归先用测试 Instrumentation 切换系统到非触摸模式，并在结束后恢复原值；只向主窗口注入方向键不会保证新窗口采用遥控模式。菜单节点用 `hasAnyAncestor(isPopup())` 限定再断言焦点和注入按键，不能让底层同名季标签误匹配，也不直接 RequestFocus 到待验菜单项。`SharedTvBrowseTest.remoteChangesSeasonOpensEpisodeAndRestoresItsFocus` 覆盖这一链路。
- 同功能的桌面 / 手机 / TV 与按钮 / 长按等入口按改动影响核查，不扩大为无关全平台重测。
- 流影换片回归须覆盖焦点按钮回车、滑块方向键及跨播放器实例的连续滚轮输入，不能只向页面注入 KeyDown 或直接触发 Click。节流状态归流影页，不随播放器重建清零；分页成功返回空页仍须能恢复保留页；播放中直接离页不能在卸载回调里重新挂载控件。

## 可用验证入口

### Yamby / Hills 模拟器参考授权

- 用户已明确授权使用模拟器安装、运行和查看 Yamby / Hills；涉及这两个应用的界面或交互参考时，直接进行模拟器观察，不重复询问是否允许使用模拟器，也不止步于 APK 静态分析。
- 参考 APK 位于 `docs/cankao/`。先检查已有设备、工具链及历史实测记录；使用隔离测试环境，不清空或覆盖用户已有应用数据。涉及真实账号、凭据或额外破坏性操作时仍需单独确认。
- 实际进入目标页面，观察点击、滚动、返回并检查截图；只找到类名、字符串或路由不等于看过界面。设备或页面无法运行时报告具体阻碍与未验证项，不推测布局。
- 结束后关闭本次启动的模拟器和测试服务，清理临时账号、日志与导出；保留的脱敏参考图须位于已忽略目录。
- 2026-10-20 人物页实测记录及截图在 `build/people-reference/`（本地忽略目录）：Yamby 已验证人物分组横轨、三列作品网格、分页请求与返回位置。合成图仅用于布局观察，不代表参考应用的默认配色。
- Hills 1.10.0 在 API30 与 Android 14 的 `libndk_translation.so` 环境均遇到启动 SIGABRT：`Expected an exception after ExceptionCheck`。先查 [既有记录](../../tasks/archive/2026-10/10-13-msg-extension-client/research/hills-danmaku.md)，不要原样反复重试或推测其界面；换不同转译后端或真实 ARM 环境后再验证。此限制不代表真实设备也失败。

| 场景 | 入口 |
|---|---|
| 桌面自检 | scripts/selfcheck-win.sh，当前平台是否可执行先确认 |
| Android 本地回归 | 在 apps/android 运行 ./gradlew :app:testDebugUnitTest --tests '<相关类>' |
| 手机主题渲染 | PhoneThemeTest，真实设置页按钮与背景像素断言 |
| TV 播放页 / 草稿截图 | TvPageShots / TvDraftShots，配置 Roborazzi 记录模式 |
| TV 播放器焦点 | TvFocusTest 等按键与焦点断言 |
| Android 真机 | src/androidTest 下相关测试与实际安装播放，设备可用性需核查 |

Robolectric 用空 Application 避免在 JVM 加载进程级 native 库；参照既有截图测试配置，不新增截图框架。
相关测试先在故障下红，再修复通过；完成后按 [统一交付规则](../shared/build-release.md) 出包，报告设备 / 联调缺口。

### Compose 播放器测试同步

- 播放上报迁到 `PlaybackService` 后，Robolectric 集成测试须先断言页面已请求启动服务，再用 `Robolectric.buildService(...).create()` 驱动生命周期；只发 `player.status` 事件不能替代服务查询的位置样本。服务 Handler 用主 Looper 的 `idleFor` 推进，Compose `mainClock` 只推进页面时钟；结束时销毁服务并清理绑定。`TvRefreshRatePlaybackTest` 同时断言快进目标累加、上报仍取实际位置及当前 `item_id`，不能删掉上报断言或改报目标位置来消除失败。
- 直接组合 `rememberExoPlayer` 也必须提供 `LocalApp`；无会话行为的测试复用 `setPlayerContent`，已有 `AppState` 的页面测试提供自己的实例，并在结束时取消 `app.bg`。不得给生产 `LocalApp` 增加默认替身来消除测试失败。
- 包含后台挂起恢复和 Media3 主线程访问的集成测试使用 `androidx.compose.ui.test.junit4.v2.createComposeRule`，参照 `PhoneMedia3RecoveryTest`；旧版 Unconfined 调度可能把挂起后的播放器访问带到工作线程。保留同实例、自然切集与释放断言，不为此全仓迁移测试规则。
- 图片模糊等后台工作不受 `mainClock` 控制；冻结时钟测淡入前，使用 `waitUntil` 等待 `onLoadResult` 并逐帧推进组合，再测混色与终态。仅推进固定虚拟时长不能证明后台图片已完成。
- 图片代理高度按实测卡片几何、密度和 2:3 海报比例核验，并独立对照 `AppState` 的向上分档契约（120/220/330/480/720，超过时为1080）；保留来源路径断言，不能固定所有手机为 `h=330` 或只验证“发过图片请求”。

参考：[手机测试](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneThemeTest.kt)、[TV 页面截图](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/tv/TvPageShots.kt)、[TV 草稿](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/TvDraftShots.kt)、[TV 焦点](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/tv/TvFocusTest.kt)。

- 动效功能与性能证据分开：共享元素中间几何、图片淡入混色只证明路径正确；滚动卡顿反馈要核实空闲共享节点、屏外composition与图片解码，再用同设备同数据的帧耗时判断效果。禁止反复调幅度后仅以渲染回归声称流畅；真机未连接时明确性能归因仍未确认。

## TV 实拍问题回归门禁

- 根页返回不能调用可能清空栈的 pop 来探测；覆盖根页一次返回仍保留首页、进入详情再返回不继承退出计时。
- 切栏目回首页完整回顶与详情返回恢复来源是两条契约，分别按真实按键测试；标题向下测试先横移到首卡离屏，再回标题按 Down，必须聚焦真正第一项。
- 焦点放大测试同时断言语义边界不变与边界外像素变化；使用真实 MediaCard、图片和片名，不能只用纯色 Box 证明没有片名白条。正常动效下验证上下离轨和焦点余量。
- 缓存命中不代表没有淡入；冻结时钟，在解码完成后反复离屏/回滚，检查第一帧即显示目标像素。性能仍需同设备同数据的帧耗时。
- 异步首焦点不得在 requestFocus 失败时消耗 pending；节点晚挂载测试须保留真实延迟请求。用户已有焦点、已下滚或弹层打开时不得抢焦点。
- 取色等后台任务的集成渲染使用 v2 createComposeRule，避免旧 Unconfined 把重组带到后台线程；不靠删断言、全局关动效或改生产线程逻辑规避测试调度问题。
- 深浅主题、窄屏和大字号截图标明测试素材；浅色遮罩必须保持白字对比度，短溶解过渡的实际观感及实体遥控重复事件仍需真机验收。
