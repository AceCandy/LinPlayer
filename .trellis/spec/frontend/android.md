# Android 手机与 TV

## 入口与主题

- LinPlayerApp 初始化进程级核心；MainActivity 只按形态选择 PhoneRoot / TvRoot，Activity 重建不能重复初始化核心。
- 手机主题在 ui/theme/Theme.kt，由 LpTheme 提供 Lp.colors；R / Sp / Dim 使用当前源码刻度，不用桌面刻度。
- 手机共用组件在 ui/components；TV 采用 tv/kit 的 TvC / TvSp / TvR / TvW。
- 手机服务器探测结果按 `server` 匹配账号、`ok` 转为卡片状态 `ok` / `down`；渲染端不可再按 `up` 判成功。缺少探测结果表示未检测，与连接失败区分；`PhoneManagementUiTest` 核验成功色及三态文字。
- 手机主导航是 Navigation Compose / Route；TV 使用 TvNav，换集与导航轨切页遵守既有返回栈和焦点记忆。
- 不使用 Material You 动态取色替换既定配色。浅色正文、次级与弱提示文字在 bg 和 s1/s2/s3 合成底上至少 4.5:1，由 PhoneThemeTest.lightTextContrast 断言；实际组件与叠图仍需渲染核查。
- 深色 → 浅色 → 深色要检查背景、说明、箭头、数值和选中项；设备强制深色的表现不能只用 JVM 截图下结论。
- PhoneRoot 始终铺当前主题 bg，壁纸在其上绘制；不能因配置了壁纸而让根背景透明。标准 Material 表面色先将 s1/s2/s3 合成到 bg；共用面板与按钮使用合成后的实色主题表面，不加玻璃高光；首页服名不加底色。
- 浅色文字叠图时必须在文字区铺浅色渐变底，不能沿用固定黑遮罩；浅色主按钮各个渐变停色都要满足文字对比度。系统栏跟应用主题同步，API 24/25 的白色导航图标保留深色底。

## 资源与出包

- API 分层主题属性同时检查 values-vXX 和 values-night-vXX；night 资源优先级可能遮蔽版本资源。
- JNI 入口在 release R8 下必须保留。libmpv.so 不入仓，拉取后校验 ELF / ABI；不能把指针文本当运行库。
- release signingConfig 必须实际接入；签名材料在忽略的本地文件。最终 APK 统一交付路径见 [构建与交付](../shared/build-release.md)。

- 首页合集和各库最新按 LazyColumn 可见栏目触发，任务归页面作用域，离屏不重复取消；刷新恢复空栏目占位，账号变化/离页取消失效任务。
- 手机首页不展示或请求「接下来看」。每次恢复 RESUMED 更新继续观看；首页采用紧凑服务器栏，媒体库 → 继续观看 → 各库最新 → 合集，不展示大轮播、不调用随机推荐；库入口及最新结果复用页面缓存，首次/显式下拉/失效通知才重取。刷新主请求组结束、失败或取消后必须收起指示器。

依据：[入口](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/MainActivity.kt)、[手机主题](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/theme/Theme.kt)、[TV 主题](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/tv/kit/TvTheme.kt)、[主题回归](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneThemeTest.kt)、[安卓历史](../../../docs/lessons/android.md)。

- 首页栏目保留左侧竖杠；有更多入口时点标题导航，不另画「更多」。首页媒体库首行只展示Fit封面，has_primary=false不请求图片并居中显示库名；有图时加载/失败也显示库名，成功后隐藏；不能恢复封面下重复库名。首页设置无圈，搜索位于悬浮栏聚合左侧，作为普通页面压入原Tab栈。

- 手机登录后浏览页统一显示悬浮导航，播放/登录/添加服务器除外；纵向用户滚动累计24dp切换显隐并滑移淡入淡出，横滑不触发，换页恢复。底部留白固定，当前Tab在二级页点击返回根页。搜索不重复压栈，库页全局搜索保留viewId，二级页不另放导航搜索按钮。库页排序入口在库名同一行最右侧：无底色递减横线图标、当前排序和方向箭头；点击打开排序/升降序及条件面板，默认无筛选行，已选条件才显示清除条。方向按库保留并进入filterKey与请求；切换排序取消旧分页任务。
