# 验证记录

2026-10-07，可靠性C阶段第一批，Android共用控制器的跨内核歧义拒选。

## 已改

删除TrackIdentity.ordinal及snapshot序号累计。matchingTrack只在至少有一个有效标题/语言并且候选唯一时返回，重复标签/无身份不再使用旧序号猜选。其它默认选轨和pending消费/手选/off路径未改变。同步Android规范，保留其它批次的未提交改动。

## 已验

- 红验证：先只新增回归，旧实现17项中2项失败，确切失败为“重复标签不能用旧序号消歧”和“歧义不能提交选轨”；不是编译或环境失败。
- 绿验证：PlayerControllerTest 17、PlayerEngineLifecycleTest 2、PhoneEnginePlaybackTest 5、TvTrackTimingTest 1，共25项，失败/错误均0。包括ISO语言等价、列表反序、唯一匹配、无身份拒选、歧义不发实际命令且不消费pending、后续唯一恢复一次、手选音轨/字幕关闭、晚到外挂与手机/TV接线。
- 独立只读审查核对共用调用者、移除字段兼容性、pending及off行为、实际命令断言，未发现本批阻断问题。未执行设备验证。
- check-android-args.py：257处通过；已跟踪/未跟踪源文件隐私门禁、git diff --check、任务jsonl验证通过。
- pack-android.sh arm64-v8a标准出包通过。实际apksigner验证v2/v3通过，签名者1；APK与中间release产物字节一致，只有arm64-v8a，liblpcore/libmpv为AArch64 ELF。
- 手机包build/android/app-arm64-v8a-release.apk：63179996字节，SHA256 `3ee1371eb4bbf8e7bd53d03e00731cd0280d7dde3e54adb523cfe6aaf98a1e6c`。本环境未配置远程崩溃上报。
- 本批临时日志删除。未提交、推送或归档；保留待设备验收状态。

## 未验和风险

未验证手机真实视频换核轨道效果，TV真机继续暂缓；目录内TV包是旧产物，不属于本批交付。未改变桌面端，因此本批没有桌面新包。

拒选后保留新内核当前选择，用户可手选；缺少标签或跨核标签不同的媒体不保证自动恢复。唯一标题/语言仍不证明forced/SDH/位图语义相同；这些字段和跨集记忆属于后续批次，阶段C尚未完成。

审查记录存量待确认风险：restoreTracks已经发出的恢复命令不可撤回，手选发生于命令在途时，仅清pending不能保证原生命令完成顺序。本批回归保护的是晚到轨表尚未提交的恢复，不宣称消除了在途选轨竞态。

回滚本批身份/匹配改动与新增回归即可，无持久化或协议变更。
