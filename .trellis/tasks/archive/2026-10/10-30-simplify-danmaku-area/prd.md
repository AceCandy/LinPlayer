# 简化弹幕显示区域设置

## Goal

移除播放页弹幕的行数和排版高级入口，只保留 1/4、1/2、3/4、全屏显示区域，并同步 Android、Windows 与核心回归。

## Requirements

- 播放页弹幕设置只保留一个“显示区域”设置，循环档位为 1/4 屏、1/2 屏、3/4 屏、全屏。
- Android 手机、Android TV 和 Windows 播放页显示相同的四档区域标签，并按相同顺序循环。
- 播放页不再展示滚动行数、顶部行数、底部行数、粗体、合并重复、防止重叠和热力图等高级入口；已有底层字段、配置和回显契约保留。
- `0.75` 直接复用核心现有合法范围和排版公式，不修改命令签名或持久化格式。
- 补充区域循环和 3/4 屏排版回归，保留原有 1/4、1/2、全屏行为。

## Acceptance Criteria

- [x] Android 手机和 TV 只显示“显示区域”，四档循环顺序正确。
- [x] Windows 播放页可以显示并提交 3/4 屏，不把它误显示成全屏。
- [x] 核心读取、保存和排版仍接受 `0.75`，旧配置字段不丢失。
- [x] 相关 Android、Go 测试及 Windows 静态检查通过；未执行 APK/桌面安装包和真机验收时在交付说明中明确标出。

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
