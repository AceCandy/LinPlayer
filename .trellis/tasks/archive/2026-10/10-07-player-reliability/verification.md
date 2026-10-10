# 本批验证记录

## 变更
- 已完成播放器可靠性分阶段详细设计与独立设计审查。
- 控制能力核对确认 TV Media3 偏移入口已经隐藏、手机无错误偏移入口；没有新增能力框架或偏移功能。
- 新增 Media3 首帧/READY 测量与缓冲症状分类，接入手机/TV 共用 rememberExoPlayer。
- 保持取流、服务端请求、状态上报、自动回退白名单、控件/遮罩和 LoadControl 行为。

## 已验证
- `PlaybackHealthMonitorTest` 9 项：READY/首帧区分、首帧连续门控、等待数据→已缓冲重新计时、倍率、停止取数/空缓存、暂停/抑制/后台/EOF/无效倍率、seek/真实进展/抖动、未知时长/纯音频、停播/换片。
- `Media3DiagnosticsTest` 3 项：真实 ExoPlayer 已登记 observer、先于页面 setMediaItem、偏好重组不重复、离页撤监听/释放、同实例换片重置、停止拒绝迟到首帧、真实 BUFFERING 数值采样及后台清窗口。
- `PlayerControllerTest` 10 项、`PlayerEngineLifecycleTest` 2 项、`PhoneEnginePlaybackTest` 4 项。合计 28 项通过。
- 忠实故障注入：临时移除 `ObserveMedia3Diagnostics(player)` 接入，接线测试失败（实际 registry 中没有观察器）；恢复后相关测试全部通过。未保留临时源码变化。
- Android 参数门禁 257 处通过；diff 空白检查通过。
- 隐私门禁先因环境没有 `python` 失败，使用 shell 函数映射至 python3 运行原判据后通过；另用同一 Python 扫描器检查所有新文件及改动文件通过，没有新增凭据/地址。不修改门禁或豁免。
- 独立只读代码审查未发现确定高严重度问题；按意见同步日志字段契约，并补入 state/按倍率折算的缓冲快照。

## 验证边界
- 首帧/READY 在测试中忠实注入已登记 observer 回调，未让真实视频解码产生首帧；不能当作真机画面呈现验证。
- 缓冲分类接线测试直接调用 observer.sample；没有独立验证 LaunchedEffect 的每秒周期调度和真实系统后台日志竞态。
- 手机/TV 真机、MPV/桌面真实呈现、已出首帧后的停帧、设备低内存/性能测量未验证。本批未实现自动恢复或网络重连。
- 诊断阈值仍需真实样本校准；误报只产生日志，不会改变播放行为。

## 构建交付
手机 arm64 Release 编译、R8 与 lintVitalRelease 通过；pack-android.sh 核心/native 检查、v1 证书和 v2/v3 签名块检查通过，apksigner verify 通过。最终包与本次 Gradle 输出 SHA-256 一致，只有 arm64-v8a，包含 liblpcore/libmpv。最终产物 build/android/app-arm64-v8a-release.apk，63,180,003 字节（标准脚本整数 MiB 检查为 60 MB，通过既有预算）。没有产出/交付本次 TV 包；目录中的 TV APK 为旧产物。

构建环境未配置 SENTRY_DSN，本包不启用远程崩溃上报；本批诊断使用已有本地 Logs。未修改凭据配置。编译器出现既有 Compose stack trace mapping 收集警告，构建成功；这不影响上述播放行为测试，但未验证崩溃栈映射完整性。
