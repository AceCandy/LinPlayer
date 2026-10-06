# 共用组件与主题

- 页面优先复用已有组件词汇。桌面用 PageBase、Toast、Skeleton、Images.LoadAsync、Nav；手机用 LpScaffold、Panel、LpCell 等；TV 使用 tv/kit，不能套手机触摸控件取代焦点控件。
- 桌面色彩读 Tokens.axaml / Tok.Of，手机读 Lp.colors，TV 读 TvC；布局刻度分别按平台规范，不混用。
- 不透明颜色走 token；叠图的半透明颜色遵守对应控件语义。改变主题时检查实际合成底色，不能只比较独立色号。
- 主题修改覆盖正文、说明、占位、数值、图标、输入框、按钮与弹窗；检查深色、浅色及实时切换。
- UI 按草稿逐页实现，用户点名的控件形态照留；改变控件种类需先确认。草稿与实现冲突按最新反馈核实并记录。
- 设计正本：桌面 [UI_PC](../../../docs/go-migration/UI_PC.md)、手机 [UI_MOBILE](../../../docs/go-migration/UI_MOBILE.md)、TV [UI_TV](../../../docs/go-migration/UI_TV.md)；历史刻度不覆盖当前源码。

代表实现：[桌面样式](../../../apps/windows/LinPlayer.Desktop/Theme/Controls.axaml)、[手机组件](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/components/Base.kt)、[TV 组件](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/tv/kit/TvKit.kt)。

- `LpField` 可选 `trailingIcon` 复用原生输入框尾部槽，`onSearch` 非空时使用 IME Search 并调用该回调；未传时保持已有键盘和布局行为。
