# 提交与归档验收

2026-10-27：按用户要求先修复回归失败，再提交当前改动并归档本任务。

## 交付范围

- TV 设置、登录、播放器向手机端对齐，保留遥控、刷新率等 TV 适配；移除仅供旧浏览入口使用的页面及测试。
- 包含 PRD 追加的手机播放器跳过片头片尾、失败重试与共享页面适配。
- 出包阻塞的 `LpCell` 回调显式声明 `(() -> Unit)?`；恢复弹幕测试仍依赖的 TV 测试夹具导入。
- 登录地址示例使用保留域名，不提交局域网地址示例、密钥、缓存或 APK。

## 回归收尾

- 原回归集 73 项有 3 项失败：两项 TV 截图仍匹配独立时间文字；一项快进上报测试未随上报职责迁移启动服务。
- TV 截图改为精确断言“当前 / 总长”完整文字，手机断言不变。
- 快进测试先确认页面请求了 `PlaybackService`，再创建服务、提供查询样本并推进主 Looper 时钟。继续断言三次快进目标累加，实际进度上报仍为样本位置且带当前 `item_id`；结束时销毁服务并清理绑定。
- 上述修复经过独立只读复核；服务生命周期与时钟要求已记录于前端质量规范。

## 已验证

- `:app:testDebugUnitTest` 定向运行 13 类、165 项，零失败、零错误、零跳过：DanmakuSourcesTest、PhoneEnginePlaybackTest、PlaybackServiceTest、PluginNavTest、SeasonSelectionTest、VisualAlignmentTest、SharedTvBrowseTest、TvFocusTest、TvPageShots、TvPhoneParityTest、TvPrivacyTest、TvRefreshRatePlaybackTest、TvTrackTimingTest。
- `python3 scripts/check-android-args.py`：189 处 UI 调用参数检查通过。
- `git diff --check HEAD` 通过；暂存文件敏感信息检查通过。
- 2026-10-26 TV 默认 armeabi-v7a Release 打包成功，形态、签名结构与体积门禁通过。此后登录示例与测试有调整，本轮未重新出包。

## 未验证与后续边界

- 未执行完整 Android 测试集，未做真实设备安装、遥控焦点手感、媒体解码、HDMI 刷新率切换或真实服务器进度上报验收。
- Robolectric 截图与状态采样不代表真实解码或设备播放验收。
- PRD 已明确延后的跨 Activity 换源导航不在本次闭环范围；未扩展 tvOS 或其它平台。
- 本轮仅本地提交与任务归档，不推送远端；跨端整体任务仍独立保留。
