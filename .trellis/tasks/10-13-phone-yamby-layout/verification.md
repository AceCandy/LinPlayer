# 实现与验证记录

## 本轮结果

- 时间/总长与滑杆同排，横屏上下集位于单集标题左侧。
- 竖屏16:9视频、完整顶部快捷入口、中央播放控制、横滑操作行、视频下方时间进度行和现有分集横轨；点击方向不重启播放，点击分集先停止旧集。
- 手机原生无定量等待仅动画，原说明保留给读屏；实际数值、业务说明、错误和重试保留。
- 视频叠层只覆盖videoHeight，竖屏错误在可滚动内容区保留完整原因和重试/返回。方向生命周期、NextUp 10%且10–30秒窗口、播放停止屏障和动画生命周期未改。
- UI_MOBILE与Android规范已同步；三次只读独立复核中的确定问题已修正，最终复核未发现新的源码问题。

## 先失败后通过

- 初始横屏时间几何、竖屏缺入口和行内可见等待文字回归均在修改前失败。
- 视频叠层误含48dp进度行：等待动画中心断言在修正前失败，使用videoHeight定位后通过。首次仅查询“正在打开”未命中测试实际缓冲状态，随后按两种真实等待标签查询，确认几何缺陷。
- 竖屏长错误加1.3字号：返回按钮不可见，移至可滚动内容区后可见。测试最初错误期待只有Player的NavHost pop会销毁页面，修为验证返回点击调用leave并请求UNSPECIFIED；最终回归通过，不声称此测试验证了真实导航出栈。

## 回归范围

分批共116条相关回归通过，无剩余失败或跳过：

| 测试类 | 条数 |
|---|---:|
| PhoneEnginePlaybackTest | 38 |
| PhonePlayerOsdTest | 9 |
| PhonePlayerPanelTest | 7 |
| PhoneLoadingTest | 5 |
| MaterialLoadingLifecycleTest | 1 |
| PhoneDetailCacheTest | 37 |
| PhoneAggregateCacheTest | 6 |
| PhoneManagementUiTest | 6 |
| PhonePrimaryProgressTest | 1 |
| PhoneLiuyingTest | 6 |

- 首轮10类115条全通过；独立复核后重跑受影响6类96条全通过。
- 新增长错误回归后，最后3类54条中原有53条通过；长错误用例完成正确断言后单独通过。最终生产代码与此3类执行时一致。
- 每次Gradle运行会覆盖本地XML报告，当前XML是最后的单条长错误成功结果；本表记录本轮分批执行结果，不声称当前报告包含116条。
- 不存在的PhonePrefetchSettingsTest模式未执行，不作为验证证据；本轮没有修改或重测搜索流程。

本机执行环境：`apps/android`，项目JDK/Android SDK/Gradle缓存，`--offline --no-daemon :app:testDebugUnitTest`，逐类或单方法`--tests`，`-Proborazzi.test.record=true`。最后单条为`PhoneEnginePlaybackTest.竖屏长错误仍可重试和请求退出`。

## 渲染与门禁

- 已查看横屏深浅主题、640×360与1.3字号、竖屏OSD、真实PlayerPage内容与分集、320dp小屏大字、最后竖屏长错误截图。测试图在忽略目录 `apps/android/app/build/player-osd/`。
- 新布局测试断言时间/滑杆及上下集/标题排列、按钮命中不重叠、方向和倍率等回调、窄屏横滑访问截屏；真实页面测试断言16:9视频、等待中心、当前集定位与停止顺序。
- `python3 scripts/check-android-args.py`：255处调用参数通过。
- `git diff --check`：通过。
- 已跟踪与未跟踪文本隐私扫描：没有新增敏感内容，存量410处基线未改。裸版本号被误识别为地址，改用带v前缀的版本标记后通过，未新增豁免。
- 参考端设备运行记录见 `research/yamby-layout.md`；本轮模拟器、容器、测试服务及临时导出均已清理，仅保留被忽略的最终参考图和测试截图。

## 未验证与交付边界

- LinPlayer本轮没有安装到模拟器或真机；实际系统转屏、安全区、Surface透出、首帧、PiP、音视频与性能仍未进行设备验收。JVM布局和回调回归不替代这些验证。
- 没有逐一运行参考端全部按钮或录制参考端loading文案；等待仅动画来自用户明确要求。
- 用户已要求提交归档，本轮实现与验证材料按此授权提交并归档。未编译新APK，上一轮 `build/android/app-arm64-v8a-release.apk` 不含本轮修改，不能当作本次交付产物。
