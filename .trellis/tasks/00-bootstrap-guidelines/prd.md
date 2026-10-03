# 项目规范整理与填充

## 需求

将 LinPlayer 的长期开发规范集中到 .trellis/spec，按真实 Go 核心和原生外壳组织。
根入口负责加载，设计正本保留原位，历史故障与当前执行规则分开维护。

## 边界

- 保留 backend / frontend / guides 路径，新增 shared 与总索引。
- 文档使用中文，规则有真实代码、测试、脚本或用户确认的产品约束依据。
- 不移动业务源码、缓存或构建目录，不修改 Trellis 运行脚本与任务状态。
- 不改变产品范围、API 行为、产物命名或既有授权要求。
- 前序手机主题修改保留，本次只整理规范与入口。

## 验收清单

- [x] Go 核心规范补齐目录、错误、日志、JSON 持久化和契约门禁。
- [x] 外壳规范覆盖 Avalonia 桌面、Compose 手机与 TV，区分平台刻度和验收方法。
- [x] 通用规范覆盖产品 / API、目录、安全与统一交付路径。
- [x] 根入口指向 spec，初始化占位和不适用的上游模板内容已移除。
- [x] 规范链接有效，Trellis 可发现各层，现有任务上下文引用没有失效。
- [x] 已有源码 / 测试例子可追溯，产品 / API 正文迁移未改写，托管入口保持不变。

## 入口与证据

- 规范总索引：`.trellis/spec/index.md`
- 通用层：`.trellis/spec/shared/index.md`
- 核心层：`.trellis/spec/backend/index.md`
- 外壳层：`.trellis/spec/frontend/index.md`
- 每层包含 Pre-Development Checklist 与 Quality Check，并链接代表实现与测试。
- 2026-10-04：链接、层发现、任务 jsonl 引用、diff 格式与隐私门禁通过；独立文档复核已完成。
- 本次文档变更不执行应用编译、出包或设备验收；任务保留原状态，提交 / 归档按后续授权处理。
