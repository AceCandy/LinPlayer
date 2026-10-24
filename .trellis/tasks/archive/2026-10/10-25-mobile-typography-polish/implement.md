# 执行与验证

## 完成记录（2026-10-25）

- 字阶在 `Theme.kt` 的 `lpTypography()` 与 `Typography.kt` 的 `LpText.rail` 收口；首页续播头高度测量同步改用 `rail`。
- `SectionTitle` 移除竖条及仅供竖条使用的 `accent` 参数，详情四处调用同步。
- `LpText.number` 与 `StepperRow` 读数去掉 Monospace，保留 `tnum`。
- 倍速首次改为最小宽度会扩大命中区，导致小屏大字号 OSD 重叠（PhonePlayerOsdTest 两项失败）；改回固定48dp并使用 `TextAutoSize.StepBased` 后通过。
- 新增 `fmtRemaining`（分钟精度、不足一分钟明示），`fmtDur` 十分钟以上只到分钟。
- TV 正式浏览经 `PhoneRoot(tv=true)` 复用手机页面，因此 TV 详情/首页片名同为28sp；UI_TV.md 注明 TvType 表仅约束播放器与 TV 专属组件。

## 未验证与风险

- 未做真机验证；厂商系统字体若不支持 `tnum`，时间码可能轻微跳宽，可将 `LpText.number` 改回 Monospace 一行回滚。
- TV 实机观感未单独核验，仅 SharedTvBrowseTest 通过。
