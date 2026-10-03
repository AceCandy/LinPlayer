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
