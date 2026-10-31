# Media3 拖动模式与手机进度缩略图

## Goal

Media3 内核拖动进度时进入自带拖动模式让画面跟手；MPV 内核手机端补上已缓存字节的拖动缩略图

## Requirements

- 用户 2026-11-01：「丝滑拖动和视频帧的加上吧」。
- Media3 内核：拖动时 setScrubbingModeEnabled + 逐次 seekTo（Media3 内部合并过密 seek），松手先同步退出拖动模式再走 controller.seek 提交；取消回起点，起播/离页/换核仅收尾；起播未就绪/换片中不进入。
- MPV 内核：保持松手才 seek；手机拖动预览复用核心 player.thumbnail（只读已缓存字节），横滑与进度条共用同一预览。
- 视频帧不引入 media3-inspector：缩略图须只读缓存（不给服务器添压力），核心已有实现且 MPV/Media3 都能用。

## Acceptance Criteria

- [x] PlayerControllerTest：未就绪不进拖动模式；取消回起点；提交后不被拉回。
- [x] 回归先复现再修正：提交前退出拖动模式、重新起播与换核收尾、缩略图取消异常继续传播。
- [x] 手机预览随媒体/内核重置，重试清空；页面销毁释放拖动状态。
- [x] 手机预览差值按起拖位置；预览期间不叠缓冲圈；进度条收起/取消时清预览。
- [x] TV Media3 预览游标走拖动模式，缩略图取图改用共享方法。
- [x] UI_MOBILE.md / UI_TV.md 同步。
- [x] 实现与自动回归完成；真机效果移交 android-playback-device-validation，未声称通过。

## Validation

- 本机 SDK/JDK 可用，已执行 Kotlin 编译；PlayerControllerTest（44）、PhonePlayerOsdTest（14）、PhoneMedia3RecoveryTest（3）、xyz.linplayer.app.tv.TvPlayerLayoutTest（13）共74项通过，0失败/跳过。先前“无 SDK”记录不成立。
- 未出安装包、未验证真实设备的非关键帧精度和实际跟手体验。
