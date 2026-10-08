# 手机字体与 Material 3 Expressive 核验

## 已核实事实

### 项目现状

- Android app `minSdk = 24`：`apps/android/app/build.gradle.kts:60`。Material Compose BOM 是 `2026.08.00`，该 BOM 将 `androidx.compose.material3:material3` 管理为 `1.4.0`；项目自己的依赖表也记录这两个值：`apps/android/app/build.gradle.kts:156-165`、`docs/go-migration/UI_MOBILE.md:1210-1212`。BOM POM：<https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.08.00/compose-bom-2026.08.00.pom>。
- 当前 `LpTheme` 保留自定义深浅色方案并传入 `MaterialTheme(colorScheme = scheme, typography = typo)`：`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/theme/Theme.kt:163-198`。注释明确主动不用 Material You 动态取色，理由是影院沉浸底色：同文件 `:157-162`。
- 导入字体目前贯穿 `Theme.kt:userFontFamily()`（`:137-155`）、`SettingsPage.kt` 的 OpenDocument/导入/恢复入口（`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/SettingsPage.kt:283-350`）以及 `UiPrefs.uiFont`（`apps/android/app/src/main/kotlin/xyz/linplayer/app/data/UiPrefs.kt:40,114,155`）。Theme 注释明确采用 `Typeface.createFromFile` 是因为 Compose `Font(File)` 要求 API 26，而 app minSdk 24（`Theme.kt:142-143`）。
- 页面转场目前直接用 Navigation Compose 的 `tween` / `tweenI`，并未走 `lpTween`：`apps/android/app/src/main/kotlin/xyz/linplayer/app/PhoneRoot.kt:230-243`、`:317-321`。`NetImage` 图片淡入也直接调用 Compose `tween(T.T5, ...)`：`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/components/Cards.kt:88-107`。本地统一 tween 封装 `lpTween` 会读取 `LocalMotionScale` 并按系统动画倍率缩放：`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/theme/Motion.kt:86-95`。

### 字体来源、授权、文件体积

以下许可证/说明均从字体作者或维护组织的公开仓库 README/LICENSE 核对；字体文件没有下载到工作区。体积是对官方 raw 文件 URL 发 HEAD 取得的 `Content-Length`，不是 APK 增量实测。

| 候选 | 官方证据与特征 | 授权 | 文件体积（字节，约 MiB） |
|---|---|---|---|
| Noto Sans CJK SC | [notofonts/noto-cjk README](https://github.com/notofonts/noto-cjk/blob/main/Sans/README.md)：提供简体中文 SC、静态字重、变量字体和子集版本。 | `Sans/LICENSE` 明确 SIL Open Font License 1.1；允许随软件捆绑、嵌入、再分发；字体本身不可单独出售，修改版不能改用其他许可证。 | 官方子集变量 TTF `Sans/Variable/TTF/Subset/NotoSansSC-VF.ttf`：17,773,132（16.95 MiB）；官方单 Regular 静态 OTF `Sans/SubsetOTF/SC/NotoSansSC-Regular.otf`：8,331,336（7.95 MiB）；完整字符集 SC 变量 TTF：36,144,788（34.47 MiB）。 |
| Noto Serif CJK SC | [notofonts/noto-cjk README](https://github.com/notofonts/noto-cjk/blob/main/Serif/README.md)：有简体中文 SC 子集、静态字重和变量字体。 | `Serif/LICENSE` 同为 SIL Open Font License 1.1，条款同上。 | 官方子集变量 TTF `Serif/Variable/TTF/Subset/NotoSerifSC-VF.ttf`：25,125,232（23.96 MiB）；单 Regular 静态 OTF `Serif/SubsetOTF/SC/NotoSerifSC-Regular.otf`：11,625,800（11.08 MiB）；完整字符集 SC 变量 TTF：59,899,696（57.12 MiB）。 |
| LXGW WenKai Screen / 霞鹜文楷屏幕阅读版 | [作者仓库 Readme](https://github.com/lxgw/LxgwWenKai-Screen/blob/master/Readme.md)：介绍称基于霞鹜文楷，将较粗字重映射为 Regular，并调整度量与 Android 默认 Roboto 相同，目标是 PC/Android 屏幕阅读；字体从 Releases 取 TTF。该仓库最新 release API 查询到 `v1.522`；`LXGWWenKaiScreen.ttf` 体积 25,673,994 字节（24.48 MiB）。 | 仓库 `OFL.txt` 是 SIL Open Font License 1.1。Readme 特别列出保留名称「霞鹜」「落霞孤鹜」「LXGW」：未经作者书面授权，衍生字体名不得使用；原文件未改仅重编的例外条款另见原文。 | 官方 release API：<https://api.github.com/repos/lxgw/LxgwWenKai-Screen/releases/latest>；release 下载：<https://github.com/lxgw/LxgwWenKai-Screen/releases/download/v1.522/LXGWWenKaiScreen.ttf>。该文件体积来自 GitHub release asset 元数据 `size`，未下载。 |
| Android 系统默认 | 不打包字体，可无新增字体资源体积。项目当前空字体时 `FontFamily` 为 null，由 Compose/平台默认字体处理（`Theme.kt:146-153,186-195`）；UI 设置当前把它展示为「系统默认」（`SettingsPage.kt:307-309`）。 | 无另行再分发字体文件。 | 0 字节新增 APK 资产。各设备系统字形实际覆盖/观感未经本轮设备验证。 |

Noto 官方 README 的部署说明特别区分全字符集与 Region-specific Subset；SC 子集更省体积。官方路径及 README：<https://github.com/notofonts/noto-cjk/blob/main/Sans/README.md>、<https://github.com/notofonts/noto-cjk/blob/main/Serif/README.md>。许可证正文：<https://github.com/notofonts/noto-cjk/blob/main/Sans/LICENSE>、<https://github.com/notofonts/noto-cjk/blob/main/Serif/LICENSE>。

### 项目版本的 Material 3 Expressive API

- 使用项目实际版本 `androidx.compose.material3:material3:1.4.0` 的官方 Google Maven sources artifact：<https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/1.4.0/material3-1.4.0-sources.jar>。从其中 `commonMain/androidx/compose/material3/MaterialTheme.kt` 核验：`MaterialExpressiveTheme` 在第 185 行声明为 `internal fun`（参数含 `colorScheme`、`motionScheme`、`shapes`、`typography`）；其默认分支第 203-207 行选 `expressiveLightColorScheme()`、`MotionScheme.expressive()`、`Shapes()` 和 `Typography()`。因此，应用源码不能直接调用该版本这个内部入口。
- 同 artifact 的 `MotionScheme.kt:123-129` 文档说 expressive scheme 面向 prominent UI / hero interactions；但 `expressive()` 在第 129 行也是 `internal`。`MaterialTheme.kt:90-96` 接收 `motionScheme` 的重载也是 `internal`。不要仅因新版本分支上的 Gread `HEAD` 显示 `MaterialExpressiveTheme` 就认为 1.4.0 app 可以调用；该版本 sources artifact 的可见性为准。
- Material 3 1.4.0 官方 sources artifact 中 `ExperimentalMaterial3ExpressiveApi.kt:24` 的注解本身为 `internal annotation class`。本轮未从 1.4.0 找到 app 可直接使用的公开 `MaterialExpressiveTheme` 入口。Material3 1.4.0 具体 expressive 组件清单未完整枚举；此项不影响上面的可见性结论。
- 版本 artifact 的 sources 源码位于官方 Maven；CodeGraph/Gread 对 `androidx/androidx` HEAD 检索到更新分支的相同命名，但该 HEAD 不能代替 `1.4.0` 版本证据。Gread 用于仓库 README/LICENSE 和 androidx 源码检索；精确版本核对以项目 BOM POM 与 `material3-1.4.0-sources.jar` 为准。

## 推断与待验证

- 变量字体候选可用一个文件覆盖多个字重，但 SC Sans 变量子集仍约 17 MiB，SC Serif 变量子集约 24 MiB；两者都内置会形成显著包体开销。单 Regular 静态 OTF 小一些，但 Compose 现有 `Typeface.createFromFile` 对这两份具体 OTF 在 API 24/不同 ROM 的解析情况本轮没有跑设备验证。
- API24 是项目支持边界；本轮没有找到 Android API 24 上这几种 variable font / OTF 文件的项目级运行证据。项目现状只证明 `Typeface.createFromFile` 是为避免 Compose `Font(File)` 的 API26 限制而选择，不证明候选文件格式和字重轴在 API24 上兼容。字体上线前应在 API24 和当前 Android 版本各测加载、粗细映射、CJK 覆盖，避免回退或启动异常。
- 若要同时提供多种常规字体，可把系统默认作为零体积项，再评估只打包一个 SC 子集常规字体；是否加入衬线/文楷属于包体与个性化权衡，本次只提供体积和许可证证据，不作最终选型。
- 向 Expressive 靠拢可先保留当前 `scheme` / 自定义影院色并梳理公开组件自身的形状、状态反馈及可控动画；不能直接以 1.4.0 的内部 `MaterialExpressiveTheme` / `MotionScheme.expressive()` 为应用接入点。对全局动效方案若要采用 Expressive motion，需要先找到该版本公开可用的 API 或由项目自己的动效实现覆盖；并应核查 `NavHost` 与 `NetImage` 直用 `tween` 的路径是否遵守系统「移除动画」倍率。

## 未覆盖

- 没有下载、提交、解析字体文件，也没有在 API24 实机/模拟器验证字体加载。
- 没有完整盘点 Material3 1.4.0 所有 expressive 组件和官方设计指南；当前结论针对主题/动效入口的 API 可见性。
- 没有实测 APK 压缩后的字体增量、启动内存或字体渲染性能。
