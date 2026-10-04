# Android 手机与 TV

## 入口与主题

- LinPlayerApp 初始化进程级核心；MainActivity 只按形态选择 PhoneRoot / TvRoot，Activity 重建不能重复初始化核心。
- 手机主题在 ui/theme/Theme.kt，由 LpTheme 提供 Lp.colors；R / Sp / Dim 使用当前源码刻度，不用桌面刻度。
- 手机共用组件在 ui/components；TV 采用 tv/kit 的 TvC / TvSp / TvR / TvW。
- 手机主导航是 Navigation Compose / Route；TV 使用 TvNav，换集与导航轨切页遵守既有返回栈和焦点记忆。
- 不使用 Material You 动态取色替换既定配色。浅色正文、次级与弱提示文字在 bg 和 s1/s2/s3 合成底上至少 4.5:1，由 PhoneThemeTest.lightTextContrast 断言；实际组件与叠图仍需渲染核查。
- 深色 → 浅色 → 深色要检查背景、说明、箭头、数值和选中项；设备强制深色的表现不能只用 JVM 截图下结论。
- PhoneRoot 始终铺当前主题 bg，壁纸在其上绘制；不能因配置了壁纸而让根背景透明。标准 Material 表面色先将 s1/s2/s3 合成到 bg；共享玻璃按钮和服务器胶囊使用 chip/fg 语义色。
- 浅色文字叠图时必须在文字区铺浅色渐变底，不能沿用固定黑遮罩；浅色主按钮各个渐变停色都要满足文字对比度。系统栏跟应用主题同步，API 24/25 的白色导航图标保留深色底。

## 资源与出包

- API 分层主题属性同时检查 values-vXX 和 values-night-vXX；night 资源优先级可能遮蔽版本资源。
- JNI 入口在 release R8 下必须保留。libmpv.so 不入仓，拉取后校验 ELF / ABI；不能把指针文本当运行库。
- release signingConfig 必须实际接入；签名材料在忽略的本地文件。最终 APK 统一交付路径见 [构建与交付](../shared/build-release.md)。

- 首页合集和各库最新按 LazyColumn 可见栏目触发，首屏轮播所需库的最新列表提前加载并共用结果，任务归页面作用域，离屏不重复取消；刷新恢复空栏目占位，账号变化/离页取消失效任务。
- 手机首页不展示或请求「接下来看」。每次恢复 RESUMED 更新继续观看；轮播按首页库序复用最新列表、去重最多五条，不调用随机推荐；库入口及最新结果复用页面缓存，首次/显式下拉/失效通知才重取。刷新主请求组结束、失败或取消后必须收起指示器。

依据：[入口](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/MainActivity.kt)、[手机主题](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/theme/Theme.kt)、[TV 主题](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/tv/kit/TvTheme.kt)、[主题回归](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneThemeTest.kt)、[安卓历史](../../../docs/lessons/android.md)。
