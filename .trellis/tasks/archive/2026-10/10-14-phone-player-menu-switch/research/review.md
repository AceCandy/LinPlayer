# 审查与验证记录

- 新增回归先失败：已提交seek目标75秒被旧时钟12秒覆盖；普通字幕未恢复；停止失败提前消耗自动回退；主服2秒覆盖明确40秒。修复后Android 173项通过。
- Media3 1.11.0（91fd5df7ab6b5c2f1a589fbdee5ee8deeae97f02）Format.selectionFlags默认0，C.SELECTION_FLAG_FORCED为1<<1，未设置代表false；源码 https://github.com/androidx/media/blob/1.11.0/libraries/common/src/main/java/androidx/media3/common/Format.java 。MPV缺位仍保持unknown。
- 独立复核菜单、媒体信息及状态条未发现阻断问题。切核审查提出旧stop可能影响新页面，按PlayerPage离页stopIn登记、起播awaitPendingStop及控制器transportMutex链路核实：新页面等待旧收尾，现有取消/离页回归覆盖，不增加重复屏障。
- Android 173项零失败，参数检查252处通过；核心与绑定门禁在配置本地Python、.NET、libmpv路径后全部通过。生成绑定无需内容变化。
- Robolectric截图已检查隐藏状态条、更多菜单和媒体信息布局；未做真机解码、切核首帧、系统电量与实际网络读数验收。
