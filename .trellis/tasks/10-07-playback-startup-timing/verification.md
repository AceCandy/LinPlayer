# 验证记录

## 已验证

- JVM相关唯一28项通过：StartupTimingTest 5、PhoneEnginePlaybackTest 9、Media3DiagnosticsTest 3、PlayerEngineLifecycleTest 2、PlaybackHealthMonitorTest 9。
- 最后调整目标起点为局部捕获、stop后同route提交后，PhoneEnginePlaybackTest 9项再次通过。
- 生产Media3加载记录断接线：移除startupTiming.load，专项1项因等不到startup_load真实功能断言超时而失败；恢复后相关套件绿。不是编译或fixture故障。
- 真实已登记listener注入首帧、重复首帧、加载前旧媒体错误、播放器从null晚创建、离页撤listener；生命周期pause后恢复仍拒绝迟到首帧。
- 真实NavHost目的页面消费详情点击起点；一次消费，普通入口无点击值。
- 生产MPV请求阶段首帧unsupported、生产Media3地址与load分开、换版本/换集origin=target及独立attempt、自动回退等待长请求后两内核独立attempt；播放请求数未增加。
- Android参数检查255处通过；已跟踪1500文件/未跟踪85文件隐私检查无新增真实地址凭据，411处旧基线未更改；git diff --check通过。
- 两轮独立只读审查，处置见review.md。

## 未验证及风险

- 无手机真机冷/热起播、网络/低内存性能数据，listener注入不能替代真实首帧渲染；没有性能提升结论。
- MPV没有可信首帧回调，只有命令前后；seek恢复测量、TV真机和桌面未纳入本批。
- 极端同一次Compose提交中程序化连切并返回同目标未验证；正常目标/版本切换及回退已回归。
- 无UI布局改动，未新增截图验收；日志只本地现有Logs，不新增上报或日志设置。

## 交付

- 手机最终包 `build/android/app-arm64-v8a-release.apk`，63,196,383 bytes。
- SHA256 `7ef4c46f66152ab5b655065e0e54c2bc254a97d5c81b2645eaa5fde857acb402`。
- apksigner v2/v3=true；v1=false（存在v1证书不等于v1 scheme验证有效）。
- 仅arm64-v8a，13个so全部ELF64 AArch64；liblpcore/libmpv存在。
- 最终包与本次release中间产物逐字节一致；Dex包含load_to_frame_ms、address_to_load_ms、first_frame_supported和startup诊断字段。
- 本次pack日志和断接线备份均已清理；未启动调试服务。未提交、未归档，等待设备采样与验收。
