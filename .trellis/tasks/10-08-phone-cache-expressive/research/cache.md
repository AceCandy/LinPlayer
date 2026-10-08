# 持久媒体缓存研究

## 事实

- 两套内核共用 Go `play()` 起播路径：`Play` / `PlayResolve` 都调用 `play()`，并在最后分叉（`core/player/playback.go:104-120`）；`startPrefetch` 只让 `DirectStream` 走代理（`playback.go:316-321`），所以在调用层已有跨内核共用入口。
- 当前身份信息：`PlaybackTarget` 有 `URL`、`ItemID`、`MediaSourceID`、`PlayMethod`（`core/emby/playback.go:35-45`）。Emby `rawMediaSource` 可读 `Path`、`DateCreated`、`ID`、`Size`、`Bitrate`、`RunTimeTicks`（`core/emby/emby.go:157-170`）；但 target 构造只保留 item/source id、时长等，不保留 Path/Size/DateCreated（`core/emby/playback.go:131-147`）。无 ETag/Last-Modified 字段/探测处理证据。
- `ResolveStream` 对 DirectStreamUrl 或拼接原始 stream URL 均设 `PlayMethod="DirectStream"`（`core/emby/playback.go:149-171`）；当前代码不会由此识别 DRM。
- `prefetch.probe` 只发 `Range: bytes=0-0`，读 `Content-Type` 与 `Content-Range` 的总长度；无有效总长度则拒绝代理（`core/net/prefetch/prefetch.go:294-327`）。没有记录/比较 ETag、Last-Modified 或校验字节摘要。
- 本地代理响应只输出 Range 状态、`Content-Range`、`Accept-Ranges`、`Connection`、Content-Type 和 Content-Length（`core/net/prefetch/serve.go:360-381`）。请求解析只读取 Range，不读取条件验证头（`serve.go:412-443`）。因此 Range + 长度只能定位字节范围，不能证明内容没被替换。
- 当前 ring 文件名为 `s<pid>_<total>_<seq>.part`，启动新实例会 `O_TRUNC`，槽映射 `slots` 仅在内存（`core/net/prefetch/cache.go:207-240`）；`diskCache.close` 关闭并删除文件（`cache.go:343-349`），`Handle.Close` 会调用它（`core/net/prefetch/prefetch.go:171-177`）。`sweepOrphans` 会删掉其他进程 pid 的 `.part` 文件（`cache.go:180-200`）。故当前明确是会话内缓存，无法跨退出恢复。
- `proxyFor` 仅按完全相等的 upstream URL 复用同一代理（`core/player/warm.go:40-75`）；停止、转码分支会 `closeSharedProxy`，Play Stop 行 559（`core/player/playback.go:318-321,543-560`）。预热与正式播放可复用同 URL 代理。
- 现有大小设置 `prefetch_cache_bytes`，缺省 512 MiB，Clamp 64 MiB 至 4 GiB（`core/config/prefs.go:35-41,170,431,541-545`）；它目前只决定单个流 ring 槽位容量，且最少会按线程数扩展槽位（`core/net/prefetch/cache.go:229-239`），不是全局所有媒体的 LRU 上限。
- 已有统一缓存目录 `paths.PrefetchCache()`（`core/paths/paths.go:52-60`），`CacheSize()` 统计整个 `cache/`（119-134），`ClearCache()` 清整个 cache 并保留目录（136-156）；`system.clearCache` 使用这套 API（`core/system/system.go:82-92`）。清缓存和活动缓存并发的处理目前未见保护。
- 目前缓存块 4 MiB（`core/net/prefetch/cache.go:22`）；普通流会按 Range 请求供给，带缓存命中及重拉逻辑（`core/net/prefetch/serve.go:289-315,392-409`）。Range 不对齐的首块可能仅存残段，但头/尾 pinned 块强制整块拉取（`serve.go:98-110,126-150`）。旁路模式也使用 disk cache：`StartPassthrough`（`prefetch.go:239-240`）被 `proxyFor(...,false)` 调用（`warm.go:55-59`）。
- 转码流不会走字节代理（`core/player/playback.go:316-321`）；项目自身注释说明转码 URL 是分段流，字节代理无意义（同处）。本地文件播放不走这里的 `PlaybackTarget`/Emby proxy 入口（`playback.go:100-102` 说明 `Path` 是另一条探针本地文件路径）。
- 桌面 Go core 也通过同一 `core/player` 包的 `Play` 路径；没有找到单独桌面磁盘缓存实现。`proxyFor` 在 core/player/warm.go，有共享播放器代理单例，路径位于平台无关 Go core。

## 推断 / 设计约束

- 以 `ItemID + MediaSourceID` 为稳定键可做到同条目同版本基本身份隔离，但它不能证明服务端源文件内容未变；同一 source ID 对应内容替换时可能误命中。`Path`、`Size`、`DateCreated` 可作为额外版本指纹，但项目未证明其在所有服务端实现上稳定/可靠；大小尤其不能排除等长替换。优先考虑「账户/服务器身份 + ItemID + MediaSourceID + 可得的 Path/Size/DateCreated + 上游 URL 规范化」的元数据键，并在每次新建代理 probe 时检查长度/可用 validator；任何已存身份校验不符即丢弃缓存。不要将含 `api_key` 的 URL 原文写入可读 metadata；URL token 轮换会导致 URL 键失配，直接 hash 原始 URL 仍会因 token 变化失去复用。
- 当前没有内容一致性证明：Range/Content-Range/Content-Length 证明范围和尺寸，不证明内容版本；ETag/Last-Modified 若上游提供且每段请求可带 `If-Range` 并一致响应，能做服务端 validator 校验，但当前没有此行为。最稳可用内容哈希需读完对应字节，不能预先验证所有缺失区间；分段哈希会增加索引与写入协议复杂度。
- 最小可恢复实现应把每个媒体缓存做成独立数据文件 + 原子提交的 metadata（缓存身份、总长度、块大小、有效块位图/索引、validator、最近访问时间）；只把完整成功写入且同步元数据标记的块视为命中。启动时扫目录、校验 metadata/文件长度，清除临时/损坏/validator不符条目。停止播放只关代理/网络 worker，保留已提交块和 metadata。
- 当前 ring 依靠内存 `slots` 表和覆盖槽；直接保留 ring 文件但丢 slots 会误把未知/旧槽当缺失，需恢复槽元数据或改为带块索引的持久分块文件。环形覆盖和全局 LRU 属不同淘汰模型：多个媒体共享容量上限需要 cache manager 统一预算，不能只复用每流 `PrefetchCacheBytes`。
- 淘汰正在读写的数据需引用计数/pin/锁定条目；ClearCache 也需要处理仍打开的文件句柄。POSIX 删除已打开文件与 Windows 行为不同，跨平台 core 不能假设删除即可安全释放。至少保留当前播放的被读块，淘汰其余最久未访问完整块/整媒体条目。
- 代理服务必须在缓存未命中时可从上游拉取；用户要求停止后不继续回源，故停止时取消所有 fetch goroutine并保证不会写入已保留的条目。重启命中期间需要从当前新 URL 发请求补洞，而不是恢复旧代理端口/签名 URL。
- DRM 不在 `PlaybackTarget` 中暴露判据，本轮未证实服务能否返回 DRM 直传；安全边界应限定仅当前明确的可 Range、可读 DirectStream，且不处理 HLS/DASH/Transcode；对未知/不支持 Range/无总大小/验证变化的流旁路且不持久化。需单独确认 Android Media3 对本地代理 Range 的使用是否一致，Go 接口只返回 PlayURL 给 Exo（`playback.go:88-102,109-120`），缓存机制可透明共享。

## 必须验收的测试

1. 同一稳定身份先写入若干完整块、关闭 Handle/代理后重建，读取已缓存范围不触发上游请求；缓存缺块仍可按 Range 拉取并正常回放。
2. Media3 与 MPV 使用相同 `ItemID/MediaSourceID` 及不同播放 URL/token 时命中同批块；不同 account/server、不同 item、不同 source id 均不串数据。
3. 相同长度但 validator 或可用源版本元数据变化时不返回旧字节；服务端不提供 validator 时明确验证降级策略，至少不把长度相等宣称为内容一致。
4. 进程重启后可恢复完成块；模拟半写入、进程中断、损坏/缺失 metadata、文件短读时不命中错误块并能重拉。
5. 总磁盘占用多个媒体并发下受全局上限约束；LRU 淘汰时跳过正在播放/在读/在写条目，释放引用后可淘汰；调低上限也最终收敛。
6. Stop 取消所有未完成上游请求但保留完整块；切片/转码/本地文件等不支持路径无缓存误用。
7. `system.cacheSize` 反映媒体 cache 实际文件，`system.clearCache` 清空并避免在播代理继续读到已清数据；多平台（Android/Linux/Windows）文件删除/重启行为符合实现。

## 未覆盖 / 未知

- 没有查外部资料（依任务要求）。未核实 Emby 服务端对 `Path`/`DateCreated`/`Size` 变化语义，也未核实 ETag/Last-Modified 是否稳定提供或 Range validator 行为。
- 没有深入检查 Android Media3 DataSource/HTTP Range 请求实现、账户 token 生命周期、DRM 识别能力、存储目录可用空间/Android 清理策略；跨端调用影响只核实到共享 Go core 入口。
- 工作树已有大量他人修改（`git status --short`），本报告只新增本文件；未改生产代码、未运行测试。
