# 外壳目录

| 路径 | 职责 |
|---|---|
| `apps/windows/LinPlayer.Desktop/App.axaml.cs` | Avalonia 启动、主题 / 字体初始化 |
| `apps/windows/LinPlayer.Desktop/Views/` | 页面、窗口、Nav、Tok 与共用呈现 |
| `apps/windows/LinPlayer.Desktop/Theme/` | Tokens.axaml 与 Controls.axaml |
| `apps/windows/LinPlayer.Desktop/Core/` | CoreClient、事件泵与核心接入 |
| `apps/android/app/src/main/kotlin/xyz/linplayer/app/` | LinPlayerApp、MainActivity、PhoneRoot |
| 安卓 `ui/pages/`、`ui/components/`、`ui/theme/` | 手机页面、共用组件、主题 token |
| 安卓 `tv/`、`tv/kit/` | TV 页面、导航、焦点与独立组件刻度 |
| 安卓 `data/`、`core/` | AppState / UiPrefs 与 CorePort 接入 |
| 安卓 `src/test/`、`src/androidTest/` | 本地 JVM 渲染回归、设备端测试 |

Windows / Linux 共用桌面源码；Android 手机 / TV 同工程，MainActivity 按形态分流。
生成绑定从 `bindings/` 接入，不能在宿主目录复制后手改。
不新增 React hooks、Tauri invoke 或网页路由作为原生壳的接入方式。

依据：[桌面入口](../../../apps/windows/LinPlayer.Desktop/App.axaml.cs)、[安卓入口](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/MainActivity.kt)、[Gradle 绑定接入](../../../apps/android/app/build.gradle.kts)。
