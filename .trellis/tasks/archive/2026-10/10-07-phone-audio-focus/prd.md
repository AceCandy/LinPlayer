# 手机音频焦点尊重页面手动操作

## 范围与依据
用户已授权继续核查手机后台、通知和音频焦点；TV 真机暂缓。
短暂失焦设置 resumeOnGain 后，页面暂停直接走控制器，未清标志；duck 期间页面调音量未清恢复旧值的标志。

## 最小方案
保留页面控制器分派，在页面暂停、音量操作时同步服务的焦点恢复意图。不改解码、TV、系统路由。

## 验收
- 短暂失焦正常恢复播放；页面手动暂停后获得焦点保持暂停。
- duck 默认恢复原音量；用户调整后获得焦点保留新值。
- 通知控制两种内核的暂停、继续、停止与位置正确。
- 服务回归先红后绿，独立复核、参数检查、隐私扫描与手机包验签。

## 未确认线索
静态共享绑定可能被旧服务销毁清空，尚无真实平台事件序列复现，不纳入本次修补。
真机锁屏、来电、路由和厂商后台策略仍需设备验收。

## 执行结果
- 2026-10-07：两项用户意图回归先在无意图同步的原焦点逻辑下失败（均期望0次命令、实际1次），修补后通过。
- PlaybackServiceTest 6、PlayerControllerTest 10、PhonePlayerPanelTest 6、PhonePlayerOsdTest 3，共25项通过。
- 两次独立只读复核：整改duck测试，实际通过PlayerController设置60%，FakeCore最终音量保留60；最终复核通过。
- check-android-args 255处调用通过；task validate 与 diff检查通过；隐私扫描1469文件无新增，存量baseline411未改。
- 媒体会话跳转仅验证实际注册的回调分派，Robolectric不证明系统transport派发。
- 未验：真机后台/锁屏/来电、音频路由、厂商电源策略、TV真机；静态绑定销毁时序仍是待确认线索。
- 手机最终包：build/android/app-arm64-v8a-release.apk，63,098,071字节，SHA256 b4a91597ab4efb51eb539b4eeffd4a139e78cb47bd3891e459aa52030b20e9fc；与本次Gradle产物一致，13个arm64 ELF库含libmpv/liblpcore，apksigner min23下v1/v2/v3通过。TV旧包未更新。
- Gradle --stop确认无daemon；无新调试服务或本地临时文件。用户已确认本轮具体提交清单，执行本地提交、归档及日志记录，不推送。
