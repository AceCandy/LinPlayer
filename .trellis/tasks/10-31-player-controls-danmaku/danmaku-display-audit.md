# 弹幕显示与设置核查（2026-10-31）

## 范围和需求理解纠正

本轮用户询问弹幕展示是否有问题、与 Yamby / Hills 的区别，以及为何只剩屏幕占比。仅核查，未修改产品行为、提交或出包；保留此前控件和自动加载修改。

此前对话先要求参考四项常用设置（区域、不透明度、字号、速度），随后讨论用区域替代滚动/顶部/底部行数。将后者扩大理解为删除其余全部外观入口，精简过度。建议恢复上述四项，行数继续隐藏；这是纠正建议，本轮没有重新更改设置契约。

历史提交 `6a05c07d` 曾将四项常用参数与高级项分组，随后 `3126c038` 删除了字号、透明度、速度和整个高级页。对应归档任务 `10-30-simplify-danmaku-settings` 与 `10-30-simplify-danmaku-area`。手机 `DanmakuPanel.kt`、TV `TvPlayerParts.kt`、桌面 `PlayerPage.DanmakuStyleBlock` 当前都仅展示区域四档。

## 参考证据

样本为仓库 `docs/cankao/Yamby-v2.1.0.11-arm64-v8a-release.apk` 和 `Hills-v1.10.0-arm64-v8a-release.apk`，不代表最新版本。

| 项目 | Yamby | Hills | LinPlayer |
|---|---|---|---|
| 字号、透明度、速度 | DEX 存在对应设置键及 SetDanmakuFontSize / Transparency / ScrollSpeed 事件 | ARM64 libapp.so 有 Danmaku Scale / Transparency / Scroll Speed 文案 | 核心保留，播放页入口被删除 |
| 滚动/顶部/底部行数 | DEX 有 ScrollMaxLines / TopFixedMaxLines / BottomMaxLines 事件 | 静态文案包含三类 Max Lines | 仍影响排版，播放页无法修改 |
| 防重叠、重复合并 | DEX 有 prevent_danmaku_overlapping、merge_duplicate_danmaku；旧运行记录观察到重复合并项 | 静态文案包含 Prevent Danmaku Overlap、Merge Duplicate Danmaku | 核心保留，播放页入口被删除 |
| 实际弹幕画面、默认值及密度算法 | 本轮未验证 | 本轮未验证 | 已查实际源码，并做 Android 原生绘制测试 |

本轮直接从原 APK 的 DEX 字符串表与 libapp.so 提取上述名称，未修改或重新签名 APK。字符串和事件存在不证明控件当前可见、具体默认值或运行算法，不能声称参考播放器已完成同素材视觉对比。

Yamby 尝试在普通用户、仅 KVM、无外网、只读挂载 SDK / APK 的临时容器运行 API30 模拟器。KVM_GET_API_VERSION=12、KVM_CREATE_VM 成功，日志确认加速可用，但 Android 启动前 QEMU main loop exits abnormally with code 1；调整 ADB 启动及进程托管后仍失败，未安装进入目标页面。临时容器与 AVD 已清理，无新增参考截图。旧实测只证明源管理和两个合并项，不能代替本次目标控件核查。

Hills 已有 API30 / Android14 转译层启动 SIGABRT 记录，本轮不重复失败路径。详见归档任务 `10-13-msg-extension-client/research/{yamby-danmaku-runtime,hills-danmaku}.md`。

## 本端已确认的问题

1. **文字实际宽度与布局估宽不一致。** `core/player/danmakustyle.go:textWidth` 按中文26、其它字符13估宽，而 `layoutReply` 默认字号40。Android `DanmakuLayer.drawDanmaku` 将估宽用于固定弹幕居中、滚动路程和可见性，未使用绘制画笔的实际文字宽度。默认样式、640×360 的原生绘制测试中，十字中文“这是一条测试用的弹幕”估宽86.67px、Paint测宽130px；固定弹幕可见范围278..406，中心342，屏幕中心320，偏右22px。滚动age=7.99秒仍有0..43列可见，age=8.01秒直接消失。该环境已复现偏心与尾部骤失；不将该像素数推广到所有设备字体。
2. **隐藏设置仍可能改变显示。** 核心及 `DanmakuStyle.applyStyle` 仍保存、读取 scale / opacity / speed / roll_lines / top_lines / bottom_lines 等。旧滚动行数0会过滤全部滚动弹幕，已有 `core/player/danmaku_session_test.go` 覆盖。更改区域只写area，不恢复这些旧值。未读取实际用户配置，不能断言用户正遇到该值。
3. **区域目前仅限制滚动弹幕。** `layout` 的rollLanes使用Area，顶部/底部仅受自身行数与全屏maxLanes限制，底部仍从屏幕底沿排布。“1/4屏”并不保证所有类型都留在这片区域；若产品约定为全部弹幕占屏范围，现实现不完整。
4. **防重叠会牺牲较多密度。** 每行在一条弹幕完整离场前都不可复用，不判断前后弹幕已安全拉开距离。默认滚动8秒，1/4屏约5行，5条尚未离场时新滚动弹幕会被跳过。源码注释承认该取舍；未取得参考端同素材测量，不将此写为已证实比参考更稀疏或更卡顿。

## 验证与后续边界

- 执行 Android 定向回归：DanmakuLayerTest 3项、PhoneDanmakuPanelTest 4项全部通过；证明既有时钟、暂停/缓冲和当前菜单行为，不证明上述缺陷不存在。
- 临时 DanmakuAuditProbeTest 2项使用生产 DanmakuLayer、默认布局参数及原生 Paint / 截图像素，成功复现偏心和尾部骤失。核查后移除临时测试源文件，未修改生产绘制逻辑。
- 菜单截图由本轮测试重新生成于已忽略的 apps/android/app/build/player-osd，属于组件测试图，不是真机播放或参考客户端截图。
- 建议先恢复四项常用设置并修正实际字宽；统一区域语义与处理隐藏旧值需明确兼容方案，不能直接抹掉用户全部偏好。密度改进需保留窄屏、长句及混合类型防重叠保证，不以盲目增加行数处理。
- 未做真实设备、真实片源和三端同素材对照，未验证帧耗时；本轮未声称实际用户问题已修复。

## 后续授权修复

用户随后明确要求修复、审核后提交归档。已恢复三端四项常用设置，按实际字体测宽绘制，统一三模式区域及底部位置；用户调整区域显式解除旧行数限制，其余偏好不迁移。桌面同时修复暂停改样式不重绘，审核发现的慢响应步进、拖动回执和菜单重开竞态已补回归修正。实际验证及剩余限制见同目录validation.md；上文只读结论保留为修复前证据，不代表修复后状态。
