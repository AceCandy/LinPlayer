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
| TV 页面 / 草稿截图 | TvPageShots / TvDraftShots，配置 Roborazzi 记录模式 |
| TV 焦点 | TvFocusTest 等按键与焦点断言 |
| Android 真机 | src/androidTest 下相关测试与实际安装播放，设备可用性需核查 |

Robolectric 用空 Application 避免在 JVM 加载进程级 native 库；参照既有截图测试配置，不新增截图框架。
相关测试先在故障下红，再修复通过；完成后按 [统一交付规则](../shared/build-release.md) 出包，报告设备 / 联调缺口。

### Compose 播放器测试同步

- 直接组合 `rememberExoPlayer` 也必须提供 `LocalApp`；无会话行为的测试复用 `setPlayerContent`，已有 `AppState` 的页面测试提供自己的实例，并在结束时取消 `app.bg`。不得给生产 `LocalApp` 增加默认替身来消除测试失败。
- 包含后台挂起恢复和 Media3 主线程访问的集成测试使用 `androidx.compose.ui.test.junit4.v2.createComposeRule`，参照 `PhoneMedia3RecoveryTest`；旧版 Unconfined 调度可能把挂起后的播放器访问带到工作线程。保留同实例、自然切集与释放断言，不为此全仓迁移测试规则。
- 图片模糊等后台工作不受 `mainClock` 控制；冻结时钟测淡入前，使用 `waitUntil` 等待 `onLoadResult` 并逐帧推进组合，再测混色与终态。仅推进固定虚拟时长不能证明后台图片已完成。
- 图片代理高度按实测卡片几何、密度和 2:3 海报比例核验，并独立对照 `AppState` 的向上分档契约（120/220/330/480/720，超过时为1080）；保留来源路径断言，不能固定所有手机为 `h=330` 或只验证“发过图片请求”。

参考：[手机测试](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneThemeTest.kt)、[TV 页面截图](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/tv/TvPageShots.kt)、[TV 草稿](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/TvDraftShots.kt)、[TV 焦点](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/tv/TvFocusTest.kt)。

- 动效功能与性能证据分开：共享元素中间几何、图片淡入混色只证明路径正确；滚动卡顿反馈要核实空闲共享节点、屏外composition与图片解码，再用同设备同数据的帧耗时判断效果。禁止反复调幅度后仅以渲染回归声称流畅；真机未连接时明确性能归因仍未确认。
