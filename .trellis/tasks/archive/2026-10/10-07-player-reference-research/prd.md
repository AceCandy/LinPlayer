# 参考项目播放器能力深度调研

## 目标
从 docs/cankao 的七个本地项目源码提取值得 LinPlayer 借鉴的播放机制，并核对当前实现，形成有出处、优先级和边界的建议。

## 范围
- plezy、Moonfin-Core、CineIsle、Ghosten-Player、JellyCine、Plozz、qEmby。
- 播放内核决策与故障恢复、轨道/字幕、会话/生命周期、网络与起播、连续播放、设备音视频适配。
- 当前 LinPlayer：Go 核心、Android Media3/MPV 控制器、Avalonia/libmpv 桌面。
- 仅调查本地快照，区分已实现代码、文档宣传和待验证推断；不拉取上游，不运行外部服务。
- 仅 Emby 与本地播放相关机制；不扩大源类型，不改 MediaStationGo，不实施播放器功能；TV 真机继续暂缓。

## 验收
- 七个参考项目均有源码调查记录，不能只列 README 功能。
- 关键发现提供 file:line、符号和机制/局限；对照 LinPlayer 实际代码而不是猜测缺失。
- 给出近期可做项、已有项、需要设备验证/平台专用的项及暂不引入项。
- 高优先级结论由主线程抽查关键源码，报告静态调研与运行验证的差异。
