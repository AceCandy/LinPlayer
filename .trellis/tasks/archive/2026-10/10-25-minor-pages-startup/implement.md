# 实现
- 冷启动：`plugin.activeTheme` 常态只读内存偏好返回空，主线程仅一次本地往返；1.5s 为核心卡死兜底，且主题须在首帧前加载（有意设计），不改。真正杠杆是 baseline profile，需真机。
- `LinesPage` 改 `manageListWidth()`；`BrowsePage` 列表改 `manageListWidth()`，大小用 `fmtSize`。
- 新增 `PhoneMinorPagesTest` 8 例；空态替身 `source.currentSource` 回 null 与真实核心一致。
- 验证：全量 1032 例，3 跳过；`SharedTvBrowseTest.castOpensPersonWorksAndReturnsToSameActor` 偶发一次布局时序错误，单独连跑 3 次通过。
