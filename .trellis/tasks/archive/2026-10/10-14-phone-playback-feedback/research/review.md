# 审查与验证

## 已证实缺陷

- 两内核共用StartCached，原先“Media3没有磁盘缓存”的初查结论经play()调用链否定，没有建立第二套缓存。真正的兼容缺口是仅强ETag持久化；基准服务本地ServeContent使用Last-Modified。
- Last-Modified重进测试修前失败（缺If-Range且重复下载），修后相同版本不下载缓存块、版本改变重新下载；弱/缺失/最近修改/no-store仍拒绝持久化。
- 外挂ASS不进MediaItem，新增真实MediaItem测试修前只得到1条而非2条，修后进入轨表。精确Media3 1.11.0 DefaultMediaSourceFactory默认外挂走SubtitleExtractor，完整文件读完一次交付，不是SingleSample路径。
- 重复首批分页回归修前请求第4次才被测试断言打断，修后第2页立即报错。
- Native边界影子下运行真实Libass状态机：切轨新头晚到时旧opened阻止补开，修前断言等待期仍active失败，修后通过。超过4MiB的完整文件修前丢弃，修后完整传递。

## 独立复核

字幕、缓存/选集/状态分开只读复核。采纳ASS大小静默丢弃意见。
“空ID应跳过”未采纳：ID是播放目标和列表key；跳过会破坏按原始页数推进的游标，明确报错可重试，不虚构可播放项。
“assOpen非零应自动重试”未采纳：检查core/ffi/libass_android.go，错误来自不兼容ABI/建库/建渲染器/建轨失败；既有硬失败取消wanted约束保留，不让每条事件重复初始化。用户再次选择仍可重试。

## 验证

- 218项Android回归零失败零错误：ExoSubtitle 4、LibassSelection 2、Logic 49、PhoneDetailCache 37、PhoneEnginePlayback 54、PhonePlayerMenu 10、PhonePlayerOsd 11、PhonePlayerPanel 9、PlayerController 32、SeasonEpisodes 6、TvTrackTiming 4。
- 核心check-core门禁全部通过；prefetch/player/preload竞态检查通过。
- Android参数252处通过；工作树/暂存区隐私门禁与diff检查。
- Roborazzi record实际重录并检查横屏、竖屏小屏大字、低电量图标和状态隐藏前后几何。首次查看旧截图发现录制未开启后重新录制，以新图为准。
- adb未连接设备；本次启动的adb服务已关闭。JNI影子回归证明状态时序，不证明真实字体/解码呈现。

## 未验与限制

用户具体片源、服务器响应与真机字幕/首帧未复现。无可信版本或禁止缓存的媒体仍只会话缓存；持久命中仍需取播放信息和初始化解码器，不能承诺零加载。
