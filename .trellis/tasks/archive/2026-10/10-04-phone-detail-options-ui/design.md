# 方案

DetailPage.kt 负责页面配色、季筛选和上下两行播放选项。复用 MediaFilterChip。
Dissolve.kt 的 PrimaryAction/IconAction 仅用于详情，直接统一媒体配色；SectionTitle 增加可选强调色并让标题可收缩，默认保持其它调用样式。
Base.kt 的 OptRow 增加媒体呈现参数，详情明确启用，播放器等其它调用保持默认。
不重构业务状态，不新增依赖。测试使用现有 FakeCore/Roborazzi，覆盖版本与语言保存契约。
