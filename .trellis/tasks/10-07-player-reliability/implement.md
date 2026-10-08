# 实施计划

## 本批顺序
1. [x] 独立只读核对 Android/桌面控制能力，主线程抽查与读取即将修改源码。
2. [x] 评审 PRD/design；激活实施任务。
3. [x] 新增纯诊断状态与测试：READY/首帧分离，低缓存/已缓冲区分，倍率/暂停/抑制/seek/切片/EOF/后台重置，告警去重。
4. [x] 同一 rememberExoPlayer 接入监听与采样，日志经 IO 写盘；平台回归验证真实 listener、生命周期/离页取消和同页换片。
5. [x] 独立只读复核，修复发现并运行相关门禁。
6. [x] 同步规范和验收记录；不自动提交，保持后续阶段待实施状态。

## 验证
- 环境：复用 .toolchain/jdk-21.0.2、android-sdk、gradle。
- 相关 JVM：`:app:testDebugUnitTest --tests '*PlaybackHealthMonitorTest' --tests '*Media3DiagnosticsTest' --tests '*PlayerControllerTest' --tests '*PlayerEngineLifecycleTest' --tests '*PhoneEnginePlaybackTest'`。
- 字符串命令门禁：`python3 scripts/check-android-args.py`。
- Kotlin 编译包含于相关 Gradle 测试，必要时检查 release 编译/包；若出包只提供最终 build/android 路径并验签。
- diff/隐私：`git diff --check`，新文件检查，项目 secrets 门禁；测试不得接入真实账号/媒体地址。
- TV 真机、手机真实视频首帧、低内存设备性能、MPV/桌面呈现信号本批未验，明确记录。

## 回滚点
阶段 A 无持久化/协议变化，移除共用观察入口即可完全停止新增运行时诊断。后续阶段在独立任务中实现及验收，不混入本批 diff。
