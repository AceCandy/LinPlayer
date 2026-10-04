# 设计与边界
主要修改 ListPages.kt 的 DownloadsPage 与 HistoryPage，保留文件内已有收藏改动。现有下载误读 state/bytes/speed；核心原样返回 status/received_bytes/progress/error，CoreClient 直接转交 data，不改名。核心不发 download.progress，参照TV两秒拉取；用 repeatOnLifecycle STARTED 和页面作用域控制轮询，成功才替换任务。速度用相邻成功采样字节差/单调时间差，首次及非下载态不显示速度。动作成功后刷新；独立错误状态保留旧列表。
历史使用 block 防止失败转空，范围变化重新加载；另读 account.listAccounts 仅映射友好名称，不展示原始URL。历史行局部布局复用 Panel、文本与媒体色进度；仍通过原路径切服再进详情。插件续播路径保持不变。
不增加下载播放路径、不重构数据模型或共享组件。未获得真实网络数据的指标不能伪造。
