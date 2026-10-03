# 请求与页面生命周期

保留既有规范路径；这里的生命周期是 Avalonia / Compose，不是 React hooks。

- 异步任务按当前路由、账号、筛选与分页参数启动；独立请求可并发，不能用附加功能形成首屏等待屏障。
- 离页、账号切换、筛选变化时取消失效请求；不能让旧响应覆盖当前页面，不重复无界预加载。
- Compose 进入页面的任务使用合适 key 的 LaunchedEffect；需要资源清理时用 DisposableEffect。用户点击触发的协程使用受管理作用域。
- 仅属于当前页面的任务不得放进进程级作用域；长期监听明确拥有者和停止条件。
- Avalonia 通过 CoreClient 的 CancellationToken 与页面有效性判定安排请求，UI 更新经 Dispatcher.UIThread。
- 测试必须给旧请求完成的机会，再断言它未改写新状态；不能在清理已使候选集为空后得到假绿。

代表路径：[手机媒体库](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/LibraryPage.kt)、[手机详情](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/DetailPage.kt)、[桌面媒体库](../../../apps/windows/LinPlayer.Desktop/Views/LibraryPage.cs)、[桌面核心接入](../../../apps/windows/LinPlayer.Desktop/Core/CoreClient.cs)。
