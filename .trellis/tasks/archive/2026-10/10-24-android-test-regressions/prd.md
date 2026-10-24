# 修复 Android 全量回归失败

## 目标

修复 Android 全量回归中的 21 项失败，恢复真实有效的门禁，不通过取消测试或放宽行为要求刷绿。

## 背景与已确认事实

- 基线为 main db5a8921，工作树干净。上一轮 Android 全量 965 项：941 通过、21 失败、3 跳过；核心门禁通过。
- 9 项播放器组合测试未提供 LocalApp，在执行核心断言前失败；位置为 ExoPrefsTest、Media3DiagnosticsTest、PlaybackBufferTest、PlayerEngineLifecycleTest、StartupTimingTest 和 TvRefreshRatePlaybackTest。
- SeasonEpisodesTest 的替身未提供当前 emby.playbackSeason；PhoneLiuyingTest 两项仍硬编码封面 h=330。
- 其余失败包括 FloatingTabTest 语义/重复节点、PhoneBrowseUiTest 刷新入口/返回请求契约、PhoneLibraryUiTest 隐私参数、PhonePosterMotionTest 背景淡入、PhoneMedia3RecoveryTest 线程访问。
- 用户已批准：补齐宿主与替身，校准过期断言；对线程、淡入和搜索行为复现后判断测试或生产根因。没有已确认应废弃的覆盖。

## 要求

1. 保留偏好、缓冲、首帧、实例释放、播放恢复、导航位置、隐私、刷新与图片代理的实际行为覆盖。
2. 测试使用当前生产入口、命令和语义，严格核对身份、范围、尺寸、请求数量及错误后的内容保留。
3. 真正生产回归在所属共享入口修复；不新增依赖、不做无关重构，不改产品范围或兼容契约。
4. 不新增跳过、忽略或重试掩盖；原有跳过单独报告。
5. 不自动打包、提交或推送。

## 验收

- [x] 原失败用例逐组复现并通过；线程与淡入根因有可回归证据。
- [x] Android 全量测试零失败，无新增跳过；报告实际总数与原有跳过原因。
- [x] Android 参数检查和 git diff --check 通过。
- [x] 独立复核确认没有删减有效断言、隐私绕过、生产测试分支或无关改动。
- [x] 明确说明未验证真机播放与渲染、实服联调；清理临时调试产物和服务。

## 范围外

不扩展功能，不修改 Go 命令/生成绑定，不重新打包，不把 JVM 测试作为真机验收。
