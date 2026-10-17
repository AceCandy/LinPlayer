# 当前差异与证据

基线：main 6c2b7222；手机近期业务提交 fac360d4、a2ec1664、fb54c532。

| 项目 | 当前证据 | 判断 |
|---|---|---|
| TV失效消费 | AppState.kt:208-214 发 all/library/resume；tv页面未发现 invalidate 订阅 | 共享通知未完成页面闭环 |
| TV首页/媒体库 | tv/HomePage.kt:164 与 LibraryPages.kt:269 菜单回调仅处理 blocked | fav/played 未触发对应刷新 |
| TV收藏 | tv/ListPages.kt:154 处理 unfav/blocked 移除 | 当前页局部覆盖，不能声称完全未刷新 |
| TV搜索/详情 | tv/SearchPage.kt:305、324 和 DetailPages.kt:585 使用默认菜单回调 | 需要追踪当前页及返回页状态刷新 |
| 桌面卡片 | Views/CardActions.cs:56 默认 Nav.Reload，隐藏续播另调用 ForgetResume | 已有刷新，保留并核查缓存 |
| 桌面详情 | Views/DetailPage.cs:1986 收藏成功只更新按钮；2095 已看成功只Toast | 当前详情和返回列表缺少显式刷新 |
| 桌面返回 | Views/Nav.cs:132 Back恢复 Stack.Peek().Page | 旧页面实例需要失效/恢复检查 |
| 桌面榜单 | Views/RankingPage.cs:150 Fetch先清旧项，异常再清空 | 失败保留与重试行为需补齐 |
| TV榜单 | tv/DiscoverPage.kt:149 已有“第N页·刷新”；请求先设Loading | 不是没有重试入口，需保留内容和正确页码 |
| 日历 | 桌面 CalendarPage.cs:240、248 按本地时间；TV DiscoverPage.kt:158 fallback用systemDefault | 与JST分组基准不一致 |
| 日历时间 | 桌面只读air_date/broadcast_at；TV DiscoverPage.kt:182 已读broadcast_time和timezone | 桌面需核查并对齐实际核心契约 |
| 隐私空间 | frontend/state-management.md：手机启用请求分类，缺省宿主无筛选；TV无进入调用、桌面无对应实现 | 单列第二批，不能只接UI |

路径前缀：桌面 apps/windows/LinPlayer.Desktop；TV apps/android/app/src/main/kotlin/xyz/linplayer/app；AppState位于同Android前缀data/。

## 核验方法改进
判断同步必须逐层确认：共享实现 → 调用入口 → 页面消费 → 返回/缓存 → 回归证据；account.status会话刷新不能替代用户状态失效核查。子任务结果已按关键源码抽查，并纠正“TV榜单无重试入口”的不准确结论。

## 实施前核查结论
- Go核心emby/commands.go的三种状态写命令没有对应的成功失效事件；Android已有本地通知，桌面需要宿主补充。没有修改核心和生成绑定。
- TV的TvShell仅组合栈顶页，PageCache保留页面数据，非重放invalidate会漏离页变更；桌面Nav.Back恢复原实例。采用宿主代次及返回挂载补验。
- AppState.boot清PageCache不清当前组合中的remember；同服切用户的回归已红→绿，TvShell按server/userId重建当前页。
- 桌面CallDisplayAsync及Android displayBlock可在实时失败时返回缓存成功；榜单与状态热刷新显式关闭此行为，由页面保留旧内容并报真实错误。
- MSG基线d4e0b745的internal/handler/emby_ext.go及测试提供broadcast_time/timezone/weekday；LinPlayer core/emby/extension.go及测试已透传，采用现有字段。
- 实际自动验证结果与命令见implement.md；原始差异表是修改前证据，行号不能作为修改后定位依据。
