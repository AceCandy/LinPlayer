# lin_player_player

`lin_player_player` 是 LinPlayer 的 **播放器模块**：播放内核封装、弹幕解析/渲染、播放控制 UI、缩略图/轨道偏好等播放器通用能力。

## 适用范围（放什么）
- `PlayerService`：对 mpv/media_kit 的封装与策略（硬解/缓冲/杜比视界等）。
- Danmaku：解析、处理、渲染与在线弹幕加载。
- 播放控制与播放器侧的通用工具（轨道偏好、速度/缓冲信息等）。

## 不放什么（边界）
- 不放“页面编排”（具体 Page/Route 留在主工程 `lib/`）。
- 不放服务端 API 实现（属于 `lin_player_server_api`）。

