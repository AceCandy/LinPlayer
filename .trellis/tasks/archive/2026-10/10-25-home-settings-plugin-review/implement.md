# 实现
- `HomePage.kt` Hero 渐变浅色去 .92 停靠点；`Cards.kt`/`DetailPage.kt` 占位包 s3 底。
- `PrimaryProgressPanel.kt` 并发读取包 coroutineScope；`SettingsPage.kt` 存储/备份/关于/更新/Bangumi。
- `PluginPages.kt` select 共享选中值、GroupLabel + LpCell checked；`OptRow` 增加 selected 语义。
- `DataSourcePages.kt` 芯片行 `bleed(16dp)` + 内边距。
- 测试：`PhoneManagementUiTest` 二级页截图与失败断言；`PhonePluginUiTest` 单选回归；新增 `PhonePrivateLargeTest`。
