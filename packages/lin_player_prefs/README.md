# lin_player_prefs

`lin_player_prefs` 是 LinPlayer 的**偏好设置定义模块**：用于沉淀跨模块共享的设置项（枚举、序列化 id、默认值、少量轻量 value object）。

## 适用范围（放什么）
- `UiTemplate` / `PlayerCore` / `PlaybackBufferPreset` 等“设置项枚举”与 `fromId()`。
- 纯 Dart 的小型数据结构（例如缓冲拆分计算）。
- 允许包含极少量与 UI 强绑定但“设置本身需要”的类型（例如 `Color` 作为主题 seed）。

## 不放什么（边界）
- 不放 Flutter Widget / `ThemeData`（UI 逻辑在 `lin_player_ui`）。
- 不放网络请求、IO、持久化实现（这些在 state / api 层）。

## 目的
- 让 `state / player / ui` 都能依赖同一套“设置项定义”，避免重复与循环依赖。
