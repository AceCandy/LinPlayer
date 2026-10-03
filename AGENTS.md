# AGENTS.md · 开工入口

本仓库当前开发规范统一维护在 [.trellis/spec/](.trellis/spec/index.md)。本文只保留项目概览、加载顺序和文档入口。

## 项目范围

LinPlayer 是第三方 Emby 媒体客户端。当前为 Go 核心 + C# / Avalonia 桌面外壳 + Kotlin / Compose Android 外壳；覆盖 Windows、Linux、Android 手机和 TV。
本机文件播放保留；其它源与附加能力遵守 [产品与 API 基准](.trellis/spec/shared/product-and-api.md)，不因历史文档扩大范围。
播放控制优先核查 libmpv 能力。Rust / React / Tauri 已删除，历史实现只从 rust-final 查阅。

## 必须加载的规范

1. 开工前检查工作树、身份、当前任务与 [.trellis/workflow.md](.trellis/workflow.md) 的适用流程。
2. **所有任务必须读** [通用规范索引](.trellis/spec/shared/index.md) 及其列出的四篇正文，覆盖产品 / API、目录、安全与交付要求。
3. **Go 核心 / 绑定**：读 [backend/index.md](.trellis/spec/backend/index.md) 和相关主题。
4. **桌面 / 手机 / TV**：读 [frontend/index.md](.trellis/spec/frontend/index.md) 和对应平台文档。
5. 读 [思考检查入口](.trellis/spec/guides/index.md)，按领域检索 docs/lessons，再完整阅读即将修改的代码及注释。
6. 独立复核后按适用门禁验证；最终报告已改、已验、未验及剩余风险。面向用户输出用中文。

CodeGraph 使用见 [CODEGRAPH.md](CODEGRAPH.md)；实际源码以工作树为准，文本检索用 rg。
各端最终安装包位置及检查命令以 [构建与交付规范](.trellis/spec/shared/build-release.md) 为准，不提供中间产物目录的下载入口。
用户最新要求优先；影响行为、数据、安全或兼容性的冲突先澄清。

## 文档职责

| 位置 | 用途 |
|---|---|
| [.trellis/spec/index.md](.trellis/spec/index.md) | 当前目录与执行规范总入口 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 面向开发者的技术概览 |
| [docs/go-migration/SPEC.md](docs/go-migration/SPEC.md) | 跨端架构与 FFI 设计正本 |
| [docs/go-migration/COMMANDS.md](docs/go-migration/COMMANDS.md) | 命令契约真源，生成绑定不手改 |
| [docs/plugin-system/SPEC.md](docs/plugin-system/SPEC.md) | 插件设计正本 |
| [docs/lessons/](docs/lessons/) | 领域故障过程、根因、实测与失效条件 |
| .trellis/tasks/ | 单次需求、方案与研究 |
| .trellis/workspace/ | 会话记录 |

UI 设计正本为 docs/go-migration/UI_PC.md、UI_MOBILE.md、UI_TV.md；草稿位于 docs/desktop-drafts.html、docs/tv-drafts.html、docs/mobile-drafts/。
具体实现与最新反馈冲突时核实并更新对应文档，不把旧草稿当新增功能授权。
源码、设计正本、工具链与缓存不为规范归档而搬迁。

<!-- TRELLIS:START -->
# Trellis Instructions

These instructions are for AI assistants working in this project.

This project is managed by Trellis. The working knowledge you need lives under `.trellis/`:

- `.trellis/workflow.md` — development phases, when to create tasks, skill routing
- `.trellis/spec/` — package- and layer-scoped coding guidelines (read before writing code in a given layer)
- `.trellis/workspace/` — per-developer journals and session traces
- `.trellis/tasks/` — active and archived tasks (PRDs, research, jsonl context)

If a Trellis command is available on your platform (e.g. `/trellis:finish-work`, `/trellis:continue`), prefer it over manual steps. Not every platform exposes every command.

If you're using Codex or another agent-capable tool, additional project-scoped helpers may live in:
- `.agents/skills/` — reusable Trellis skills
- `.codex/agents/` — optional custom subagents

Managed by Trellis. Edits outside this block are preserved; edits inside may be overwritten by a future `trellis update`.

<!-- TRELLIS:END -->
