# 实施边界

- 复用 DetailPage 的 TV 分支和组件，不新建页面或请求链。
- 头部简介承接 DETAIL_OVERVIEW 插件锚点，移除 TV 底部重复项；真实两行溢出才提供全文入口，手机 Overview 不变。
- TvDetailAction 复用 iconOnly；PickList 仅改 TV 可见文本，保留语义、onClick 和版本选择弹窗中的元数据。
- 选季复用 seasonPicker，TV 只显示季名；不改变选季请求/状态。
- 生产预期仅 DetailPage.kt，测试覆盖 SharedTvBrowseTest / VisualAlignmentTest，按需要更新关联断言。最小回滚为本任务 diff，无数据迁移。
