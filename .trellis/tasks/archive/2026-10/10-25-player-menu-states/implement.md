# 实现
- `PlayerMenu.kt`：默认圆角 12dp、背景 0xEB1D2124；`PlayerMenuRow` 新增 `divider`，边距调整；`PlayerMenuNote` 左右 16dp。
- `PlayerPanel.kt`：去掉按类型覆盖的圆角/底色；选项列表 itemsIndexed，末行无分割线；选集 0 分钟隐藏。
- `DanmakuPanel.kt`：各页末行 `divider = false`。
- `PlayerPage.kt`：失败页按钮改为 Row 并排、最小宽 112dp，说明居中。
- `DataSourcePages.kt`：空态改 EmptyState，SourceError 居中图标版式。
- `PhoneSourceUiTest`：新增空首页、分类错误截图；spec 播放菜单节补约定。
