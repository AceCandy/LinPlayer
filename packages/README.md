# Packages（模块索引）

本目录用于承载“可复用、可测试、边界清晰”的模块；主工程 `lib/` 以页面编排为主。

## 业务底座
- `lin_player_core`：纯 Dart 核心定义（AppConfig / FeatureFlags / MediaServerType）。
- `lin_player_prefs`：偏好设置定义（UI 模板、播放器设置枚举、序列化 id 等）。

## 网络与适配
- `lin_player_server_api`：服务端/网络 API（Emby/Jellyfin、WebDAV、Plex）。
- `lin_player_server_adapters`：Server Adapter 适配层（UI 只依赖接口）。

## UI / Player / State
- `lin_player_ui`：UI 基建（主题/样式/玻璃效果/图标库/网站元信息等）。
- `lin_player_player`：播放器模块（PlayerService、弹幕、播放控制、缩略图/轨道偏好等）。
- `lin_player_state`：全局状态与持久化（AppState、ServerProfile、备份导入导出等）。

## Patched Dependencies
- `media_kit_patched`：对 `media_kit` 的本地改造版本（用于更细粒度传递 mpv 参数）。
- `video_player_android_patched`：对 `video_player_android` 的本地改造版本（Exo 字幕轨道等）。

