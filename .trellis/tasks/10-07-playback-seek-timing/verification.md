# 验证

## 已验证

- 相关唯一49项JVM回归通过：SeekTimingTest 5、PhoneEnginePlaybackTest 9、PlayerControllerTest 24、PlaybackServiceTest 11。
- 最后增强媒体会话测试后专项再次通过：真实已登记回调触发MPV/Media3 seek；MPV服务原轮询两次状态推进，target_observed和clock_advanced均标source=service。
- 生产页面诊断入口断接线：去掉ObserveSeekTiming后，连按seek生产页面回归因5000ms内等不到clock_advanced而红；恢复后完整相关套件绿。不是fixture或编译红。
- 连按挂起合并、未提交/旧位置不确认、UI target清除后继续测时钟、暂停到目标不记推进、缓冲重置基线、旧revision回执/失败、命令失败/取消/超时、在途stop屏障、生命周期后台和离页撤销覆盖。
- 独立只读审查未发现阻断；指出的服务真实采样盲区已补专项，见review.md。
- Android参数检查255处通过；隐私门禁已跟踪1500文件与未跟踪96文件无新增真实地址/凭据，411旧基线不变；git diff --check通过。

## 未验证与剩余风险

- 未跑手机真机、TV真机、桌面、真实弱网/低内存/不同倍率/EOF场景性能采样。无实际性能提升结论。
- target_observed表示位置容差命中；clock_advanced要求非缓冲非暂停、累计250ms时钟推进，包含采样等待，不是首帧、画面恢复或渲染健康证明。
- 后台中断不继续本次前台耗时；暂停seek只记录位置到达。EOF附近无250ms推进可能诊断超时，不能认定故障。
- 本批没有UI改动，不新增截图验收；不改缓冲、核心/FFI/服务端或TV策略。既存未提交改动保留；未提交归档。

## 交付

- 手机最终包 `build/android/app-arm64-v8a-release.apk`，63,196,371 bytes。
- SHA256 `a0e4de2024169b7192f6115db4db792634210e48a3976b05012f16b5814bece4`。
- apksigner v2/v3=true，v1=false（证书存在不等于v1 scheme有效）。
- 13个so、仅arm64-v8a，全为ELF64 AArch64，liblpcore/libmpv存在。
- 最终包与本次release中间包字节相同；Dex含sample_source、clock_advanced、target_observed、frame_verified=false，并保留上一批load_to_frame_ms。
- 本批构建临时日志和断接线备份已清理；没有启动调试服务。
