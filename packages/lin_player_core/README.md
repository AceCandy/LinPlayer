# lin_player_core

`lin_player_core` 是 LinPlayer 的**纯 Dart 核心模块**：放置跨模块共享、稳定、与 UI/平台无关的“领域定义”与“运行时配置”。

## 适用范围（放什么）
- `AppConfig`：应用运行时配置（从 `--dart-define` 读取），例如显示名、GitHub 仓库信息、feature flags 等。
- `AppProduct`：产品标识（由 `APP_PRODUCT` 决定）。
- `AppFeatureFlags`：特性开关集合（用于“UI 入口控制/默认值”之类的差异收口点）。
- `MediaServerType`：媒体服务端类型枚举及常用扩展（如 `isEmbyLike`）。

## 不放什么（边界）
- 不放 Flutter Widget（例如 `InheritedWidget`/页面/UI 组件）。
- 不放网络请求、IO、数据库/存储。
- 不放具体服务端实现（Emby/Plex/WebDAV 等属于 `lin_player_server_api`）。

## 目录结构
- `lib/app_config/*`：应用配置与 feature flags。
- `lib/state/*`：跨模块共享的基础枚举/小型状态定义（当前为 `MediaServerType`）。

## 使用方式（在主工程里）
```dart
import 'package:lin_player_core/app_config/app_config.dart';

final config = AppConfig.current; // 从环境变量读取
print(config.displayName);
```

> UI 侧的 `AppConfigScope`（把 `AppConfig` 放进 Widget Tree）目前仍在主工程 `lib/` 中：这是刻意的分层（core 不依赖 Flutter）。

## 约定
- 本包应保持**低依赖、低变更频率**；更适合作为其它模块的“公共底座”。
- 若新增类型会被多个模块用到，优先放到这里；否则请放到更具体的模块中。

