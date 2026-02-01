# lin_player_ui

`lin_player_ui` 是 LinPlayer 的 **UI 基建模块**：主题、样式扩展、玻璃效果组件、缓存图片等可复用 UI 能力。

## 适用范围（放什么）
- Theme/Style：如 `AppTheme`、`AppStyle`。
- 可复用 Widget：玻璃/模糊/卡片等基础组件。
- 与 UI 强相关的通用服务：例如封面缓存、网站元信息解析等。

## 不放什么（边界）
- 不放全局业务状态（`AppState`）与持久化。
- 不放服务端差异分支（应由 adapter 层收口）。

