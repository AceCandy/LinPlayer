# 验证记录

## 新增/修改测试（除先播后验两条依赖新接口外，均先在旧代码上确认失败）

- prefetch：TestSuffixRangeServesTailAndCachesPinnedChunk、TestProxiesShareUpstreamConnections、TestPinnedChunkInFlightIsSharedAcrossConnections、TestPersistentKeepsResumeBlockBehindDefaultForwardBuffer
- player：TestProxyForReusesSameMediaAcrossPlaySessions（含停掉的代理不复用）
- prefetch 先播后验：TestLazyOpenServesCacheWithoutUpstream、TestLazyOpenDropsReplacedVersion；TestPersistentIsolationAndValidators、TestPersistentLastModified 按新语义改为“换版本后本次代理被停、下次读到新版本”
- preload：TestC27_头尾都要热而且尾部用后缀Range（先尾后头）、TestC27_取消后不再打头部

## 门禁

- `go test -race`：net/prefetch、net/preload、player（需 LD_LIBRARY_PATH 指向 .toolchain/mpv-test）、emby 通过。
- `scripts/check-core.sh`：go vet + 全部 go test、出库、差分对账、lp check、SDK 骨架、UI 产物通过；FFI 契约与 COVERAGE 用 python3 手动跑通过；C# 契约测试因本机无 dotnet 未跑；插件 UI 三端一致仍是原有的 TV 壳 6 条入口缺失。

## 复核结论

- sameStream 只作用于 DirectStream 原流；磁盘缓存本就按同一 Identity 共享，额外要求 host/path 一致，不扩大误判面。
- 复用的代理已与预热 ctx 解绑（WithoutCancel），预热取消不影响起播。
- 固定块跳过在途下载：载体失败 / 载体连接关闭 / 载体写盘失败三种情况都能经 nextBytes 倒回游标自愈。
- 后缀 Range：运行中的代理 totalSize 恒 > 0；N 大于总长时钳到 0。
- 256MiB 单片仍受全局预算约束；默认 1GiB 预算下可留片数约从 8 部降到 4 部。
- 先播后验：版本记录须与所在目录的哈希键一致才认；旧缓存没有记录时首次同步打开补写，不清空已有缓存；代理被停后 Android 显示“播放提前结束/起播失败”，只有距结尾 5 秒内才判播完。

## 未验证

- 真机首帧时间与 Hills 对照（本机无 adb）。
- 真实 Emby DirectStreamUrl 是否每次带不同 PlaySessionId（MediaStationGo 不带，本机未连真实 Emby）。
