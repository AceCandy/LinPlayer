# 验证记录

- 用户要求直接提交归档；本轮**未编译、未运行任何测试**，仅做 diff 自审、`git diff --check` 与新增行隐私/AI 名称扫描（通过）。
- 自审要点：`isTvRootPage` 统一顶栏显示、`TvPageInset` 边距与一级页返回处理；`visibleEntries` 全为一级页时才画顶栏；`LpTopBar` 在 TV 隐藏返回；`LpDialog` 改用整窗 `Dialog`（已在依赖 class 中确认 `BasicAlertDialog` 使用 `DialogMaxWidth` 限宽）；详情 `awaitingPlay` 只拦方向键进入、`Enter` 方向的程序聚焦放行，失败或无可播分集即放开。
- `SharedTvBrowseTest` 已按新首屏布局调整，但未运行，焦点路径断言可能需按实测修正。
- 未验证：编译、单测、真机观感（头图比例、遮罩对比、图标行拥挤度、去掉顶栏遮罩后亮图上文字可读性、氛围色首帧对齐与切页边距是否被裁）、Emby 默认刷新是否真正补探测（`.strm`）及非管理员静默无效。
- 本轮未启动调试服务、Gradle 守护进程或临时文件。
