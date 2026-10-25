# 实施记录
- `Base.kt` 新增 `manageWidth()` / `manageListWidth()`，设置页原私有 720dp 写法改为共用。
- `ServersPage`、`PluginPages` 管理列表与标签行套用；`PluginPage`（插件自绘）不限宽。
- `SearchPage` 结果网格改用 `posterColumns()`。
- 验证：全量 1008 测试 0 失败，截图逐张复核。
