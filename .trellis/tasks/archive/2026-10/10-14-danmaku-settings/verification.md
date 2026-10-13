# 验证结果

- 独立只读复核：核心未发现阻断；UI指出保存失败读数不恢复与总开关乐观值问题，已修复并加入失败回归。滚动范围实际经过ready守卫，不存在读取失败仍写入的问题。
- Go：check-core.sh完整通过（vet、无缓存全包测试、出库、FFI、宿主契约、差分和插件检查）；补充非法参数测试后player/config再次vet与无缓存测试通过。
- 绑定：check-bindings.sh全部通过，314条契约两端一致。
- Android：PhoneDanmakuPanelTest 4项、PhonePlayerMenuTest 7项、DanmakuSourcesTest 3项全部通过；参数名检查249处通过。
- 实际Robolectric Popup截图检查横屏、竖屏200%字体，验证无额外遮罩、右侧280dp、可滚动开关；真实窗口Back回一级，外点关闭。
- git diff --check与隐私门禁通过，无新增真实地址/凭据。
- 未验：真机播放观感、系统手势返回、桌面/TV实际播放。不生成安装包、不提交Git。
- 剩余限制：防重叠采用完整生命周期占行，密集时减少显示数量；全局仅当前安装，不同步其它设备。
- 本轮隔离Gradle daemon已停止，临时日志/截图/隔离构建输出已清理。既有工作树改动保留。


## 提交前审查
- 三路只读审查覆盖当前全部未提交的播放器、弹幕核心、手机设置与测试；修复后再独立核验。
- 修复总开关落盘失败后仍遗留内存值：回滚原Prefs，并与样式写入共用锁；追加真实保存失败回归。
- 滚动范围未加载时明确禁用，避免看似可点击却不提交；真实Popup测试追加禁用断言。
- 排除“样式失败覆盖其它成功值”的误报：Android load/set同锁，读取与保存不交错。
- NextUp旋转未测量位置仅为时序推断；OSD隐藏期间没有底栏点击目标，重新显示后由布局测量更新，未发现稳定可达遮挡。
- 存量配置层通用setter与其它模块的并发事务缺口早于本次改动，本次只闭合弹幕设置与总开关；未进行全仓配置事务重构。


## 全部未提交改动的最终审查与回归
- 核心完整门禁通过；Android共144项，143通过。唯一失败是既有LogicTest“长按播放键换的是另一个内核”的未知值预期，与HEAD现有实现不一致，非本次回归；本次相关96项全部通过。
- 相关覆盖：PhoneEnginePlaybackTest 52、PhoneNextUpTest 4、PhonePlayerOsdTest 11、PhonePlayerPanelTest 7、PhonePlayerMenuTest 7、PhoneDanmakuPanelTest 4、DanmakuSourcesTest 3、DanmakuClockTest 4、DanmakuLayerTest 3、LogicTest弹幕钟1。
- 未验证真机解码播放、系统旋转/手势返回与密集弹幕性能；未重新出包。
