# 状态归属

| 状态 | 拥有者 / 例子 |
|---|---|
| 账号、播放、下载、历史等业务状态 | Go 核心与命令结果，不在每端重复实现规则 |
| 安卓会话与页面请求入口 | AppState / LocalApp，页面用 app.call |
| 安卓设备呈现偏好 | UiPrefs，例如主题、字体和本机播放内核偏好 |
| 手机临时交互 | Compose 页面状态与导航路由 |
| TV 返回栈 / 焦点记忆 | TvNav 与既有 tv/kit 状态机制 |
| 桌面页面会话与返回栈 | Nav.Session 与 Nav |

UiPrefs 是纯本机呈现偏好的有边界例外，不把核心业务配置移进 SharedPreferences。
主题选择不通过核心 prefs 命令虚构字段；配置未支持的字段不能“返回成功”后假装落库。
缓存绑定路由 / 会话，换账号时不能继续展示其它账号的响应。

依据：[AppState](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/data/AppState.kt)、[UiPrefs](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/data/UiPrefs.kt)、[桌面导航](../../../apps/windows/LinPlayer.Desktop/Views/Nav.cs)。

## 季内连播

- 手机和桌面正常完播自动播放当前季下一集；TV 保留下一集卡、5 秒倒计时和本机自动播放开关。季末退出，不跨季。
- 分集由播放页加载：优先 `season_id`，缺失时仅在已知 `season_no` 下查 `series_id` 并过滤当前季；分页仍用 `emby.seasonEpisodes`。Android 共用 `playbackSeason(app, detail, itemId)`，当前集不在返回表中视为加载错误。
- 加载中、失败、成功但没有下一集是三种状态；完播等待在途请求，失败保留页面与重试入口，不能以空初始列表退出。旧目标或离页请求不得写回。
- MPV 完播需已推进、已知时长且位置在片尾 5 秒内；桌面保留连续两拍 EOF 判据。中途 EOF 不触发换集，带 `item_id` 的旧 Emby 状态不得污染新集。
- 自动与手动换集复用停止屏障、当前服务器和已有续播/轨道记忆；播放页替换目标，不累积返回栈。每集重建连播目标，禁止仅由详情入口传单个下一集。
- 回归：`PhoneEnginePlaybackTest` 验证连续三集、Media3 完播、慢请求及中途断流；`TvFocusTest` 验证倒计时、开关、季末、加载等待和失败重试；`tools/seekcheck/SeasonPlaybackCheck.cs` 通过真实页面/核心 HTTP 解析验证桌面季内队列与服务器保留。上述回归不替代真实设备播放与上报联调。
