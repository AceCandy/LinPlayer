# tvOS 对齐 Android TV 低优先级差异

基线：Android TV 正式入口 `TvRoot → PhoneRoot(tv=true) → ui/pages` 与 `tv/` 播放器。口径（用户 2026-11-02 确认）：只与 Android TV 对齐——tvOS 缺的补齐，tvOS 多出的删除，不新增 Android TV 没有的功能。上一轮 15 项见 `archive/2026-11/11-02-tvos-atv-gap-closure`。

## 清单

### 按 Android 定案的三项
- [x] 媒体库页搜索入口：Android TV 无，删除。
- [x] 详情页音轨/字幕选择按 Android 写入偏好。
- [x] 首页 Hero 轮播到末尾按 Android 重新洗牌。

### tvOS 多出项（删除）
- [x] 分集详情的收藏/评分/「从继续观看中隐藏」按 Android 分集详情收敛；操作结果提示按 Android。

### tvOS 缺少项（补齐）
- [x] 存储页：数据目录、日志目录、导出日志（tvOS 平台不支持的部分记录原因）。
  - 已做：数据目录、日志目录、发送日志给开发者（`system.sendReport`，附核心诊断与核心日志目录文件）。未做：导出日志文件（tvOS 无文件选择器）。
- [x] 弹幕：屏蔽用户导入、官方源状态、搜索框预填片名。
  - 已做：官方源可用/不可用及原因、屏蔽用户数、搜索预填标题「 · 」前部分。未做：屏蔽表导入（tvOS 无文件选择器）。
- [x] 私密空间入口可见提示。
  - 复核：Android TV 同样无可见提示（长按/菜单键进入），故不新增提示；只把失败文案改为 Android「当前服务器未提供成人库分类，无法进入隐私空间」。
- [x] 播放器：画面比例、倍速档位、媒体信息参数、下一集预取。
  - 画面比例改为原始/自适应/4:3/16:9（去掉 Android 没有的拉伸）；倍速 0.25–4.0 共 16 档按引擎上限过滤（视频 2×）；媒体信息按 Android 字段表。
  - 下一集：只做核心预解析 `player.prepareNext` + `prepared_id`；Android 的内核预热与无缝排队依赖 Exo/MPV，AetherEngine 无公开接口，未做。
- [x] 卡片菜单：收藏适用类型、聚合搜索结果卡片菜单。
- [x] 续播时长角标只在续播行显示。
- [x] 搜索：输入防抖自动搜索、换类型重搜、历史记录时机与上限。
- [x] 首页无媒体库空态。
- [x] 流影：标签计数、能力缺失/读取错误空态、跨条目音量保持。
  - 音量保持复核为已一致（会话内静音状态沿用）。

## 状态
勾选仅表示代码落地并通过本机静态检查；Swift 类型检查、截图与真机均未执行。成功操作提示新增 `AppModel.toast`（自动消失，对应 Android toast），错误仍用 notice 弹窗。

## 验收口径
- 每项对照 Android 源码 file:line；不新增核心命令语义。引擎能力不足时如实显示上限，不伪造选项。
- 本机无 Swift 工具链：Mac 上运行 `bash scripts/check-tvos-ui.sh`、`bash scripts/check-tvos.sh`、`bash scripts/check-tvos-visual.sh`。
- 勾选仅表示代码落地；遥控、播放与真机行为另行验收。
