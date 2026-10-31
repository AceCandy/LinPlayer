# TV 下一集控件移至右下角并修正收起态焦点

## Goal

将 TV 播放页下一集控件固定到右下角，按 OSD 显隐调整位置，并修正收起态确认键与上下键的展开及焦点行为。

## Requirements

- TV 播放页的“下一集”控件固定在右下区域。
- OSD 显示时，下一集控件位于底部播放控制按钮上方；OSD 收起时，下一集控件位于底部细进度线之上，并保持半透明提示态。
- OSD 收起且下一集提示存在时，第一次确认只唤出 OSD 并将下一集控件完整显示，第二次确认才播放下一集。
- OSD 收起且下一集提示存在时按上键或下键，唤出 OSD 后焦点仍落在下一集控件；不误落到进度条或其它按钮。
- 不改变自动连播、季末退出、下一集数据来源和核心命令契约。

## Acceptance Criteria

- [x] 右下布局在 OSD 显示和收起两种状态下符合位置要求。
- [x] 收起态确认键遵循“唤醒并聚焦 → 再确认切集”。
- [x] 收起态上/下键唤醒后焦点仍在下一集控件。
- [x] 相关 TV 布局与焦点测试通过；未进行真机遥控器验收时在交付说明中明确标出。

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
