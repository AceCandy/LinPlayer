# 选轨顺序验证记录

日期2026-10-07。本批仅Android控制器和手机/TV接线；保留前批全部未提交改动，不改Go/服务端/绑定、轨道字段或持久化。

## 实现与独立复核

恢复、手选、详情初选、回退字幕关闭与seek/stop共用transportMutex。不可撤回选轨等待真实命令回执才放锁，手选先登记意图，离页/换片推进代数并失效排队请求。

手机/TV点击UNDISPATCHED登记；手选等待也受NonCancellable保护，面板关闭不能丢已登记选择，页面停止由门/代数使其失效。TV详情初选改走restoreInitialTrack，当前媒体已有同类手选时跳过。

两次只读复核：初轮指出TV详情初选绕锁，主线程纳入同一屏障；最终复核未发现可证明的阻断交错，核对两端生产回调、锁顺序、pending消费、代数和停止/起播流程。未运行设备播放。

## 回归与门禁

- 旧实现红验证：18项中1项失败，恢复选轨取消后，停止和新起播越过在途命令，实际事件为恢复开始/停止/新起播而预期只有恢复开始。
- 控件反向验证：临时把手机MPV回调改回直接app.call，真实面板点击的命令断言明确失败（预期[1]、实际[1,2]）；finally还原生产源码后运行最终回归。
- 最终49项通过，失败/错误均0：PlayerControllerTest 22、PlayerEngineLifecycleTest 2、PhoneEnginePlaybackTest 5、PhonePlayerPanelTest 7、TvTrackTimingTest 2、PlaybackServiceTest 11。
- 覆盖在途恢复→手选最后提交、audio/sub/off、面板取消不丢登记、恢复取消仍等回执、排队手选离页失效、失败后可手选、详情初选晚到不覆盖、下一媒体重新允许初选、真实手机面板移除后手选提交、真实TV焦点/Enter字幕/off接线，兼验已有seek/换核/通知生命周期。
- 新手机夹具补齐stopPlayback回执；面板移除后的测试等待推进主线程再观察完成。TV入口采用实际焦点和Enter按键，JVM触摸注入未打开该入口，不据此认定产品故障。临时语义树输出已移除。
- check-android-args.py 253处通过，已跟踪/未跟踪隐私门禁、git diff --check、任务jsonl validate通过。

## 设备与剩余边界

未验手机真实视频换核/音轨切换和系统行为，TV真机继续暂缓；不以JVM核心替身和控件测试证明原生视频效果。本批没有桌面新包或TV新包，目录内TV包是旧产物。

核心永久无回执时继续等待，不能提前放锁让旧命令影响新媒体。Media3同步手选未改变。forced/SDH/位图语义、跨集记忆尚未实施。

最终审查还记录提交门关闭时点击会登记意图但不提交的时序疑点，未证明旧轨面板在该窗口可操作；不把它称作已验证故障。门关闭时不提交旧运行期轨道ID是当前安全约束。

本批无持久化/协议变更，可回滚本批控制器及页面回调和测试。未提交、推送或归档；真实设备验收待执行。

## 最终交付

标准pack-android.sh arm64-v8a成功，实际APK的v2/v3验签通过，release中间文件与最终APK字节一致；仅arm64-v8a，liblpcore/libmpv ELF通过。远程崩溃上报本环境未配置。

build/android/app-arm64-v8a-release.apk：63179997字节，SHA256 `b95fc20ac82e9f422793b3855aef152cbfc09f72953ed78e578fc525c483ac8a`。
