# Android TV 侧栏即时切换与返回焦点

## Goal

让排行榜搜索设置的侧栏获焦即时切换，右侧返回先回侧栏，修复搜索首焦点与聚合按钮遥控焦点

## Requirements

- Android TV 排行榜、搜索、设置的左栏获焦后立即切换右侧内容。
- 从右侧按返回先恢复左栏焦点，再按返回回到顶部导航。
- TV 设置右侧不重复显示当前分组小标题。
- 从顶部导航进入搜索时，输入框自动获得焦点；聚合搜索按钮可用遥控器方向键和确认键操作。
- 保持手机布局、业务请求和非 TV 行为不变。

## Acceptance Criteria

- [x] 三页左栏即时切换并支持右侧返回左栏。
- [x] 搜索首焦点和聚合按钮遥控焦点可达。
- [x] TV 设置隐藏重复分组标题。
- [x] 定向 Kotlin 编译与 TV 回归测试通过；未出包、未做真机验收。

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
