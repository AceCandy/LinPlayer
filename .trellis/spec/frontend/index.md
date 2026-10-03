# 原生外壳规范

适用 C# / Avalonia 桌面端与 Kotlin / Compose 手机、TV；不是 React 前端。

| 文档 | 内容 |
|---|---|
| [目录](directory-structure.md) | 两套工程与各端入口 |
| [共用组件](component-guidelines.md) | 组件复用、主题、布局与反馈 |
| [桌面端](desktop.md) | Avalonia token、UI 线程、导航与风格刻度 |
| [Android 手机与 TV](android.md) | Compose 主题、两套导航、资源和 JNI |
| [请求生命周期](hook-guidelines.md) | 任务取消、离页和旧响应 |
| [状态归属](state-management.md) | 核心业务状态、宿主会话、设备偏好 |
| [JSON / 类型边界](type-safety.md) | 生成绑定、参数名、空值与错误 |
| [质量检查](quality-guidelines.md) | 真实渲染、焦点、主题与出包 |

## Pre-Development Checklist

- 读 [通用规范](../shared/index.md)、目录、共用组件与对应平台文档。
- 状态或网络改动读请求生命周期和状态归属；命令变化读 JSON 边界与核心规范。
- 对照对应 UI 设计正本、当前源码与用户最新反馈；不套用旧 WebView / DOM 手法。

## Quality Check

- 复用现有 token、组件、导航与反馈入口，业务规则保持在核心。
- 改布局、主题或焦点有实际渲染 / 按键证据；不把截图测试等同于真机播放。
- 执行 [质量检查](quality-guidelines.md) 与 [统一出包规则](../shared/build-release.md)。
