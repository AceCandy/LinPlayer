# 实现
- 聚合页/排行榜：新增 `pulling` 状态，下拉指示只在手动下拉时显示；所有路径复位。
- `LoadingState` 统一 90/130dp，读屏标签仍为「刷新中」；列表项内用 `LoadingPane(heightFraction)` 按屏高比例自适应（懒加载项内不能用 fillMaxSize，网格项也没有 fillParentMax*）。
- 片库、排行榜、演员页、数据源页去掉翻页小圈；PhoneLiuyingFavorites 与 TV 收藏末尾自动续页，失败才显示重试。
- 手机收藏 `FAV_LIMIT=150`，删去分页状态与分页底栏；TV 收藏仍每页 60 条自动续页。
- 数据源分类 `more()` 对空页循环取下一页，上限 20 次。
- 验证：`git diff --check`；独立复核一轮。未编译、未跑单测、未真机验证。
