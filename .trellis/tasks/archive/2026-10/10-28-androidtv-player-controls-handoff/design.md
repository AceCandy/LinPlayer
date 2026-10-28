# 设计与边界

## 当前差距
TV 已解析和消费 prepared_id，也有 Media3 预载，但没有手机的内核队列接续。当前 OSD 为上下分散的图文按钮，进度固定宽度。

## 最小改动
- TvPlayerParts.kt：底部 OSD、自适应进度、纯图标控件；更多承接上一集/插件。
- TvPlayerPage.kt：接入 queued/adopted 生命周期，沿用 PlayerController 停止屏障、ExoPreload 所有权和核心现有命令。
- TvKit 仅按需要增加向后兼容参数，不改变其它浏览/手机样式。
- 对应 TV/播放器测试与截图 fixture；必要时补专门 TV 队列测试。

## 接续约束
排队不改变当前条目，只有内核实际切换后接管；接管不再 stop/play/load。手动换集保留已就绪 prepared_id，取消在途准备和队列；离页等待接管后读取正确的条目/位置。迟到回执受 request、controller 和离页状态限制。不能承诺任何设备和片源都零黑屏。

## 非目标
不重新设计播放核心、不增加视频轨切换接口、不改手机视觉、无 APK 交付。

## 紧凑播控修订

用户确认采用左侧季集/片名、右侧工具组、下方进度和时间的布局。删除常驻播放按钮，下一集位于右组首位；真实版本规格移入既有媒体信息。

改动仍集中在 TvPlayerParts/TvPlayerPage：进度条持有确认暂停/提交语义，根节点处理隐藏或未知时长的确认；方向键只唤醒，提示使用明确的焦点路径，不抢确认键。长按与进度预览避免重复提交。调整 TvFocusTest、TvPlayerLayoutTest、TvPhoneParityTest、VisualAlignmentTest 和 TvPageShots 的输入/断言；不改队列、核心、共享手机控件或依赖。
