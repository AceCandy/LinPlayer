# 实施记录
- 播放器：平板截图核对无问题，未改代码，新增截图基线。
- `PluginPages.SettingRow` select 改 `LpCell(checked)` + `semantics.selected`。
- `DetailPage`：评分/标签/播放抽为局部组件共用；`wide` 时 Row 左栏 `WideHead` + 共用块，右栏原 `LazyColumn`；新增 `WideBackdrop`。
- 验证：全量 1011 测试 0 失败（`LiuyingMediaSlotsTest` 一次偶发失败，单独重跑 3 次与全量重跑均通过）。
