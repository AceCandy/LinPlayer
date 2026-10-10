# TV 刷新率匹配设计

## 边界与数据流

只新增 Android TV 窗口管理与帧率采样，不修改核心、绑定、手机或内核选择。播放页持有一个窗口偏好拥有者，记录入页的 preferredDisplayModeId；退出释放时恢复。换集、更换版本及内核回退仅更换采样任务，不释放窗口偏好，避免同帧率换集先恢复再切屏。

Media3 1.11.0 在 STATE_READY 后读取 videoFormat.frameRate，缺失时读取当前选中视频轨。TV 页面有窗口管理时临时关闭 Media3 的 Surface 帧率策略，退出后若实例未释放则恢复；手机仍用现有策略。MPV 用 player.opts.container-fps，值按核心实际字符串返回解析。

MPV 补帧通过 player.mpvGet {name: vf} 的既有返回 {name,value} 检查 lpinterp 滤镜。滤镜启用时恢复原显示偏好，不按源帧率降频，也不修改补帧档位或 DisplayHz 缓存。滤镜读取失败时同样保留原偏好。TV 当前没有补帧面板，此策略兼容全局/按剧记住的档位以及插件切换；输出帧率匹配不扩大本轮范围。

## 模式选择

- 只使用 display.supportedModes 中与当前物理宽高相同的模式 ID。
- 刷新率与源帧率正整数倍相差不超过 0.01 Hz 时认为匹配，区分 23.976/24、29.97/30、59.94/60。
- 已请求的匹配模式优先保留，避免系统延迟/忽略请求导致重选；实际当前模式匹配时优先当前，否则选最低匹配刷新率。
- 无匹配恢复入页偏好。无效或未知帧率连续 10 秒后恢复；等待新片帧率期间保留上一模式。
- 以源帧率为准，临时倍速不反复切屏。每秒采样，窗口属性值未变时不重复写入。

## 生命周期与限制

采样按 controller、exo、attempt、ready 不可变快照启动；每个挂起命令返回后检查取消，应用前再次检查 ready。离页窗口拥有者关闭后拒绝迟到写入。窗口只改 preferredDisplayModeId，其他 flags 与 preferredRefreshRate 保留。

Android API 23 起支持此窗口属性；项目 minSdk 24。请求可能引发短暂黑屏，系统可忽略请求，均不影响正常播放。不承诺 HDMI/电视的实际切换、HDR/DV 行为。无额外依赖或持久化，回滚删除 TV 接入与新文件即可。

## 证据

- core/player/subtitle.go:189：player.opts 已含 container-fps。
- core/player/transport.go:280：mpvGet 返回对象，不能按旧类型声明当作裸字符串。
- core/player/interpcmds.go:145、205：挂补帧滤镜与输出帧率/显示 Hz 闸。
- Media3 精确 tag 1.11.0 的 ExoPlayer/VideoFrameReleaseHelper：默认 ONLY_IF_SEAMLESS，OFF 可关闭 Surface.setFrameRate；系统未保证它和窗口 modeId 的仲裁。
- Android 官方 WindowManager.LayoutParams：preferredDisplayModeId 是 supportedModes 的 ID，0 为无偏好，系统可忽略。
