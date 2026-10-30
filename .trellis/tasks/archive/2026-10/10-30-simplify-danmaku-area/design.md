# 技术设计

## 变更边界

用户看不懂的是播放页把排版内部参数暴露出来，而不是核心能力缺失。实际行为应收敛为一个区域枚举；核心 `DanmakuArea` 已允许 `0.25..1.0`，因此只需扩展各端枚举展示和 UI 入口，不改 JSON 命令、配置键或排版公式。

预期改动文件：

- `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/DanmakuStyle.kt`：区域循环和标签改为四档。
- `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/DanmakuPanel.kt`：删除高级显示页入口和所有高级控件，只保留基础显示区域入口。
- `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/PlayerPanel.kt`：删除高级页专用返回分支，保留现有样式页返回。
- `apps/android/app/src/main/kotlin/xyz/linplayer/app/tv/TvPlayerParts.kt`：TV 侧栏移除高级显示二级面板，只保留显示区域。
- `apps/windows/LinPlayer.Desktop/Views/PlayerPage.cs`：同步四档标签和循环提交。
- `apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneDanmakuPanelTest.kt`、`TvFocusTest.kt`、`LogicTest.kt`：更新 UI 和纯逻辑回归。
- `core/player/danmakulayout_test.go`：增加 3/4 屏滚动轨道上限回归；核心实现无需改动。

明确不做：不删除 `roll_lines/top_lines/bottom_lines/bold/merge/prevent_overlap/heatmap` 持久化字段，不迁移用户配置，不修改 Windows/Android 以外的播放器设置，不新增“密度”或其它替代选项。

## 数据流

用户点击显示区域 → `nextArea` 得到 `0.25/0.5/0.75/1.0` → 仍调用 `player.setDanmakuStyle(area)` → 核心原有 `Prefs.Clamped` 和 `relayout` 处理。核心排版只限制滚动弹幕轨道，顶部/底部弹幕字段继续从配置读取但不再由播放页编辑。
