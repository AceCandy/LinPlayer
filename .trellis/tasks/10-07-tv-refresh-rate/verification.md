# 验证记录

## 实现

- 新增 TV 页面窗口偏好拥有者，两个内核复用同分辨率整倍频选择；分数/整数帧率分开匹配，保留当前/已请求匹配模式，不重复请求。
- 切集/换版本/回退重新启动帧率采样，窗口偏好仍归页面存续；离页恢复原 preferredDisplayModeId，保留其它窗口属性。
- Media3 在 READY 后读取格式/选中轨；仅 TV 窗口管理期间关闭其 Surface 帧率策略，未释放实例退出时恢复。
- MPV 沿用 opts.container-fps 与 mpvGet(vf) 实际对象返回；lpinterp 启用或滤镜回读失败时恢复原显示偏好，不更改补帧设置、核心缓存或 Go 契约。
- Android 规范与 UI_TV 正本同步；上一轮双内核未提交改动全部保留。

## 独立复核

- 两路只读审查分别核对生命周期/Surface 与模式算法/测试。主线程按出处抽查新采样循环、核心 mpvGet 返回、TV 切目标和 Surface 销毁路径。
- 生命周期审查未发现阻断问题。模式审查提出补帧相邻轮询可能重新降频的疑点：源码在每轮匹配前检查滤镜，命中后直接 continue，没有周期内重设匹配模式的路径，因此没有按该推断增加状态缓存。补了起播已补帧的连续采样断言，另补滤镜读取失败、Media3 选中视频轨回落，均通过。补帧动态启用有最多约一轮采样的检测延迟，未扩展输出帧率匹配。

## 已通过

- 相关 JVM/Compose 回归共 63 项、0 失败：新增 TvRefreshRateTest 8、TvRefreshRatePlaybackTest 8；既有双内核/手机/TV 回归 47。
- 模式算法覆盖 23.976/24/25/29.97/50/59.94 fps、整数倍、最低匹配频率、同分辨率隔离、invalid/unsupported 和系统未执行请求时的保留。
- 窗口恢复覆盖 API24 与 API36、非零原偏好、其他 flags/preferredRefreshRate 保留及关闭后拒绝写入。
- 真实 TV 播放页按键覆盖同/异帧率切集、退出恢复；忠实模拟不能撤回的 FFI 请求，旧请求获得完成机会后仍不能覆盖新目标或离页状态。Media3→MPV 回退重新匹配，策略恢复与已释放实例均有断言。
- 故障注入：临时删除 close 中 restore，13 项新测试中有 3 项按预期失败（真实播放页退出、迟到响应后退出、非零偏好恢复）；恢复实现后通过。故障代码未保留。
- Android 参数门禁：256 处 UI 调用通过。
- 隐私扫描覆盖 tracked + untracked 的 1454 文件（含本验证记录），无新增命中，未改既有基线/豁免。
- git diff --check 与任务 JSONL validate 通过。

## 交付与边界

- `bash scripts/pack-android.sh tv` 通过；最终包 build/android/app-tv-armeabi-v7a-release.apk 为 59,405,044 字节，版本 2.0.0 / versionCode 20000，SHA256 d317b3d8761b3a005ab1d57f29ea0496c94aca6eabdb512312186f9652449a52。
- 最终包与本次中间 APK 字节相同；仅 armeabi-v7a，全部原生库为 ARM32，含 liblpcore.so / libmpv.so。Dex 与 R8 映射保留了本轮窗口模式接线；apksigner 实际验证 v1/v2/v3 均有效，证书与既有手机包一致。额外验 v1 的 minSdk23 参数仅用于签名检查，不修改应用 minSdk。
- 本轮未重出手机包，最终目录中的手机 APK 仍为上一轮产物。构建缓存和最终 APK 已忽略；收尾关闭本轮启动的 Gradle daemon，没有启动 adb 或调试服务。
- 本环境未连接电视/盒子，未验证实际 HDMI/系统模式切换、黑屏时长、HDR/DV、Surface 重建后的首帧及真实补帧输出；Robolectric 不代表这些硬件验证通过。
- 系统可以忽略窗口偏好；采样到无匹配/持续未知时恢复原偏好，不中断播放。未扩大音频透传、DRM/HDR 新接入或自动内核规则。
- 用户已确认整理提交当前两轮改动，形成一个本地播放基线，不推送；本任务保持 in_progress，TV 真机验收按用户要求延后，不能视为设备验收通过。
