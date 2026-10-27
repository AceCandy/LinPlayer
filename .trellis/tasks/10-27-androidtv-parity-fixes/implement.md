# 执行与验收

## 开工门禁

- [x] 用户同意创建任务。
- [x] 完成审查证据与相关代码研究，编写 PRD、技术方案和执行清单。
- [x] 向用户展示最终方案，并取得其后续明确实施批准（用户：开始做吧）。
- [x] 校验任务上下文清单，执行 task.py start，确认状态 in_progress 后再编辑产品代码。

## 顺序里程碑

1. [x] R1：TvPhoneParityTest 新增显式 Media3 上下集、显式 MPV 自动连播及自动模式跨切集回归，原实现三项均因内核错误失败；固定入口模式后 TvPhoneParityTest/TvFocusTest 共 15 项通过。实际 diff 已复核，停止屏障和非 Emby 的 MPV 限制未变。
2. [x] R2：TV 深浅首排片名断言在原三列布局均失败；按窗口扣除TV左右边距后复用宽屏计算（960x540 默认五列）。VisualAlignmentTest、PosterLayoutTest、PhoneWideLayoutTest、SharedTvBrowseTest 通过；已看浅色库截图，首排片名/年份完整可见。手机、横轨和插件列数覆盖未变。
3. [x] R3：TV 深浅电影首屏和真实遥控播放回归在原实现均失败；TV 单列主操作移到参数前，VisualAlignmentTest/SharedTvBrowseTest/PhoneDetailOptionsTest/SeasonSelectionTest 通过。独立复核指出直接请求焦点不足，补测复现返回按钮向下停留；TV工具栏显式连接可见已就绪主操作后，顶栏→返回→播放纯方向键及加载中/合集保护通过。插件隐藏/替换主操作不抛无效焦点错误。已看浅色详情截图，并核对 Activity 交接仍为 m1、TV 标记及非数据源参数。
4. [x] R4：偏好晚到、切核、重试和换集四项标题回归在原实现失败（初次起播也会覆盖先返回的详情）；标题清理改为按 itemId 的 effect，TvPhoneParityTest/TvTrackTimingTest 通过，未增加详情查询。新集等待资料时不显示旧集名。
5. [x] R5：新增四项规格回归，原实现在实际 ID 晚到、未知版本、换集残留三项失败；版本列表与实际 ID 派生规格，换集清列表、每次起播清身份并检查取消。TvPhoneParityTest/TvTrackTimingTest 通过，两个返回顺序都正确，未增加查询。再补两项不响应取消的迟到旧响应回归，临时移除新增取消屏障时两项均红，恢复后通过。
6. [x] R6：原实现四项因缺刷新入口失败；首页登记同一刷新回调，壳按当前条目持有并在离页撤销，顶栏追加独立动作并接左右焦点。SharedTvBrowseTest/TvPrivacyTest/PhoneHomeRefreshTest 通过，覆盖在途防重、完成后再刷新、普通/私密、详情往返、账号切换、本机/插件源及手机原手势；已看聚焦刷新截图。

各里程碑完成时在本清单记录测试命令、失败原因及通过结果，不在上一项未通过时继续下一项。

## 本地验证

项目工具链：JAVA_HOME=.toolchain/jdk-21.0.2，ANDROID_HOME=.toolchain/android-sdk，GRADLE_USER_HOME=.toolchain/gradle；从 apps/android 执行 Gradle。

按改动选择而非无意义全量运行：

```sh
./gradlew :app:testDebugUnitTest --tests 'xyz.linplayer.app.tv.TvPhoneParityTest'
./gradlew :app:testDebugUnitTest --tests 'xyz.linplayer.app.tv.TvTrackTimingTest' --tests 'xyz.linplayer.app.tv.TvFocusTest'
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true --tests 'xyz.linplayer.app.VisualAlignmentTest' --tests 'xyz.linplayer.app.tv.SharedTvBrowseTest'
./gradlew :app:testDebugUnitTest --tests 'xyz.linplayer.app.PosterLayoutTest' --tests 'xyz.linplayer.app.PhoneWideLayoutTest' --tests 'xyz.linplayer.app.PhoneDetailOptionsTest' --tests 'xyz.linplayer.app.PhoneHomeRefreshTest'
```

- 执行 python3 scripts/check-android-args.py、git diff --check，检查相关新增内容无敏感信息。
- 最后单独进行只读复核，确认六项要求、真实调用方、错误/取消与手机兼容性。
- 截图保留于已有忽略 build 目录，清理本次不再需要的临时文件；停止本次测试启动的后台进程。
- 按需更新 Android 规范中可执行契约，不自动提交、不出包；设备验证缺口如实列出。

## 最终状态

- 六项里程碑及追加R7完成；最终19组327项集中回归通过，0失败/错误/跳过。
- 参数检查、diff空白检查和本轮30个文件的隐私扫描通过；全仓隐私扫描历史误报见check.md。
- 已独立复核，补齐实际窗口/插件列数及纯方向键证据，额外修复详情工具栏向下不可达。
- 规范与UI正本已同步；未提交、未出包、未自动归档。真机及联调缺口见check.md。

## 方案 1 补充验证

- [x] 确认继续保留 TV 下载、截图、PiP、后台播放差异。
- [x] SharedTvBrowseTest 增加整剧/指定季分阶段等待、准确播放目标，以及季菜单/分集进入返回的三条按键回归，局部夹具按真实季关系返回。
- [x] 显式下行焦点改回默认搜索时三条新回归全部失败，恢复后通过；测试环境用非触摸模式并限定 Popup 节点，避免底层同名标签假绿。
- [x] 本轮未增加产品行为改动；整剧/指定季聚焦截图已查看，主操作与目标文字位于首屏。
- [x] 14组223项集中回归、189处参数检查和diff空白检查通过。
- [x] 浏览/播放/设置的剩余能力核查及边界写入check.md；媒体库一级入口、数据源首页刷新两项误报已排除。
- [x] 新增测试与证据说明已独立只读复核，无确定问题；22个文件的隐私检查通过，临时依赖导出已清理，1个Gradle后台进程已停止。
- [x] 插件失败换源的跨Activity导航方案已说明，用户明确授权实施；不扩大对外路由契约。

## R7 执行顺序

1. [x] 四项初始红测复现手机同源丢进度、跨插件未注册目的地、Emby未交还浏览栈及TV缺候选入口。
2. [x] 分离候选选择和导航；同源复用停止/换目标，跨源用内部Activity结果，保留进度/集序、错误/取消及私密边界，不扩展公开导航。
3. [x] 补Activity结果消费/返回栈、私密与慢请求回归。补测再复现TV吞取消后迟到起播、返回源详情重复自动播放，两项修复后通过；最后补齐TV同源成功与Emby分支，19组327项集中回归通过。播放器与浏览交接分别独立只读复核，未发现确定问题。
4. [x] 查看TV换源弹窗/失败页脱敏截图，更新检查记录、Android契约和UI正本；189处参数、diff及30文件隐私门禁通过；本轮1个Gradle后台已停止，不提交、不出包。

## R8-R12 首页与自适应修订（已实施）

- [x] 核查正式首页尺寸、轮播焦点及纵向搜索；用多卡真实TvRoot复现按下回跳，正常动画红/关闭动画绿；只读复核并记录推断边界。
- [x] 对照JetStream和Jellyfin的源代码及仓库截图，区分参考视觉与交互，不将源码/静态图当现场验收。
- [x] 按用户纠正撤销调序及实心块提案；保留隐藏媒体库开关的两条内容路径，改为黄色短线与栏目聚焦即切页；叠图前景方案已获认可。
- [x] 补查共享卡片、搜索/收藏/聚合、人物作品和管理类页面，区分确定缺口、已有适配与静态风险；主线程抽查调用和尺寸代码。
- [x] 用户确认仅栏目聚焦切页、动作入口仍确认的边界及最新实施方案。
- [x] 恢复多卡红测并修复纵向导航：TV轮播被动展示；覆盖隐藏开关两态、多卡续播、正常动效、离屏下一轨、行内左右与返回恢复。
- [x] 改顶栏状态/叠图前景：栏目获焦切换、黄色短线、动作入口确认；补获焦切页、快速连按、不重复导航、返回恢复及动作入口不误触发。导航项按稳定索引保留，获焦切页延后一帧执行，避免重组期间焦点丢失。
- [x] 补首页库卡/共享横轨/人物网格的TV自适应与图片请求尺寸，检查实际边距、文字、焦点和媒体库Fit；验证960x540及窄窗口纯尺寸回归，手机原有尺寸/动效回归通过。
- [x] 静态核查详情头图、数据源网格、设置和插件弹窗：已有适配保持，未发现需要凭固定dp直接修改的确定问题；本轮未扩大页面重构。
- [x] 独立复核、定向回归、真实渲染对照、参数/空白/隐私检查及关闭测试后台；不自动出包或提交。真机仍未验。
