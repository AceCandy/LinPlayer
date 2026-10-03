# 目录与文件归属

## 当前布局

| 路径 | 职责 |
|---|---|
| `core/` | Go 业务核心、命令总线、C ABI 与核心测试 |
| `apps/windows/` | Windows / Linux 共用的 C# / Avalonia 外壳 |
| `apps/android/` | Kotlin / Compose 工程，手机与 TV 共用核心接入 |
| `bindings/csharp/`、`bindings/kotlin/` | 从命令表生成的绑定及绑定验证工程 |
| `third_party/libmpv/` | 链接所需头文件与导入库；可拉取的运行库不入仓 |
| `oauth-proxy/` | OAuth 中转与官网静态站 |
| `scripts/` | 构建、打包、生成与门禁的可执行入口 |
| `docs/` | 面向开发者的概览、设计正本、草稿与历史经验 |
| `.trellis/spec/` | 当前规范正文，见 [总索引](../index.md) |
| `.trellis/tasks/`、`.trellis/workspace/` | 任务材料与会话记录 |
| `.toolchain/` | 本地工具链与缓存，不入仓 |
| `build/` | 核心中间输出与最终交付产物，不入仓 |
| `VERSION` | 版本号唯一权威 |

## 放置规则

- 新功能优先进入既有功能包、页面或组件；不为单次使用另建抽象目录。
- 不移动业务源码、设计正本或生成目录来解决文档归属问题。
- 当前目录结构以工作树为准；旧 Rust / React / Tauri 代码通过 `git show rust-final:<路径>` 查阅。
- `docs/cankao/` 是参考项目，只有明确需要参考对应实现时读取。
- 根规范文件提供加载入口，详细执行规则进入 spec；研究与临时方案进入任务，故障过程进入 lessons。
- SDK、Gradle、桌面编译器需要的中间目录保留原位，不能作为用户交付入口。最终位置见 [构建与交付](build-release.md)。

## 核查

目录依据：[开发概览](../../../docs/DEVELOPMENT.md)、[忽略规则](../../../.gitignore)。新增文件检查归属与忽略状态；移动规范时同步索引、Markdown 链接和任务 jsonl 引用。
