# Go 核心层规范

适用 `core/`、`bindings/` 与核心契约检查脚本。业务在 Go 功能包，宿主负责呈现和平台接入。

| 文档 | 内容 |
|---|---|
| [目录与边界](directory-structure.md) | 功能包、命令注册、FFI 与绑定 |
| [持久化](database-guidelines.md) | JSON 配置、原子写、损坏与数据接管 |
| [错误契约](error-handling.md) | bus.Err、结果信封与 panic 边界 |
| [日志与脱敏](logging-guidelines.md) | 日志事件、离机反馈与隐私 |
| [质量检查](quality-guidelines.md) | 核心门禁、ABI、取消与对账 |

## Pre-Development Checklist

- 先读 [通用规范](../shared/index.md) 和上表相关主题。
- 跨宿主改动读取 [架构正本](../../../docs/go-migration/SPEC.md) §5；命令变化读取 [命令表](../../../docs/go-migration/COMMANDS.md)。
- Emby 工作遵守 [产品与 API 基准](../shared/product-and-api.md)，查相关 lessons 与 handler 测试。

## Quality Check

- 功能逻辑未进入 FFI；错误码、JSON 字段与字符串命令名保持契约一致。
- 配置损坏不被空配置覆盖；异步任务遵守取消与初始化顺序。
- 运行 [核心质量检查](quality-guidelines.md)，说明真实联调与设备验证缺口。
