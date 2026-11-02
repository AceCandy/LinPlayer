# tvOS 补齐 Android TV 剩余功能与 UI 缺口

基线：Android TV 正式入口 `TvRoot → PhoneRoot(tv=true) → ui/pages` 与 `tv/` 播放器；设计正本 `docs/go-migration/UI_TVOS.md`。2026-11-02 静态源码对照得出，15 项已在 tvOS 源码逐条复核。

已排除的误报：`player.setPlaybackPrefs` 顶层或 `settings` 嵌套参数均被 `settingsOf` 接受；`prefs.setUpdateSettings` 只改传入字段，tvOS 仅传 channel 不会重置其它两项。

## 执行顺序

### A 小改动 / 低风险
- [x] A1 暂停时控制栏不自动收起；暂停/继续给出中央反馈图标（`PlayerView.swift` 自动隐藏任务）。
- [x] A2 播放失败整页提示 +「重试」主按钮（复用 `switchPlayer` 重试路径）。
- [x] A3 详情头部显示 tagline、分级、状态、时长、季数（字段已在 `DetailPresentation` 解码）。
- [x] A4 首页 Hero 补普通/豆瓣评分、最多 2 个类型、2 行简介（对照 `HomePage.kt` Hero）。
- [x] A5 关于页补「启动时自动检查更新」与 GitHub 代理，启动时按开关检查。

### B 中等改动
- [x] B1 播放页拆分「自动跳过片头」「自动跳过片尾」（`skip_intro`/`skip_outro`），`skip_auto` 只在设置页。
- [x] B2 首页续播卡菜单「取消观看记录」，按 Android 同源能力显示。
- [x] B3 聚合页解码 `active`/`aggregate_primary`，显示主服/当前标记；当前服条目直接打开不切服。
- [x] B4 快进步长 5/10/15/30 秒与分档加速、松手自动提交；长按倍速 2/3/4（宿主本地偏好，同 Android `UiPrefs`）。长按仅在系统事件可可靠区分时实现。
  - 已做：步长设置、分档加速、松手 0.7 秒自动提交；长按确认键 600ms 临时倍速（2/3/4×，默认 3×，不写偏好，松开恢复，短按改为松手时切换暂停）。
  - 引擎限制：视频 `maxSupportedRate` 为 2×（AetherEngine.swift:7291），3×/4× 在视频上按 2× 生效，界面显示实际值。
  - 长短按区分依赖 Button `isPressed`，需 Siri Remote 真机确认。
- [x] B5 分集详情页显示本季分集横轨并定位当前集。
- [x] B6 排行榜自动续页与按来源分组目录。
- [x] B7 同一剧换集沿用手选音轨/字幕（语义同 `SessionTrackMemory.kt`）；切版本/服/账号失效。
- [x] B8 登录页常驻手机配对二维码及「扫不了」提示。

### C 大改动
- [x] C1 详情页换源（`source.switchCandidates`），私密空间不跨服。
- [x] C2 服务器编辑：改账号密码、图标选择、线路重命名。
  - 未做：本地图片作图标（tvOS 无文件选择器）；网络图标源可增删。

## 状态
勾选仅表示代码已落地并通过本机静态检查（speed-controls、gen-bindings --check、loading-check 模拟）；Swift 类型检查、视觉截图与真机行为均未执行。

## 验收口径
- 每项改动对照 Android 源码 file:line，不新增核心命令语义；业务仍走既有命令。
- 本机无 Swift 工具链：Mac 上运行 `bash scripts/check-tvos-ui.sh`、`bash scripts/check-tvos.sh`，布局另跑 `check-tvos-visual.sh` 人工看图。
- 遥控长按、换源跨服、真实播放与轨道沿用需真机验收；未执行的验证不得勾选为通过。
