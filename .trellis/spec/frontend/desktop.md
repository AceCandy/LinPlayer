# Windows / Linux 桌面端

## 组件与风格

当前为 C# / Avalonia，共用源码位于 apps/windows。页面继承 PageBase，使用既有 Nav、反馈、图片与加载入口。
色彩用 Theme/Tokens.axaml 与 `Tok.Of(key)`；不在 Application 初始化前缓存静态画刷。

桌面风格刻度由 `scripts/check-style.sh` 检查：

| 项目 | 规则 |
|---|---|
| 圆角 | 0、6、10、999 |
| 间距分量 | 0、2、6、10、14、18、26、34、42 |
| 超过 42 的偏移 | 使用具名尺寸常量；BorderThickness 不属于间距刻度 |
| 注释正文 | 每段最多 6 行；标签行不算正文 |
| 注释装饰符号 | 每文件最多 3 个 |
| 颜色 / 图标 | 不透明色进 token；码位在 LinIcons 中存在 |
| 空 catch | 附近有允许静默的具体说明 |

手机 / TV 使用各自 token，不套用本表。

## 生命周期与平台约束

- 网络与磁盘任务不阻塞 UI；控件创建和更新回 Dispatcher.UIThread。
- CoreClient.CallAsync 支持取消令牌；导航和页面有效性规则见 [请求生命周期](hook-guidelines.md)。
- App 启动在建窗口前完成主题 / 字体加载；运行时取色不缓存旧主题。
- 不用 PowerShell 5.1 批量重写中文源码；非 ASCII 的 .ps1 保留 BOM。
- 无边框最大化与视频子窗口几何按当前 Win32 接入约束，检查工作区、DPI 与真实窗口；截图不能证明视频层可见。

- 首页 Hero 图片任务按需创建，只预取当前张和下一张；异步切图同时核查条目索引和数据代次，避免旧批次覆盖新批次。

依据：[Tok](../../../apps/windows/LinPlayer.Desktop/Views/Tok.cs)、[导航](../../../apps/windows/LinPlayer.Desktop/Views/Nav.cs)、[媒体库页面](../../../apps/windows/LinPlayer.Desktop/Views/LibraryPage.cs)、[桌面经验](../../../docs/lessons/ui-desktop.md)。

## 播放跳转

- `PlayerPage._position` 只保存内核真实位置；进度上报、弹幕、停播和续播不能使用待跳转目标。
- `PlaybackSeek` 持有显示目标；相对输入从目标累加，绝对输入按已知时长钳制。提交串行，等待中的请求只发最新目标；旧回执和旧失败不解除新目标。
- 回执不代表落点：已提交、非缓冲、同请求代数且真实位置差小于 1.5 秒才解除。失败或单调时钟 15 秒超时恢复真实显示；超时检查独立于状态查询成功与否。
- 单页状态查询不重叠，跳转等待不跳过其它状态同步。离页立即禁止输入并失效队列，停播等待在途 seek 和起播收尾，下一页起播等待旧页停播。
- UI 超时不能取消已发原生命令；核心永久不返回时继续等待，不能提前放行导致旧 seek 作用于新媒体。`tools/seekcheck` 验证状态和真实控件接线，不能替代实际视频或 Windows 窗口验收。
