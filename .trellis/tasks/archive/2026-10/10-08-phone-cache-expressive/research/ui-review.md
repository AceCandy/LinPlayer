# UI 批独立审查

范围：本批手机 UI 与相关 Gradle/打包改动；依据 CodeGraph 和当前工作树抽查，未重跑测试。未发现有充分证据支持的阻断问题。

## 已核实

- PiP 状态在 `PlayerPage.kt:237-245` 从 Activity 初始值读取，并注册/移除 `addOnPictureInPictureModeChangedListener`。入口 `MainActivity.kt:199-208` 要求 API O+、非多窗口、`app.wantsPip`，Home leave 时以 16:9 进入。`PlayerPage.kt:963-966` 的 PassiveProgress 条件含 `!inPip`，PiP 隐藏逻辑闭合。系统回调时序仍需真机验证。
- 真实位置：Media3 在 `PlayerPage.kt:510-521` 每 250ms 读取 `currentPosition`；MPV 在 `PlayerPage.kt:554-567` 订阅 `player.status`，无状态轮询。PassiveProgress (`PlayerPage.kt:1175-1189`) 只画 Canvas，不含点击/手势处理。调用处 `PlayerPage.kt:963-966` 用底部 `WindowInsets.safeDrawing` 留出安全区。未发现手势冲突的代码证据，仍需真机目视验证。
- OSD/锁/转屏：`PlayerPage.kt:743` 定义 controlsVisible；`963-966` 仅在隐藏控件、无 panel、非退场/转屏、非 PiP 且播放已就绪等条件下显示进度。锁屏时进度可显示，而解锁按钮仍单独绘制 (`968-973`)。
- 字体迁移：`UiPrefs.kt:110-116` 只接受 `""/sans/serif`，未知值（包括旧路径）回退空值并持久化；`157-160` 设置时校验允许值。`Theme.kt:149-157` 从 Android font resource 建 FontFamily，注释声明兼容 API24。偏好加载逻辑未见状态污染；API24 真机加载未验证。
- 背景模糊：`DetailBackgroundBlur.kt:13-25` 在 `Dispatchers.Default` 执行，最大边长 320px，读取 input 到新 IntArray，生成独立输出 Bitmap；未修改共享 input。固定 `cacheKey` 为 `detail-background-blur-320-r4-v1` (`11`)。NetImage 请求记忆键包含 ctx/url/backgroundBlur 并仅背景模式附加转换 (`Cards.kt:95-98`)。调用仅见 `DetailPage.kt:800,866`。未发现共享可变缓存状态。`createScaledBitmap` 的 `small` 未显式 recycle (`DetailBackgroundBlur.kt:17-19`)，暂不足以构成阻断证据。
- NetImage API 新参数位于 `placeholder` 前 (`Cards.kt:74-82`)。当前全仓调用未见旧 positional placeholder 调用；新详情头图调用使用具名 `backgroundBlur` (`DetailPage.kt:800,866`)。
- `PhoneRoot.kt:91-92` 固定手机主题背景并移除 `WallpaperLayer`。壁纸选择仍在 ThemePicker (`ThemePicker.kt:87,124`)，所以手机全局/插件壁纸不再渲染是可见行为变化，应由需求覆盖；没有发现其它根布局副作用证据。
- 动效：`Theme.kt:171-178` 注册观察 `Settings.Global.ANIMATOR_DURATION_SCALE` 并更新 motion；`Motion.kt:91-105` 的 `lpTween` 在倍率为0时使用0ms，`lpSpring` 也在 `scale<=0` 时使用0ms tween。`R.xl=28.dp` (`Theme.kt:105-109`)，MaterialTheme 提供 M3 Shapes (`199-203`)。这些代码不能证明全项目所有动画调用都被包装器覆盖；本次未扫描整个动画调用面。
- 包体阈值：`scripts/pack-android.sh` 将 arm64 budget 从60MiB调整为80MiB，其它 ABI 保持60MiB。实际比较先对字节数整数除以 MiB (`sz=bytes/1024/1024`)，所以 `<=80` 可允许小于81MiB。未构建验证字体带来的实际体积增量，无法判断80MiB余量是否合适。

## 尚未验证 / 存疑

- PiP 状态回调时序和 3dp 条在各厂商系统栏/手势导航下的实际观感。
- API24 设备加载打包字体；字体许可及固定提交的完整核对。
- M3 Shapes 对实际组件的覆盖效果，及全局是否存在绕开 `lpTween/lpSpring` 的未适配动画。
- 新字体对 arm64 APK 的真实体积和80MiB预算余量。
