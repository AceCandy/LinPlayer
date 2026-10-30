# 简化播放页弹幕设置

## Goal

将 Android 手机与 TV 播放页弹幕设置按常用显示项与高级显示项重新分组，使用直白文案，保留现有核心字段与配置兼容。

## Requirements

- 播放页弹幕设置先呈现“显示弹幕”、当前匹配、时间偏移和“显示设置”等常用入口。
- 显示设置优先展示滚动区域、字号、不透明度和滚动速度；高级行数、粗体、重复合并、防重叠等能力仍可访问，但不与常用项混成一组。手机已有的进度条热力图继续放在高级页，TV 沿用当前不展示热力图的行为，因为 TV 播放页没有对应绘制入口。
- 将“滚动行数 / 顶部行数 / 底部行数”等文案改成能说明弹幕位置或作用的中文，并在同组说明 0 行的含义。
- 手机与 Android TV 播放页使用一致的分组概念和文案，保留现有焦点、返回层级、滑块提交、开关提交和错误状态行为；平台没有实际绘制能力的选项不新增假控件。
- 不修改 `DanmakuStyle` 字段、核心命令、持久化键或默认值，不清理已有用户配置。
- 仅改播放页设置，不把弹幕源管理或屏蔽词管理扩展到本次范围。

## Acceptance Criteria

- [x] 手机弹幕设置的常用项和高级项分组清晰，首次进入不再连续展示所有高级参数。
- [x] TV 弹幕侧栏按同一概念分组，遥控器可顺序访问所有可操作项，返回行为保持原样。
- [x] 现有弹幕样式字段仍能读取和提交，已有滑块/开关回归测试更新后通过。
- [x] Android 定向测试通过；未执行 APK 出包和真机遥控器验收时在交付说明中明确标出。

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
