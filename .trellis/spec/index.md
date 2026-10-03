# 项目规范入口

这里保存当前可执行的工程规范。任务、设计正本与历史故障分别保存，避免同一规则有多份独立正文。

## 文档归属

| 位置 | 保存内容 |
|---|---|
| `AGENTS.md` / `CLAUDE.md` | 开工入口、必须加载的规范与工具专属说明 |
| `.trellis/spec/` | 当前目录职责、开发约束、接口边界、验证与交付规则 |
| `.trellis/workflow.md` | Trellis 任务阶段与运行流程 |
| `.trellis/tasks/` | 单次需求、方案、计划与研究材料 |
| `docs/go-migration/` / `docs/plugin-system/` | 架构、命令与插件设计正本；保留原路径 |
| `docs/lessons/` | 故障症状、根因、实测过程、失效条件；链接当前规范 |
| `.trellis/workspace/` | 开发者会话记录 |

用户最新指令优先。旧文档与当前实现冲突时先核实；涉及行为、数据、安全或兼容性的取舍必须澄清，不把历史描述当作新增需求。

## Pre-Development Checklist

1. 所有任务先读 [通用规范](shared/index.md) 及其列出的四篇必读正文，再读 [思考检查入口](guides/index.md)。
2. 改 Go 核心层或绑定：读 [核心层规范](backend/index.md)。
3. 改桌面、手机、TV：读 [外壳规范](frontend/index.md)，再选对应平台文档。
4. 按领域查 `docs/lessons/`；读即将修改的源码与长注释。
5. 任务的 `implement.jsonl` / `check.jsonl` 按需引用上述规范；创建文件不代表自动注入全部正文。

## Quality Check

- 规则有真实源码、脚本、测试或设计正本依据，路径存在。
- 每层索引包含开工与验收检查，未保留初始化占位内容。
- 新规则只在对应 spec 维护正文，根入口与历史记录使用链接。
- 规范目录变化后运行 `python3 .trellis/scripts/get_context.py --mode packages`，核查任务上下文引用。
- 本仓库没有 Trellis 上游模板目录；不向全局安装或其它仓库同步本地规范。
