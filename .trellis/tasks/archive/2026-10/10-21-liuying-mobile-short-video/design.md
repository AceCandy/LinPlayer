# 设计

视觉差距位于 NativeLiuyingContent 的共用 chrome，不在播放器内核。保留原 TV chrome，手机使用独立呈现组件；复用 FeedPlayback、LpIcons 和已有主题字体，不新增依赖。

NativeLiuyingContent 持有面板开关，位于播放 revision key 外；筛选面板在根层挂载，避免换片或筛选时卸载，防止事件穿透。现有查询与状态回调直接接线，不另建业务状态副本。保留上一条/下一条的可访问入口，尤其空页回退。

预计修改 NativeLiuyingContent、新手机呈现文件、PhoneLiuyingTest、手机设计正本及 Android 规范。普通 PlayerPage、TV 页面、核心和绑定不改。

风险集中于覆盖层输入命中、播放器重建和短屏可达性，通过真实组件测试及截图核验。回滚仅需恢复手机呈现分支与相关测试、文档，无数据迁移。
