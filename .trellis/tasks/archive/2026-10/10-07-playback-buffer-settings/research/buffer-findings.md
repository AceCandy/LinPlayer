# 只读调查

两个独立探子只做探索，无源码修改和测试运行。主线程点验现有ExoPlayer创建与PrefetchPanel/命令入口。

## 配置

- PrefetchCacheBytes控制磁盘环形缓存，64MiB～4GiB，默认512MiB。手机和TV当前没有容量编辑UI。
- 手机播放器设置PlayerPrefsPanel，TV PlaybackGroup；PlaybackPrefs在core/player/prefscmds.go:32-45,336-352。默认和约束归core/config/prefs.go。
- 命令未新增时生成绑定无需手改。Android命令响应字段门禁check-android-fields.py可校验。

## Media3

项目依赖1.11.0。探子从本地AAR classes.jar javap核实DefaultLoadControl.Builder的setTargetBufferBytes、setBufferDurationsMs、setPrioritizeTimeOverSizeThresholds。shouldContinueLoading通过已分配总字节>=目标值判定；优先时间且内存许可时仍可加载到minBufferUs。target bytes不是allocator硬容量限制。运行时替换LoadControl未发现公开入口；Builder配置在播放器构造前完成。尚未设备验证。

## MPV

Android版本线索v0.36.0-549，load.go记录旧loadfile语法。baseOptions和platformOptions无显式缓存选项，但读取mpv.conf，因此自动不能被硬编码为某个默认值。运行期通用属性写入存在；demuxer-max-bytes/cache-secs/readahead-secs可写及恢复原值语义尚未核实。需同版源码/文档证据；Linux测试库0.35.1不能代替Android。

## 修正沟通

原建议的“容量上限”措辞超出Media3原生参数保证，后续应使用“目标容量”并明确内存边界；磁盘容量存在不等于设置页可编辑。先澄清产品语义再实施，禁止用阈值承诺严格内存上限。
