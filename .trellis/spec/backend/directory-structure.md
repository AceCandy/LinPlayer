# 核心目录与边界

| 位置 | 职责与例子 |
|---|---|
| `core/emby/` | Emby 请求、解析与命令适配，例 emby.go |
| `core/player/` | libmpv 控制与播放生命周期，例 player.go |
| `core/bus/` | 命令注册表、分派、结果 / 事件队列，例 bus.go |
| `core/commands/` | RegisterAll 汇总模块注册，唯一统一注册入口 |
| `core/ffi/` | 唯一 //export 包，C↔Go 转换、字符串所有权与 panic 边界 |
| `core/config/`、`core/paths/` | 配置与唯一数据路径出口 |
| `core/cmd/` | 命令枚举、凭据封装、差分对账等工具 |
| `bindings/` | C# / Kotlin 生成绑定，不手改生成文件 |

FFI 不承载业务逻辑。宿主通过 `lp_init` 给数据根，核心不猜用户目录。
添加命令同时维护 `docs/go-migration/COMMANDS.md` 与 Go 注册表，再运行 `python3 scripts/gen-bindings.py`；绑定参数仍是弱类型 JSON，不能把生成方法签名当作完整 JSON schema。

代码依据：[FFI 入口](../../../core/ffi/main.go)、[模块注册](../../../core/commands/register.go)、[路径](../../../core/paths/paths.go)、[生成器](../../../scripts/gen-bindings.py)。
