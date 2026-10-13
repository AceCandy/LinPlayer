# 播放器与加载动画核查

## 手机切集与方向锁

- `PlayerPage.kt:368-382` 的 `switchTarget` 在当前页面恢复临时音量、停止服务、等待控制器 stop，再替换 `route`，不会重新导航。
- 自动完播 `PlayerPage.kt:416-429`、面板选择 `:1026-1029` 都复用这个入口；`PlayerPanel.kt:316-320` 通过 `onPlaybackTarget` 交回页面。
- `PlayerPage.kt:735-737` 的 `wantLandscape` 使用 `remember(route.itemId)`；`:738-753` 依据条目查询视频尺寸。
- `:755-767` 的 `DisposableEffect(wantLandscape)` 在 key 变化时清理旧 effect，先写 `SCREEN_ORIENTATION_UNSPECIFIED`，然后新 effect 写 SENSOR_LANDSCAPE/PORTRAIT。新条目方向未知时可能等待请求后才重新锁。此路径可以解释用户报告的切集转屏，但真实设备的转屏动画仍未复现。
- `route.copy` 当前保留旧 `ar`；相同方向 hint 下并非每次切集都会改变 effect key。回归须使用初始比例未知、随后查询明确横片的条件，而不能仅用固定 `ar=16/9`。
- 真正退出仍走 `:301-311` 的 `leave`，先恢复系统方向、等待再 pop。修复必须隔离迟到媒体尺寸响应，不能离页后重新锁定。
- `PhoneEnginePlaybackTest.kt:69-93`、`:128-151` 覆盖 MPV/Media3 自动换集；`:352-426`、`:510-537` 覆盖同页版本和选集。现有 `showPlayer` (`:577-597`) 使用固定横屏 hint，未断言方向请求变化。
- `PlayerPage.kt:416-429` 仅在 `finished` 后选择下一集；`:1064` 的 `Osd` 及其调用没有 Next Up 状态/卡片。目前有完播连播，没有临近结束的提前提示逻辑。

## Yamby Next Up 追加运行核查

### 实际入口与证据范围

- 同一参考 APK 已安装并运行于本轮隔离 API30 模拟器，连接本地模拟 Emby 服务；测试队列为同季两集，每集120秒的本地 H264/AAC 视频。没有使用真实账号或外部媒体服务器。
- 实际播放 Activity 是 `com.hush.yamby.ui.activity.PlayerCmpActivity`，当前运行内核为 Media3/Exo。APK 中仍有旧 `PlayerActivity` 与 `player_activity.xml`；此前围绕旧播放器的静态结果不能代替当前入口行为。后续研究先用实际运行入口核对，再读取对应状态链路。
- 当前静态状态包含 `ac.C0147`（`NextEpisodeUiState(title, progress)`）、`ac.C0252`（`NextEpisodeChanged`）、`ac.b0`（`PlayerUiState` 的 `nextEpisode` 与独立的 `controlsVisible`）。`ac.C0150` 处理 NextEpisodeChanged 时更新 nextEpisode，没有同时修改 controlsVisible。该事件的产生条件仍未从保护方法中恢复，不能据此宣称源码阈值。

### 观察与复测

| 条件 | 实际观察 |
|---|---|
| 第一集83秒、88秒 | 未显示 Next Up |
| 第一集自然经过约90秒 | Next Up 出现；卡片显示第二集标题和进度条 |
| 控制栏自动收起 | 卡片继续显示；单独截图确认控制栏已完全隐藏 |
| 暂停第一集95.099秒 | 会话状态为暂停，UI 为01:35 / 02:00，卡片仍显示 |
| 快退至85.099秒 | 会话仍暂停，UI 为01:25 / 02:00，卡片撤回 |
| 从86.642秒自然播放再次越界 | 约89.869–90.519秒的截图采样仍无卡片；约90.659–91.129秒的采样已有卡片 |
| 第一集暂停约91.8秒后点击 Next Up | 当前集标题切为 S1E2，时间重置00:00；尚未到第一集 EOF |
| 未点击卡片，自然播完第一集 | 自动进入第二集 |
| 第二集暂停100秒，当前队列无下一集 | UI 为01:40 / 02:00，未显示 Next Up；控制栏收起后的截图也无卡片 |

- 时间边界以 Android 播放会话位置、更新时间与系统 uptime 推算，并在截图前后读取，范围覆盖截图耗时；截图只用于显隐确认，未依靠进度条坐标推断时间。蓝色视频与灰色卡片的像素检查已由截图人工复核。
- 结论为最后**约30秒**触发；未恢复源码的严格 `<`/`<=`、刷新周期或精确毫秒值。对齐方案选择30秒作为可测试行为边界，不把测量误差说成源码契约。
- 暂停通过媒体会话状态 `2` 和不变的位置核对；控制图标/标题与媒体会话更新可能有先后差，不能仅凭一次 Pause 图标判断正在播放，也不能把旧会话位置当作刚切换的新集位置。
- 本次测试的顶部控件无障碍名称为 Picture in picture、Resize video、Change orientation、Chapters、More player options。快进实际增加20秒，快退减少10秒；未验证其可设置性或初装默认值，暂不据此调整 LinPlayer 原有步长。
- 尚未测试不同片长、未知时长、跨季、队列加载失败、锁屏/面板、参考端 MPV 和真机；当前结果也不证明 LinPlayer 的方向或首帧问题已修复。

## Yamby 加载动画

APK：`docs/cankao/Yamby-v2.1.0.11-arm64-v8a-release.apk`；仅 arm64-v8a。

本次运行核查的 APK SHA-256：`1c847168c34d2fd1df9ce24d783da3ab297cec2b09389c194020d056fb18af7b`。

定向研究产生的 Java 行号只对应本轮临时导出；持久证据以类名、方法名、原 APK 资源路径为准，临时导出不入仓。

### 枚举与资源

`ed.EnumC1941` 静态初始化（导出 Java `:48-50`）列出七项。名称恢复经过 `y4.AbstractC6587.m12168`（`:487-503`）实际源码复核：Base64 解码两段数据，按循环密钥 XOR 得到 UTF-8；该方法有 Java 实现，不是 native，不能仅因其它方法 native 就认定名称无法恢复。

| 名称 | 保存 ID | 动画资源 | 主题着色 | 画布与帧 |
|---|---:|---|---|---|
| Material | 0 | Material LoadingIndicator | 是 | 原生组件 |
| Style1 | 1 | `res/raw/custom_loading1.json` | 否 | 500×500，0–61 帧，25fps；pacman loader |
| Style2 | 2 | `res/raw/custom_loading2.json` | 否 | 200×200，0–180 帧，60fps |
| Style3 | 3 | `res/raw/custom_loading3.json` | 否 | 1200×1200，0–120 帧，60fps；Bee 预合成 |
| Style4 | 4 | `res/raw/custom_loading4.json` | 是 | 300×300，1–60 帧，60fps；Dino 图层 |
| Style5 | 5 | `res/raw/custom_loading5.json` | 否 | 800×600，0–33 帧，25fps |
| Style7 | 7 | `res/raw/loading2.json` | 否 | 700×700，118–669 帧，120fps |

APK 还带 `custom_loading6.json`，但上述枚举没有关联它，不能自行增加第八项。六个已使用 JSON 经原 APK ZIP 条目核验总计181,196字节，约177KiB，不含外部图片资源。

### 设置与消费

- `hc.AbstractC2788.m6060` 绘制动画卡片，`m6059/m6061` 读取当前 ID；`ec.C1782` case24 调用 `C5362.f22605.m14209(...)` 保存选择并更新显示状态。
- `rc.C5362` (`:124`) 的 `f22605` 使用整数 delegate 默认值0，字段反射名可恢复为 loading 动画相关属性；原生比较及底层存储 key 未完整恢复，不声称实际设备默认选择已实测。
- `com.hush.yamby.ui.widgets.CustomLoadingView` 构造函数读取 `e4.AbstractC1646.m4905()` 当前选项：Material 使用原生 LoadingIndicator，其余使用 Lottie，按枚举标记选择是否着主题色。
- XML `layout_loading_lottie`：130dp、自动播放、循环、速度1；`layout_loading_material`：90dp。设置预览约64dp；这些是不同容器的设计尺寸，不要求所有手机加载槽机械使用130dp。
- 多个普通、详情/电影及刷新布局消费共享 CustomLoadingView；存在独立 CatLoadingWidget，因此不能据此声称参考端所有等待都完全统一。

## LinPlayer 加载入口

- `Base.kt:789-805` 的 `LpRefreshIcon/LoadingState` 是集中入口；`:808-817` 的 `BlockBox` 默认使用它。手机插件初始空树 `PluginSurface.kt:267` 已接入。
- `LibraryPage.kt:269-305` 直接用刷新图标，`SearchPage.kt:307` 单独画 CircularProgressIndicator；`HomePage.kt:368`、`ListPages.kt:198`、`SearchPage.kt:206` 使用 Material 默认 PullToRefreshBox 指示器。
- `Base.kt:222-256` 按钮等待显示省略号，调用方包括 `GatePage.kt:219`、`ServersPage.kt:406`。
- `PlayerPage.kt:842-861` 首帧等待与 `:945-951` 播放缓冲只有文字。面板和状态行的读取中提示需按实际等待状态增加统一反馈，保留原中文说明/错误/重试。
- `PhoneRoot.kt:110,124-130` 的启动轮廓、内容骨架/图片静态占位不是可量化任务进度。统一等待指示器不得伪造已就绪页面、延迟真实内容或让一个区块阻塞全页。
- 真实下载百分比等有值进度、网页内部、插件主动声明的动画和 TV 不在本轮手机通用等待样式改造范围。
- `UiPrefs` 适合本机样式保存，`AppearancePanel` 为设置入口；`LocalMotionScale` 已监听系统移除动画，全部新动画遵守它。

## 依赖核查与设备限制

- 项目 Compose BOM 为2026.08.00、Material3 1.4.0；缓存 AAR 中没有公开 `LoadingIndicator` 类，只有相关 token。参考 APK 用 Material3 1.5.0-alpha15 和 Material Components 1.13.0-alpha10，不能直接升级项目到参考端预览版本。
- 通过 Gread 查阅 `airbnb/lottie-android`；另核对 `v6.7.1` 的 `animateLottieCompositionAsState.kt`，存在 isPlaying、动画倍率及可取消动画状态接口。参考资源可用 Lottie6.7.1 播放，无需手写解释器。
- 直接核对 Material Components `1.13.0` 标签 `LoadingIndicator.java`，公开组件及 `setIndicatorColor`、`setContainerColor`、`setAnimatorDurationScaleProvider` 可用。Material 样式可通过受限的 AndroidView 包装接入，不升级 Compose；主题只影响该组件。
- 早期研究新建隔离临时模拟器：已有 API30 Google APIs x86_64 镜像、KVM、2核/4GiB、SwiftShader、无网络、无账号。工具与加速检查通过，但三次启动均在 QEMU 主循环以 code1 提前退出，当时未连接设备或安装 APK；没有改动主机权限或已有服务。本次追加核查已成功启动并实际播放，详见上面的 Next Up 运行证据。
- 早期失败的模拟器容器、临时 AVD、反编译导出及公开依赖源码临时文件已清理；追加核查完成独立复核后，已关闭专用容器、模拟器和本地服务，删除六个本轮临时目录中的导出、AVD、媒体、截图、日志与临时凭据；保留原参考 APK、既有 SDK 与镜像。
- 顶部控件名称及 Next Up 的实际行为已补证据；完整操作、倍速步长、波浪动态仍未确认。静态动画资源不能代替七款逐项渲染验证。
