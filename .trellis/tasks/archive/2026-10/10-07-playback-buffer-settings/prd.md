# 播放缓冲容量设置

## 目标

让Android手机/TV用户可选择自动或自定义内核播放缓冲容量，磁盘视频缓存保持既有独立配置。用户已确认“目标容量”的语义，可实施。

## 已核实事实

- `core/config/prefs.go:31-37,163,424`已有磁盘预取缓存容量，默认512MiB，范围64MiB～4GiB；`core/prefs/prefs.go:128-176` get/setPrefetchSettings读写。手机`ui/pages/SettingsPage.kt:831-879`读取并透传但没有容量编辑控件；TV同样没有容量控件。
- Android Media3 1.11.0，`ui/player/ExoEngine.kt:98-119`直接Builder创建，未配置LoadControl。该版本本地AAR字节码显示targetBufferBytes是加载目标/判据，不是严格内存上限；时间优先和内存压力参与决策。配置在构建前生效。
- MPV内建baseOptions与Android platformOptions没有缓存参数，启用了mpv.conf。Android版本线索在`core/player/load.go:99-101`，不可把Linux测试libmpv 0.35.1的行为直接套用。通用setProp存在不代表缓存参数动态支持已核实。
- 既有播放器prefs命令为player.get/setPlaybackPrefs；增返回字段无需手改生成绑定。

## 已确认范围

Android手机/TV共用配置与两个内核接入，自动为默认。缓冲不要求填满再起播；不改服务端、不引入新缓存框架。旧配置与全局其他偏好保留。真实设备性能收益单独验证。

## 关键决策

buffer_target_bytes=0表示自动；自定义64～512MiB，以64MiB步进，进入播放后保持本次Media3实例策略，下次播放生效。仅缓冲压缩媒体数据目标，不含解码、纹理、字幕及代理内存，不能承诺硬上限。手机/TV均沿既有组件，不新增依赖或命令。

## 验收方向

默认自动维持现有策略；自定义值实际传入对应内核，回自动不残留旧值；非法输入不保存，旧配置无字段保持兼容；手机与TV设置保存及失败回滚回归；起播/换集/fallback/手选记忆回归保护。范围与数值边界在语义确认后收敛。
