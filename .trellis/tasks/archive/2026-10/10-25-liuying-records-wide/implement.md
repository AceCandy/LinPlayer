# 实现
- `PhoneLiuyingFavorites.kt` 分类 Tab 改为下划线样式；`FeedSecondary` 改 internal。
- `LibraryPage.kt` 整页刷新 `distinctBy { it.id }`。
- `DetailPage.kt` 新增 `detailHeroMin`，两处头图使用。
- `SettingsPage.kt` 两个 LazyColumn `wrapContentWidth().widthIn(max = 720.dp)`。
- 新增 `PhoneWideLayoutTest`（横屏/平板 × 首页/媒体库/详情/设置）；spec 补宽屏约定。
