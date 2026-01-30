# lin_player_server_api

`lin_player_server_api` 是 LinPlayer 的**服务端/网络 API 模块**：封装 Emby/Jellyfin、WebDAV、Plex 等网络交互与相关模型，提供可复用、可测试的 API 层。

## 适用范围（放什么）
- 各类“远端服务”的 API 封装与解析（HTTP/WebDAV 等）。
- API 层相关的数据模型（例如 `DomainInfo`、`LibraryInfo`、`MediaItem` 等）。
- 与播放链路相关的网络辅助能力（例如 WebDAV 本地回环转发）。

## 不放什么（边界）
- 不放 UI（Widget/页面）。
- 不放业务状态管理（`AppState`、`SharedPreferences` 等）。
- 不放“按产品/按平台差异”分支逻辑（这应由 adapter/feature flags 收口）。

## 模块内容
- `services/emby_api.dart`
  - Emby/Jellyfin 常用接口封装：认证、媒体库/列表、详情、播放信息、播放上报等。
  - 维护统一的 UA/Authorization Header 逻辑（App 启动时可通过 `EmbyApi.setUserAgentProduct` 等设置）。
- `services/webdav_api.dart`
  - WebDAV 的 `PROPFIND`、目录解析、鉴权（Basic/Digest）与常用请求封装。
- `services/webdav_proxy.dart`
  - `WebDavProxyServer`：仅监听 `127.0.0.1` 的本地回环转发，用于更好地兼容 Range/鉴权并对播放器提供稳定 URL。
- `services/plex_api.dart`
  - Plex PIN 登录与资源列表获取（选择服务器并保存登录信息）。
- `services/server_share_text_parser.dart`
  - 解析“服务器分享文本”（多行 URL/端口/密码）并生成结构化数据供 UI 选择导入。

## 使用方式（示例）
```dart
import 'package:lin_player_server_api/services/emby_api.dart';

final api = EmbyApi(
  hostOrUrl: 'https://emby.example.com',
  preferredScheme: 'https',
);
```

## 与其它模块的关系
- 依赖：`lin_player_core`（例如 `MediaServerType`）。
- 上层：`lin_player_server_adapters` 会基于本模块实现统一的 `MediaServerAdapter` 接口，避免 UI 直接依赖具体服务端实现。

