# 定位与参考

- `SharedBrowse.tvFocus` 统一画 2dp 焦点框；与按钮反色或隐私霓虹可能叠加。保留其焦点恢复职责。
- `TvKitShell.TopNavigation` 的非 overlay 分支直接铺 `TvC.bg`；手机 `Scaffold.LpImmersive` 渐隐背景是项目已有可复用做法。
- `SharedTopNavigation` 的 Down 委托 `content.requestFocus()`；首页缺少首排向上时与 LazyListState 协调的回顶动作。
- 现有 `SharedTvBrowseTest` 覆盖续播多卡下行与焦点记忆，尚无回到顶栏后轮播起点坐标的断言。
- Google TV 官方焦点规范允许缩放、背景/内容颜色等组合，不强制套框：https://developer.android.com/design/ui/tv/guides/styles/focus-system
- Apple《Designing for tvOS》指南突出焦点内容而非混淆聚焦与选择，仅作为设计参考。
- 本轮不引入第三方库；参考不替代项目实际渲染和方向键验证。

## 回顶故障与验证修正

- 去掉上返测试的导航 `RequestFocus` 兜底后，四个组合中三个失败。日志显示首排卡片已经位于屏幕 y=0，与悬浮导航重叠，几何上行搜索不能离开；列表此时也不在第 0 项，原来按滚动起点拦截的方案无效。
- 最终由首页先执行正常向上寻焦，搜索失败或向上退出列表边界时显式转移到当前导航，再滚动到第 0 项。共享 `FocusRequester` 直接挂当前栏目，不再维护导航回调 holder、导航获焦回顶 holder 或额外选择标记。
- 参数与路径参考项目 Compose BOM 2026.08.00。官方焦点组文档说明滚动容器默认成组，`onExit` 可通过 `cancelFocusChange()` 取消默认转移，再由页面处理导航：https://developer.android.com/develop/ui/compose/touch-input/focus/focus-group
- 测试仅在建立首屏时使用列表滚动语义；故障路径全部使用方向键，断言先真实下滚，再回首页导航、轮播原始坐标以及再次下行/上返。直接指定焦点只用于其它组件级/恢复测试，不用于本次回顶通过条件。
- 独立复核提出“成功的上行搜索可能绕过回顶”的运行时疑虑。四种真实根路径中，列表上行退出由 `onExit` 接管，搜索失败由预览按键处理；均通过焦点与轮播坐标断言。没有增加未经复现的额外焦点状态，真机连按仍列为未验。
- 裸 `LpTheme` 下孤立 TV Material 导航的语义焦点测试没有重现实际填充，不能作为无胶囊证明。改为实际 `TvRoot` 下左右键移动并采样导航像素，发现浅色主题焦点胶囊回归；修正为栏目透明底加粗、动作反色，深浅均通过。

## 提交前审查

- 两路只读审查分别覆盖首页/导航接线与共享组件，未确认新的焦点或手机端回归。
- 发现 `TvButton` 的既有菜单键分支未按 `enabled` 门控；插件 Button/Chip/Pressable 可同时传禁用状态与长按回调。按钮启用时先聚焦，再禁用，TV Surface 仍保留焦点，菜单键继续执行长按动作。
- 新增 `disabledTvButtonDoesNotRunMenuActionAndCanBeEnabledAgain` 在原实现失败：禁用后的动作计数期望 1、实际 2。菜单回调改用 `onLongClick?.takeIf { enabled }`，与共享 `tvFocus` 的既有禁用处理一致；不改变启用时的点击、长按和菜单键契约。
