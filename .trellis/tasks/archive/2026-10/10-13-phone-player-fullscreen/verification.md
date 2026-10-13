# 验证结果

## 修改与根因
- 原竖屏分支按片源比例选择顶部小窗；现在所有片源统一全屏区域，保持原比例适配，删除下方常驻详情/分集。
- 原NextUp用end padding缩窄整个横屏底栏；现在卡片独立叠在实测底栏上方，保留右侧倍率空间，按实际末端安全区缩减卡片宽度。窄卡片隐藏装饰图标，文本省略但点击/语义保留。
- 剧集通过更多菜单接回已有选集面板；电影不显示，切集仍走停止屏障。

## 故障复现与回归
- 修改前，360×800窗口的视频区域仅360×203，整页几何断言失败。
- 修改前，640×360片尾提示将滑杆从277缩为85，底栏几何不变断言失败。
- 第一版修正后，注入24像素右侧安全区时卡片与快进按钮重叠；安全区确实使字幕按钮右边缘移动24像素，非无效注入。计入末端inset后通过。
- 最终本地JVM渲染回归77项通过：PhoneEnginePlaybackTest 52、PhonePlayerOsdTest 11、PhonePlayerMenuTest 7、PhonePlayerPanelTest 7。
- 覆盖16:9/9:16实际媒体元数据、MPV/Media3同页转屏且play一次/stop零次、Media3 Surface尺寸、电影菜单、菜单切集停止顺序、长错误返回、锁定位置、片尾显隐、底栏几何不变、所有交互命中不重叠。
- 片尾几何覆盖640×360、320×640大字、640×360右侧24/48像素安全区、568×320及其右侧24像素安全区。
- 执行命令：在项目JDK21/Android SDK/Gradle缓存环境下，`apps/android/gradlew -p apps/android :app:testDebugUnitTest --tests '*PhoneEnginePlaybackTest' --tests '*PhonePlayerOsdTest' --tests '*PhonePlayerMenuTest' --tests '*PhonePlayerPanelTest' -Proborazzi.test.record=true`。
- Android参数门禁249处通过；修改与新增文本隐私扫描无新增问题；`git diff --check`通过。两次独立只读复核，最终无阻断项。

## 实测与局限
- Yamby 2.1.0.11实际解码合成视频并操作横屏、默认竖屏、收起详情后的全屏竖屏与片尾提示，见research/yamby-runtime.md。
- 本端截图已查看，保留在忽略目录build/player-layout-reference；背景为合成窗口，不作为真机解码证据。
- 已按用户要求生成手机arm64-v8a release APK（2.0.0，29,664,882字节），交付于build/android/app-arm64-v8a-release.apk，与Gradle产物cmp一致。apksigner验签通过（v2/v3；API24验证通过v2）；13个原生库均为AArch64 ELF，采用ZIP压缩且Manifest extractNativeLibs=true，配置一致。
- 未提交Git；未验证本端真机安装、视频输出、系统旋转动画、PiP、性能及各厂商系统栏行为。
- 已关闭本轮隔离模拟器、测试服务和ADB，删除临时容器镜像、AVD、脚本、媒体、日志、账号环境与控件树，仅保留脱敏对照截图。

## 片尾胶囊追加验证
- 用户确认精简为单行胶囊：默认视觉150×36dp、触摸48dp、2dp主题色底边进度；窄于150dp隐藏箭头并简写集数，窄于96dp优先保留集数或标题，完整读屏标签保留。
- 新增文案断言在旧实现上失败；最终PhoneEnginePlaybackTest 52项和PhoneNextUpTest 4项通过，PhonePlayerOsdTest 11项在此前同轮回归通过。
- 查看默认、小横屏大字、200%字体截图；核验透明边缘点击、长标题单行省略、下一集点击停止屏障、底栏几何与安全区命中不重叠。独立只读复核意见按源码与运行结果点验，最终未发现阻断问题。
- 共享Gradle测试结果目录遇到缺失文件/EOF及守护进程停止，改用独立项目缓存、输出目录和单次守护进程完成验证；临时隔离配置与输出已清理。
- git diff --check通过；未自动出包，未验证本端真机播放、系统读屏与厂商安全区。


## 安装后弹幕卡顿反馈
- 定位共享DanmakuLayer：长命帧循环仍逐帧追过期的4Hz位置，形成周期性速度波动。DmClock现在在采样间按帧间隔和倍率外推目标；dmTick纠偏按经过时间计算；手机/TV均接入buffering，冻结与暂停seek使用实际采样。
- 修复前忠实回归失败：60Hz/0.5x连续帧移动量1.59px，预期2.42px；真实Compose Canvas的250ms移动量13px，预期约24px。第一版固定每帧8%在90Hz/23.976fps量化采样仍失败，改为时间等效纠偏后通过。
- 最终分开执行60项通过：DanmakuClockTest 4、DanmakuLayerTest 3、LogicTest弹幕钟定向1、PhoneEnginePlaybackTest 52（后者使用Roborazzi记录模式）。覆盖60/90/120Hz、0.5/1/2/3倍率、23.976fps量化、暂停/缓冲/恢复、大幅正反seek，以及实际Canvas文字像素移动。
- Canvas状态变更测试需先waitForIdle再推进手动时钟，确保重组已应用；最初未等待导致暂停seek/缓冲恢复假红，修正测试同步后通过。未将假红作为生产故障。
- 全量LogicTest 49项另有既有失败：第573行未知内核值的otherEngine断言期待exo，而当前实现返回mpv；HEAD已存在同样矛盾，单独执行仍失败。本轮不修改无关内核规则/断言。
- 中间整组运行出现一次Gradle结果文件竞争；等所有本轮进程结束后串行、no-daemon验证。工作树同时存在片尾卡片的其它更新，保留其改动，最终按当前源码单独通过播放页52项。
- Android参数门禁249处、diff空白检查与21个修改/新增文本文件隐私扫描通过（存量基线未扩大）；独立只读复核无阻断项。
- 本轮没有重新打包、提交，也没有真机帧耗时/密集弹幕CPU与GPU验证；之前交付APK不包含本轮弹幕修复。真实设备流畅度仍不能由JVM渲染结论替代。小于等于1秒的seek沿用软纠偏语义。
- 已删除本轮临时测试日志；测试使用no-daemon完成，未启动调试服务。


## 全部未提交改动的最终审查与回归
- 核心完整门禁通过；Android共144项，143通过。唯一失败是既有LogicTest“长按播放键换的是另一个内核”的未知值预期，与HEAD现有实现不一致，非本次回归；本次相关96项全部通过。
- 相关覆盖：PhoneEnginePlaybackTest 52、PhoneNextUpTest 4、PhonePlayerOsdTest 11、PhonePlayerPanelTest 7、PhonePlayerMenuTest 7、PhoneDanmakuPanelTest 4、DanmakuSourcesTest 3、DanmakuClockTest 4、DanmakuLayerTest 3、LogicTest弹幕钟1。
- 未验证真机解码播放、系统旋转/手势返回与密集弹幕性能；未重新出包。
