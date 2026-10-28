# 实施与核验记录

## 改动

- 生产仅改共享 `DetailPage.kt`：TV 标签、主操作和媒体参数进入沉浸头部，紧凑选季与分集卡保留首屏空间；手机沿用原布局。
- TV 剧/季使用既有真实待播放分集媒体请求；切目标清除旧选择，字幕按目标与版本隔离。视频仍是只读规格，音轨仍为语言偏好，不新增核心协议。
- 参数向下明确进入已挂载且有可聚焦内容的选季/分集区域；空选集继续自然下行，上返完整恢复头部。

## 红绿回归

- `cinemaSeriesShowsTargetOptionsAndEpisodeArtworkInFirstViewport` 与 `cinemaSeriesRemoteOptionsReachPlayerWithTargetIdentity` 在修改前均失败：剧集缺参数，播放下行无法进入版本。修改后通过。
- `cinemaSeriesTargetChangeDropsOldParametersWhileMediaLoads` 挂起下一季目标的媒体请求，验证旧参数消失、新目标恢复默认版且不继承手选字幕；起播检查目标、版本和字幕身份。
- `cinemaEpisodeWithEmptySeasonDoesNotTrapFocusInParameters` 在仅判断挂载时失败，加可聚焦内容判定后通过；空季/空分集的单集页可从版本下行到简介。
- 定向最终验证包含空选集用例和全部 TV 视觉用例，共 6 项，0 失败、0 错误、0 跳过。
- 导入错误及读取 Activity 启动结果前缺少 `waitForIdle()` 属测试环境/同步问题，不计为产品故障红例。

## 独立复核

- 已分开核验媒体身份、手机隔离、插件入口和遥控焦点；最后对空选集判定与参数 Down 路径再次只读审查，未发现确定性回归。
- 拒绝“剧集版本恒为空”的审查意见：`versions` 先取身份匹配的 `currentMedia`，仅当前媒体缺失时剧集回退空列表；参数及实际起播断言覆盖该链路。
- 拒绝“正常参数 Down 未测试”的缺口判断：`cinemaSeriesRemoteOptionsReachPlayerWithTargetIdentity` 明确从字幕 Down 到选择季，再 Up 到版本及播放；既有换季用例另验到首集、切集和返回恢复。
- 加载中/请求失败时进入分集重试控件的完整按键序列仍未新增专门用例，只核查了分支及目标存在性。

## 实际渲染

- 已目视 `build/visual-alignment/tv/{dark,light}-{detail,series}.png`：电影/剧集深浅主题的标题、操作和参数可读，剧集完整首卡及标题可见。图片与整卡的未裁切边界另有断言。
- 已检查 `apps/android/app/build/shared-tv/detail-large-text.png`：长标题及大字号自然撑高、参数换行，不强制压入首屏。
- 图片来自实际 Compose/Robolectric 页面，背景及媒体为合成测试素材，不是原型效果图，也不是真机/真实服务器截图。原型照片未加入产品资源；渲染产物均在忽略目录。

## 门禁与交付

- Android 参数门禁：189 处调用通过；diff 空白检查通过。
- 最终全量 `:app:testDebugUnitTest -Proborazzi.test.record=true` 成功，耗时 2 分 51 秒；XML 汇总 104 个测试类、1032 项：1030 通过、0 失败、0 错误、2 跳过。跳过项来自既有 `StartupDiagnosticsTest` 的环境前置条件，不计为已验。
- 相关测试类：`SharedTvBrowseTest` 43 项、`VisualAlignmentTest` 10 项、`PhoneDetailOptionsTest` 26 项、`PhoneDetailCacheTest` 37 项、`SeasonSelectionTest` 27 项及 `PlaybackReturnTest` 2 项全部通过。
- 隐私门禁扫描全部 13 个修改/新增文件，无新增真实地址或凭据；未改 allowlist 或 baseline。
- 一次全量执行在完成前收到终止信号，没有有效报告，不计为通过；保持同一测试范围重跑成功，不改测试或生产逻辑规避。
- 实施验收后用户授权提交并归档本任务；未打 APK、未安装、未推送。
- 未验真实服务器起播与切轨、实体遥控重复事件、设备过扫描和帧耗时；测试不能证明真机流畅度。
