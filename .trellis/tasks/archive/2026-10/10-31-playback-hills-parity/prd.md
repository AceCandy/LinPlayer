# 对照 Hills 缩短首开与重进起播

用户反馈：上轮优化后，首开和退出重进仍明显慢于 Hills。要求找出差距并修复。

## Hills 1.10.0 静态证据（docs/cankao APK，jadx 反编译）

- 原生播放器参数 `EmbyPlaybackData` 已携带 Flutter 侧取得的 `playbackInfo`，原生页直接起播。
- Exo 路线：按服务器可关的磁盘缓存（`/exo_cache`、`exoDiskCacheSize`、`clearExoDiskCacheOnPlaybackEnd`），下一集预加载 `enableExoPreload`。
- mpv 路线：`loadfile` 带 `stream-lavf-o=cache_dir=…,cache_key=…,prefer_libcurl=1,lazy_open=1`，字节按 cache_key 落盘；`lazy_open` 命中缓存时不建上游连接。默认 `demuxer-lavf-o` 含 `multiple_requests=1`、`short_seek_size=16777216`，MKV 默认跳过 Tags、延迟 Cues（定制 ffmpeg）。
- 结论：重进命中缓存时 Hills 只剩 PlaybackInfo 一次请求，播放数据不碰网络。

## LinPlayer 差距（代码路径）

1. 重进：停播关闭共享代理；再次 `player.play` 时 `prefetch.start` 必须同步探测上游（跟 302、取 ETag）后才打开持久缓存，缓存再全也先等一轮网络。
2. 每个代理 `tlspolicy.Transport()` 新建连接池，探测和取块无法复用上一会话或详情预热的连接，每次重新 TCP+TLS。
3. 首开：详情预热片尾走直连、不进代理缓存；且先拉 32MB 头再拉尾。Exo 打开 MKV 必先读片尾 Cues，代理对头尾固定块整块拉取，索引字节要等块内前面数据下完。

## 本轮已做

- 代理复用判据改为 `sameStream`：同一媒体身份、同一 scheme/host/path，只是授权或 PlaySessionId 不同也复用详情预热的代理，并用 `Retarget` 换成新地址（保留 302 落点与版本校验器）。
- 所有代理共用一个上游 Transport，预热、起播、退出重进之间复用 TCP/TLS 连接。
- 代理支持后缀 Range；详情预热先拉片尾、再拉头部，片尾经代理写进固定块。下一集准备的片尾仍走直连（固定块下到一半不取消，准备槽暂停时要让出带宽）。
- 多条连接同时要同一个固定块时，后来者从共享登记处接着读，不再重复下载；失败时供给端倒回游标重拉。
- 持久缓存单片上限 128MiB → 256MiB，大于 mpv 默认 150MiB 向前缓冲，退出时续播位置所在块不再被挤掉。

- 先播后验（用户 10-31 确认“要和 Hills 一样快”）：同步探测确认版本后在缓存目录写 version.json（身份、验证器、总长、类型、PlaybackInfo 报的大小）。再次起播（详情预热、正式起播、下一集准备；流影带读取限流除外）命中同身份记录、报的大小与确认时一致且块0在盘时，不等上游直接供给，后台 Range 0-0 校验；大小或验证器变化则删该版缓存并停掉代理（本次报错，下次同步探测），网络/授权失败不下结论。停掉的代理不再被复用。

## 验收

- 起播时取流地址只差 PlaySessionId/api_key 也复用预热代理；不同账号、线路、媒体源不复用。
- 有已确认缓存时起播不等上游（上游不可达也能出首块）；PlaybackInfo 报的大小变了改走同步探测；同大小换内容时代理被停掉、旧版缓存与记录删除，下次起播读到新版本。
- 预热后片尾固定块在代理缓存中；后缀 Range 返回正确字节；多连接同块只下载一次。
- 续播位置落后于 150MiB 向前缓冲时仍在持久缓存中。
- 原有 prefetch / preload / player 测试与 race 通过；不改授权、续播、上报顺序和默认内核。
- 真机首帧与 Hills 同条件对照不在本次自动化范围内，需用户实机确认。
