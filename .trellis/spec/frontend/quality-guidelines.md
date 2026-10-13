# 外壳质量检查

## 渲染与交互

- 当前桌面是 Avalonia，Android 是 Compose。旧 CDP / WebView2 / Tauri invoke / DOM 焦点方式只用于 rust-final 历史代码，不用于验收当前原生页面。
- 桌面运行真实窗口核查布局、命中、DPI 与遮挡；视频层额外核查原生子窗口和 mpv 日志。
- 手机渲染覆盖真实尺寸、安全区、深浅主题和设置切换；Robolectric 只证明该环境中的组件表现，不能代替真机系统适配。
- 对齐参考播放器须按片源与布局核对正常播放的整页：视频、控制、进度及存在的下方内容面板；比较使用相同尺寸/字号/主题，等待态截图与“按钮存在”断言不能替代整页视觉验收。真实页通过递增播放采样进入播放态，视频背景及测试图片须标明来源；参考收起/手势语义须实际核实，静态展开图只证明展开形态。
- 播放器“竖屏”必须区分设备方向与片源比例；至少覆盖9:16片源在两个方向及16:9片源竖屏小窗。元数据接线用`Route.Player.ar=0`与当前实际版本的Video宽高，不能拿路由hint冒充已读取媒体尺寸。`Osd.portrait`表示窄屏排列，`compact`表示小窗，二者不得混用。
- 方向切换回归保留同一Activity/Composition并同时变更配置和窗口尺寸，断言play一次/stop零次；Media3额外核对实际SurfaceView尺寸。测试宿主须处理与生产相同的configChanges，配置驱动不等同系统旋转；合成窗口背景不等同解码画面。
- 全屏竖屏同时检查中央退进与右侧倍率的命中、上下集/标题/进度/底部操作的顺序、锁定恢复、片尾卡片、面板和错误页实际点击。冻结Compose时钟时不可直接等待滚动动画，滚动验证须推进时钟。
- Popup是独立窗口；与播放页入口比较时用实际WindowManager.LayoutParams的x/y加boundsInRoot换算，不能把Popup的boundsInWindow当作Activity坐标。截图捕捉真实Popup，关闭测试覆盖窗口返回/外点及退场后移除；冻结时钟需先确认状态已应用到窗口，不能用旧节点存在得到假绿。组件位图未可靠保留绘制层透明度时，仅能证明开合生命周期，不声称渐隐像素或真机流畅度已验。
- TV 静态截图不证明焦点正确；遥控器方向、确认、返回、滚动与换集需按键回归。
- 同功能的桌面 / 手机 / TV 与按钮 / 长按等入口按改动影响核查，不扩大为无关全平台重测。

## 可用验证入口

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

参考：[手机测试](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneThemeTest.kt)、[TV 页面截图](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/tv/TvPageShots.kt)、[TV 草稿](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/TvDraftShots.kt)、[TV 焦点](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/tv/TvFocusTest.kt)。

- 动效功能与性能证据分开：共享元素中间几何、图片淡入混色只证明路径正确；滚动卡顿反馈要核实空闲共享节点、屏外composition与图片解码，再用同设备同数据的帧耗时判断效果。禁止反复调幅度后仅以渲染回归声称流畅；真机未连接时明确性能归因仍未确认。
