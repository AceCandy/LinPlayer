# 手机端次级页面调研

范围：Android `DiscoverPages.kt`（排行榜/追剧日历）、`DataSourcePages.kt`（源首页/分类/详情/收藏）、`ThemePicker.kt`、`PluginPages.kt`（已装插件详情）。静态核对工作树；未运行构建、测试或真机/TalkBack 检查。以下体验判断是待验证的优化方向，不代表已确认缺陷。

## 可进入路线图的点（按用户影响排序，最多 6 条）

1. **日历取数失败没有页面内重试入口。** 事实：日历在授权后请求 `sync.bangumiCalendar`，错误会保留为 `Block.Fail`（`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/DiscoverPages.kt:462-487`）；渲染时传 `BlockBox(all, null)`（`DiscoverPages.kt:509-512`），而 `BlockBox` 仅在 `onRetry != null` 时给错误态提供“重试”（`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/components/Base.kt:762-776`）。日历某天确实有独立“这一天没有放送”空态（`DiscoverPages.kt:593-598`），不要将空结果误报为缺少空态。推断：网络失败后用户可能只能离开再进入页面恢复，恢复成本高；可评估补充刷新/重试入口。优先级：高（核心列表整体不可用时）。

2. **主题列表请求失败会静默显示“官方主题”，可能把失败状态呈现成有效选择。** 事实：`plugin.themes` 和 `plugin.activeTheme` 均经 `runCatching { ... }.getOrNull()` 丢弃失败，列表回退空列表、当前主题回退空 ID；空 ID 被映射为“官方主题”（`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/ThemePicker.kt:41-51`）。成功设置主题才有“重启后生效”Toast，设置失败交 `app.report`（`ThemePicker.kt:65-70`）。推断：加载失败时打开弹窗会看起来只有“官方主题”，用户难以判断主题插件未安装还是读取失败。建议用加载/错误反馈区分失败与真实空列表。优先级：中高（配置状态可信度）。

3. **排行榜数据错误态有“重试”按钮，但当前回调没有触发请求。** 事实：榜单数据请求由 `LaunchedEffect(cur)` 发起（`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/DiscoverPages.kt:214-222`）；失败状态传给 `BlockBox(b, { cur = cur })`（`DiscoverPages.kt:278`），而 `BlockBox` 的重试按钮直接调用该回调（`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/components/Base.kt:762-776`）。推断：给 `cur` 赋回相同值不会使 keyed effect 重新执行，用户点“重试”无法重拉当前榜单；可改为独立请求重试触发器。优先级：高（错误恢复入口表面存在但不起作用）。

4. **媒体卡片/选中控件存在两套强调色语义，覆盖这些页面但不等于必须统一改色。** 事实：手机媒体卡片进度/续播标签使用蓝紫 `mediaAccent`（`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/components/Cards.kt:170-183`；token 注释为“手机媒体卡片与悬浮导航的蓝紫色，与播放页强调色分开”，`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/theme/Theme.kt:42-47`）；通用 `ToneChip(on=true)` 则用主题 `c.acc`（`Dissolve.kt:226-234`），深色 `acc` 为 `#F5A524`、浅色为 `#8A5A00`（`Theme.kt:60-80`）。因此排行榜分类（`DiscoverPages.kt:260-267`）、源分类筛选/详情线路（`DataSourcePages.kt:273-289,372-377`）、插件页标签（`PluginPages.kt:96-109`）当前选中态均走琥珀主题强调色；排行榜/日历大标题本身是 `c.fg` 中性色，不是琥珀（`DiscoverPages.kt:133-145`）。日历周条背景混用 `c.acc` 与固定琥珀 `#D98A12`（`DiscoverPages.kt:545-569`）。推断：同一手机壳内媒体身份色和交互选择色不同，可能让人感到不一致；也可能是刻意将媒体内容蓝紫与主题交互琥珀分工。路线图应先确认颜色语义并做跨页视觉对照，不据此直接提出全站换色。优先级：中（视觉一致性，产品决策依赖）。

5. **数据源详情把线路和单集都做成横向/四列 ToneChip，操作目标与当前播放上下文可核验性值得实机审视。** 事实：多线路横向滚动并标出线路名和集数，当前线路传 `on`；单集每行四个等宽 chip 且均 `on=false`，点击直接导航播放（`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/DataSourcePages.kt:372-385`）。唯一一集时显示独立“播放”按钮（`DataSourcePages.kt:378-380`）；源错误已有按限流倒计时、验证、重试、换源分类反馈（`DataSourcePages.kt:130-152`），收藏写入成功后切换状态，失败调用 `app.report`（`DataSourcePages.kt:359-367`）。推断/待测：多线路标题过长、窄屏四列触控与读屏表达需设备验证；现有错误/收藏能力不应作为缺失项。优先级：中（播放前确认与触控）。

6. **已装插件详情页操作多且清理操作后果差异大，页面依赖长列表中的分组文案来解释。** 事实：详情依次显示设置、插件自有页面、贡献点、占用、版本管理和错误详情；“清数据”副文案说明会清插件设置/登录状态，“清缓存”是独立项，卸载注明重启后卸载且保留插件数据（`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/PluginPages.kt:383-423`）。命令失败已有 `app.report`，成功动作部分会 toast/刷新（`PluginPages.kt:376-378`）。推断/待测：长详情页上风险不同的维护动作聚集在底部，用户可能误触或不易找到；可评估清数据/卸载前确认与高风险操作的视觉区分，同时保留已经存在的后果说明。优先级：中（误操作成本；需长屏核验）。

## 已有能力与覆盖边界

- 数据源首页已有源内 500ms 防抖搜索、继续观看、推荐、分类和搜索/首页错误重试；无首页能力时也明确引导使用搜索框（`DataSourcePages.kt:161-183,193-229`）。分类页记住筛选、滚动加载下一页，并有错误重试和空分类文案（`DataSourcePages.kt:238-299`）。
- 数据源收藏页已有加载/错误组件、空态和来源已移除标记；已移除条目点击会给“这个来源已经移除了”反馈，不应描述为缺少反馈（`DataSourcePages.kt:491-515`）。详情收藏、换源、线路、选集与续播定位均已实现（`DataSourcePages.kt:306-389`）。
- 排行榜分类列表加载错误有 `reload++` 重试，无榜单凭据说明、空榜单说明和逐行渲染均已存在（`DiscoverPages.kt:204-213,233-250,258-289`）；榜单数据错误的重试回调问题见上条。
- 与已覆盖报告的边界：本文件聚焦排行榜/日历、数据源页面、主题选择、已装插件详情；不重复首页、媒体库、搜索、管理页主体、插件主标签页、设置等覆盖。扩展页和插件自定义页面 surface 未深入检查。
- 未做运行时、窄屏、大字、对比度、TalkBack、键盘/焦点或网络故障验证；需把推断项当成验证方向，而非事实缺陷。
