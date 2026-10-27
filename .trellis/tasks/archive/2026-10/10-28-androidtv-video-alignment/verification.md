# 集成验证与交付边界

## 实现范围

四个子任务依次实现：返回栈和首屏回顶、TV图片/整卡动效、首页焦点/密度/导航、详情排版和背景取色。只修改Android共享浏览的TV分支及配套测试，不新增缓存框架、核心接口或独立浏览栈。文档同步至 UI_TV 与 Android/质量规范。

## 故障到回归

- `rootBackKeepsHomeAndDetailReturnDoesNotArmExit`：修复前失败，修复后通过；根页返回不清空导航栈，详情退页清退出计时。
- `seriesRemoteUpRestoresWholeHero`：修复前失败，修复后通过；详情下行再上返恢复完整头图。
- `switchingFromFavoritesRestoresHomeHeroWithoutManualScroll`：栏目切首页回顶；既有详情/演员/播放返回仍恢复来源焦点。
- `compactLibraryHeaderMovesDownToFirstPoster`：增强为先右移七次让首卡离屏，再回标题向下，修复前失败；显式横轨回零后首卡聚焦通过。
- `televisionDecodedImageDoesNotFadeAgainAfterScrollingOffscreen`：修复前失败；冻结时钟三次往返直接显示目标像素通过。缓存与淡入分开，不改手机动效。
- `focusedSurfaceGrowsVisuallyWithoutMovingItsFocusBounds` 与 `actualPosterGrowsOutsideItsFrameWithoutWhiteCaptionStrip`：像素外扩且焦点边界不变，真实 MediaCard 无片名白条。
- 刷新回归覆盖首页确认、防重、普通/私密能力区别与离页撤销；插件列数覆盖改为九列，与默认七列区别断言。
- 首次全量 1027 项中三项失败：浅色白字对比度、两项旧测试调度异常。浅色溶解起点调整保住对比度；有后台取色的集成测试按既有规范用 v2 Compose rule，不改生产调度规避测试。
- v2 后首焦点回归暴露节点晚挂载，请求失败不再消耗 pending，最多十帧等待；异步目标测试随后通过。

## 独立审查

分阶段代码/导航/动效审查和最终代码/视觉审查已执行。最终代码审查的“无 onMore 标题无法接收 Down”不属于故障：非操作标题原本不应聚焦；仅限制 callback 的创建条件，不添加无意义焦点。视觉审查发现无轮播可能沿用旧取色，已将背景应用条件与非空轮播绑定。异步取色更新接受为非阻塞背景行为。

浅色头图向浅正文的过渡较短，保留可读性优先；实际观感仍需电视验收。演员文字、分集去描边和无轮播色调复位尚无逐像素专用断言，不把整页渲染通过当作这些细节的独立像素证据。

## 渲染证据

- `build/visual-alignment/tv/`：深浅首页、详情、剧集、首页下滚及其它浏览页；覆盖窄屏与大字号。
- `apps/android/app/build/shared-tv/`：真实 MediaCard 焦点像素与四组合回顶等按键证据。
- 主线程目视复核深浅详情及下滚首页。素材是合成测试图，不是真实海报或实体电视截图；截图目录被忽略，不入仓。

## 未验与交付边界

未进行实体遥控连按、电视过扫描、真实海报观感、实际服务器联调或同机帧耗时对比。不能宣称缓存完全解决卡顿或真机所有问题均已实测消除。未运行 assemble，未生成/安装 APK，未提交、推送或归档。没有启动调试服务或模拟器。

## 最终门禁

- 最新源码重新运行完整 `:app:testDebugUnitTest -Proborazzi.test.record=true`：BUILD SUCCESSFUL；1028 项，0 失败、0 错误、2 项既有跳过。
- `scripts/check-android-args.py`：189 处参数名检查通过。
- `git diff --check`：通过；本次修改及新增任务文件隐私扫描通过，不变更存量基线或豁免。
- 最终复核首卡回调条件、无轮播背景条件、插件九列覆盖及导航/返回改动；无新增确认阻断问题。

## 提交前独立审查

用户随后授权审查通过后提交归档。两路只读审查分别核查导航/状态生命周期与共享组件/视觉/手机回归，未发现可复现的阻断缺陷；主线程点验详情初始聚焦及动作挂载代码。最多十帧的首焦点等待是已知边界，慢设备未实测，不因推测加入无界逐帧重试。栏目切首页写回基于当前同步导航实现，已有收藏切首页回归。提交只含本轮代码、规范、测试与五个父子任务，不推送、不出包，其它任务不归档。

提交前再次执行全量测试入口成功；源码未改，Gradle 使用 UP-TO-DATE 结果，不计为重新执行全部测试。保留最近完整运行的 1028 项、0 失败、0 错误、2 跳过记录；参数及本次文件隐私检查重新通过。
