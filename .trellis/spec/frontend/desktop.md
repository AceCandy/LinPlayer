# Windows / Linux 桌面端

## 组件与风格

当前为 C# / Avalonia，共用源码位于 apps/windows。页面继承 PageBase，使用既有 Nav、反馈、图片与加载入口。
色彩用 Theme/Tokens.axaml 与 `Tok.Of(key)`；不在 Application 初始化前缓存静态画刷。

普通主题语义色对齐当前手机；`Appearance` 将本机明暗模式原子保存至 userdata/appearance.txt，保存失败不改变当前外观。启动时应用模式；`ThemeVariant.Default` 跟随系统。Tokens 的共享画刷通过 DynamicResource 更新 Color，`tools/seekcheck --theme-only` 必须覆盖已创建控件明暗往返，而非只测试重新调用 Tok.Of。

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

- 首页Hero先显示/img-cache原图或已有缩略图预览，普通通道原图请求省略h/quality，按实际物理高度解码；只预取当前张和下一张。下载失败保留预览；异步切图核查条目索引、数据代次和请求代次，同项重入也拒绝旧响应。图片并发字典保存Lazy任务，仅入字典的实例启动HTTP，避免GetOrAdd并发工厂重复下载。tools/productcheck --images-only验证并发合流、图片URL、缓存探测、解码尺寸隔离与鉴权，不能代替真机画质/内存验收。
- 展示快照统一由 Go displaycache 持久化，页面通过 CallDisplayAsync 消费 partial；宿主 MetaCache 已移除。首页两路继续观看分别消费快照，失败保留对应资料并显示错误；全季通过 seasonEpisodes(all=true) 核心聚合，不再由页面另存完整列表。验证见 core/displaycache、tools/homecheck、tools/seekcheck --privacy-only。

依据：[Tok](../../../apps/windows/LinPlayer.Desktop/Views/Tok.cs)、[导航](../../../apps/windows/LinPlayer.Desktop/Views/Nav.cs)、[媒体库页面](../../../apps/windows/LinPlayer.Desktop/Views/LibraryPage.cs)、[桌面经验](../../../docs/lessons/ui-desktop.md)。

## 播放跳转

- `PlayerPage._position` 只保存内核真实位置；进度上报、弹幕、停播和续播不能使用待跳转目标。
- `PlaybackSeek` 持有显示目标；相对输入从目标累加，绝对输入按已知时长钳制。提交串行，等待中的请求只发最新目标；旧回执和旧失败不解除新目标。
- 回执不代表落点：已提交、非缓冲、同请求代数且真实位置差小于 1.5 秒才解除。失败或单调时钟 15 秒超时恢复真实显示；超时检查独立于状态查询成功与否。
- 单页状态查询不重叠，跳转等待不跳过其它状态同步。离页立即禁止输入并失效队列，停播等待在途 seek 和起播收尾，下一页起播等待旧页停播。
- UI 超时不能取消已发原生命令；核心永久不返回时继续等待，不能提前放行导致旧 seek 作用于新媒体。`tools/seekcheck` 验证状态和真实控件接线，不能替代实际视频或 Windows 窗口验收。

## 全端展示快照契约

遵守[共享展示快照规则](../backend/quality-guidelines.md#全端展示快照契约)：共享核心无TTL、1000条预算，展示显式接收partial，最终result实时；详情旧资料只读预览，主播放目标不取缓存。各端展示入口按身份/生命周期交付缓存并替换实时结果，分页按范围替换，失败保留展示但不伪装鉴权成功。

## MSG 榜单与公共日历

两页通过 `emby.ext*` 共用核心持久展示快照，`CallDisplayAsync` 不把鉴权/权限/不支持错误当成旧数据成功。榜单按来源分类并分页；公共日历按 JST，无客户端第三方采集。页面离开取消请求，分类/页码变化拒绝旧响应；点击由 `ExtensionNavigation` 实时精确匹配、核对当前会话后进入普通详情。回归：`tools/seekcheck --discovery-only` 跑真实页面与核心 HTTP，不代表真机或生产服务验证。

## 菜单集合

设置ItemsSource的菜单只能更新ItemsSource，不能调用Items.Add/Remove/Clear；扩展菜单项时保留原内容并重设源。服务器菜单构建回归用 `tools/seekcheck --server-menu-only` 覆盖当前/其他账号和重复刷新；只测页面请求无法覆盖主窗口菜单构建异常。

## 虚拟列表头部

Avalonia 11.3.11 中，不把反复随ItemsSource重设的同一个头部Control直接作为数据项：own-container回收后保留RecycleKey，再插入会跳过视觉挂接，注册滚动锚点时报非视觉后代。DownloadPage使用普通object标记，由模板返回头部，让框架管理容器；保留虚拟化，不吞布局异常。带临时核心路径的 `tools/seekcheck --scroll-anchor-download-only`覆盖真实下载刷新。本机浏览已下线，对应 `--scroll-anchor-only` 测试已移除；历史文件浏览复现不代表其它页面或Windows实机验证。

- 弹幕高级四开关放在默认折叠的更多设置中，沿用样式读写锁、成功回值与失败回显。热力图起播时读取偏好，刷新绑定弹幕请求取消和请求序号；换源清图，单纯清除匹配记忆保留当前图。seekcheck --danmaku-only覆盖四开关、重开、保存失败、热力图启停与清记忆。

- 弹幕透明度单独发送至合成器，仅更新画刷，不改字形代数或重新测量。首批截至当前位置加30秒，后台完整布局复用本轮字宽缓存；测量/解析均在后台，换源/尺寸/离页拒收迟到结果。空窗停帧，250ms位置同步提前唤醒，最终离场帧必须清画面。seekcheck --danmaku-only验证首批/整集、透明度、空窗停止与跳转唤醒。
