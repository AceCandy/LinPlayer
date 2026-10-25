# 实施
- `ListPages.kt`：下载卡片重排，去掉清记录说明；进度条 gapSize=0、去 stop 点
- `PluginPages.kt`：已安装动作改为带图标 LpCell 分组；列表 Panel 化；市场 itemsIndexed 分段背景；仓库独立删除键
- `PhonePluginUiTest`：四标签深浅截图 + 仓库删除语义
- 验证：全量单测仅既有 3 个私密空间用例失败
