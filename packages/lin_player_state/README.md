# lin_player_state

`lin_player_state` 是 LinPlayer 的 **状态模块**：全局 Store（`AppState`）、服务器配置（`ServerProfile`）、备份导入导出与 SharedPreferences 持久化。

## 适用范围（放什么）
- `AppState`：全局状态、缓存、持久化、登录/切服等流程。
- 状态相关模型：`ServerProfile`、路由线路条目构建等。
- 备份/加密等与状态生命周期相关的能力。

## 不放什么（边界）
- 不放 UI 组件（Widget/页面）。
- 不放服务端差异实现（API 与 adapter 已在对应包中）。

