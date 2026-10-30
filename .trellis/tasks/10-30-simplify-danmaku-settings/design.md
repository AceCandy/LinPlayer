# 技术设计

## 变更边界

行为缺口在 Android 播放页的呈现层：现有手机 `style` 页和 TV 弹幕侧栏将基础显示、排版策略、进度条辅助功能连续堆叠，且位置/行数文案缺少对象说明。状态和核心契约已经完整，不需要改数据流。

预期改动文件：

- `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/DanmakuPanel.kt`：调整手机样式页顺序、分组和文案；增加高级显示页入口，保留现有 page 返回链。
- `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/PlayerPanel.kt`：让高级显示页与样式页共享侧栏宽度、返回和高度策略。
- `apps/android/app/src/main/kotlin/xyz/linplayer/app/tv/TvPlayerParts.kt`：调整 TV 弹幕根面板分组、文案和高级显示二级面板。
- `apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneDanmakuPanelTest.kt`：更新可见文案与分组断言，保留提交/回滚/返回测试。
- `apps/android/app/src/test/kotlin/xyz/linplayer/app/tv/TvFocusTest.kt`：覆盖 TV 进入高级设置、遥控调值和逐级返回。

明确不做：不删除核心字段，不修改 Go 命令或配置，不改全局弹幕源设置，不新增颜色、图标或第三方依赖，不改变手机与 TV 的焦点/返回协议。

## 交互结构

手机：`quick` 保留当前内容操作；`style` 只显示四个常用滑块和一个“更多显示设置”入口；`advancedStyle` 显示行数、粗体、重复合并、防重叠、热力图及说明。样式页标题统一为“显示设置”，不再展示“全局生效”副标题，避免与当前视频匹配/校时产生歧义。

TV：根面板保留当前内容操作；“显示设置”分组展示区域、字号、透明度、速度；“更多显示设置”进入二级侧栏，展示行数和排版开关。TV 当前没有进度条热力图绘制入口，因此继续不展示该项。TV 返回从二级回根面板，再按原逻辑关闭。

高级文案将明确对象：`滚动弹幕行数`、`顶部固定弹幕行数`、`底部固定弹幕行数`；说明补充“设为 0 即隐藏这一类弹幕”。

## 兼容性

所有控件继续调用原有 `area/scale/opacity/speed/roll_lines/top_lines/bottom_lines/bold/merge/prevent_overlap/heatmap` 键，加载失败、未加载禁用和保存失败回滚逻辑保持不变。
