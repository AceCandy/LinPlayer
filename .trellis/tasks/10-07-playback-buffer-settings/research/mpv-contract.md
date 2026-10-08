# Android libmpv 缓冲选项契约

## 项目锁定版本

- `.github/workflows/libmpv-android.yml:19-26` 固定 Android 构建依赖；`MPV_REF=e167836802da6d5a4301bd4c4eeb3c5c3c17ccb8`（同段注释称 HopperRender 分叉点），另锁 `MPV_ANDROID_REF=276dad8e07a34bf1b22b1cfb257f2d24ba659c7d`。
- `scripts/fetch-libmpv-android.sh:18-19,26-28` 当前下载 tag 默认为 `libmpv-android-4`，Android so 不随源码入库，且 SHA 固定。故下文按 workflow 的 mpv commit 核验，不把“最新版”行为当依据。上游源码链接：`https://github.com/mpv-player/mpv/tree/e167836802da6d5a4301bd4c4eeb3c5c3c17ccb8`。
- `core/player/load.go:100-101` 项目注释记 Android 打包版本为 `v0.36.0-549`；它是辅助线索，workflow commit 是更明确的源码锁。

## mpv 运行期语义（上游锁定 commit）

- `demux/demux.c` 的 `demux_conf` 声明 `demuxer-readahead-secs`、`demuxer-max-bytes`、`cache-secs` 等选项；`demux_opts` 默认 `max_bytes = 150 * 1024 * 1024`、`min_secs = 1.0`、`min_secs_cache = 1000*60*60`。`demuxer-max-bytes` 为 `OPT_BYTE_SIZE`，`cache-secs` 为 `OPT_DOUBLE`，故属性字符串设值应分别使用字节数格式（可带 `MiB`）和秒数格式。
- `DOCS/man/options.rst`（同 commit）对 `--demuxer-max-bytes` 原文：“This controls how much the demuxer is allowed to buffer ahead.”；达到任一限额后 demuxer 停止读更多 packet，限额可因技术原因略微越过。`--cache-secs` 是 cache active 时预取秒数；cache 开启时它在大于 `demuxer-readahead-secs` 时覆盖后者，但实际通常仍受 `demuxer-max-bytes` 限制。要调的是总缓冲上限时，单独改 max bytes 是直接的限制；若意图指定网络 cache 的预读时长，相关联的 `cache-secs` 也可能影响结果。
- 运行时可写性：`player/command.c:422-448` 的 `mp_property_generic_option` 将通用选项 property 的 `M_PROPERTY_SET` 转给 `m_config_set_option_raw(..., flags=0)`；`command_init` 在 `player/command.c:8022-8063` 为未标 `M_OPT_NOPROP` 的配置选项注册同名 property。mpv 公开 API `player/client.c:1311-1334` 的 `mpv_set_property` 初始化后走同步 property setter。仓库 Android 的 `setProp` 已用 `mpv_set_property_string`（`core/player/player.go:742-759`）。因此这些普通选项可运行期写入，而不是仅能 pre-init `mpv_set_option_string`。
- 当前 demuxer 生效时点：`demux/demux.c:2624-2633` 每次 demux `thread_work` 调用先 `m_config_cache_update`，有变更便调 `update_opts(in->d_user)`；`update_opts`（约 `2556-2600`）把 `opts->max_bytes`、`min_secs` 等复制到当前 demux internal 的 `in->max_bytes` / `in->min_secs`。所以调节会传播到当前 demuxer，在线程下一轮工作时生效，通常无需等下一次 loadfile。上游 `demux/demux.c` 同一函数行 2631-32 仅在 max limits 总和变小时清 packet pool；增大上限不会立即读满，只会在后续 prefetch 条件允许时继续读。
- `--demuxer-max-bytes` 表述的是前向 buffer 限额，不保证瞬间达到该容量。cache 是否 active 仍由 `cache`（默认 auto，按 stream 判断）及 `cache-secs` 等共同决定；手册说明启用 cache 时受 max bytes 限制。

## 保留/恢复 mpv.conf 的原值

- 当前核心有现成 init 后属性读取 `Prop(name)`（`core/player/player.go:484-500,761-762`），运行期写属性入口 `setProp(name,value)`（同文件 `742-759`）。`mpv.conf` 在 `ensureConfDir` 过滤后给 mpv 读取（`core/player/subtitle.go:386-414`），已实测配置会覆盖 init 前设置（`core/player/player_options_test.go:115-135`）；过滤器明确只禁止影响画面/绕过过滤的键（`subtitle.go:417-450`），不禁止缓冲值。
- 最小可靠恢复方案：mpv 初始化成功之后读取并缓存当前有效值 `demuxer-max-bytes`（可同样采样 `cache-secs`），即此时配置文件已读入、用户的 `mpv.conf` 已形成的值。每次播放前若自动档，写回该缓存值；若自定义档，写所选值。不要把 mpv 编译默认 150 MiB 当作“恢复自动”的值，否则会覆盖用户 conf 的有效配置。
- 若需要区分“mpv 内建默认”和“当前 mpv.conf 覆盖值”，上游还暴露 `option-info/<option>/default-value`：`player/command.c:4071-4129` 取 `m_config_get_co_default` 并输出 `default-value`。这通常是配置定义默认值，适合区分 built-in default；恢复用户 conf 原值仍应使用 init 后采样值（或显式读取/解析原始配置）。建议把 option-info 作为可选核对工具，不代替采样。
- 用户配置当前被读的是过滤副本，而不是原始目录，且原文保留在另一处（`subtitle.go:388-414`）。因此“mpv.conf 原值”准确含义应为“过滤后实际生效配置中的值”；缓冲选项目前不在禁止列表里。

## Android-only 最小接入点

- 初始化后采原值：`core/player/player.go:312-368` 的 `ensureMpv()` 在 `mpv_initialize(h)` 成功后、`mpvH = h` 前后最接近；此时 `h` 局部句柄可直接 `mpv_get_property_string(h, name)`，或在赋 `mpvH` 后使用 `Prop`。加 Android build-tag helper 可避免 desktop 变化；另一现成 Android 文件是 `core/player/surface_android.go`。`platformOptions()` 当前已有 Android 专属分支（`player.go:160-...`），但配置采样发生在 `ensureMpv` 更直接。
- 每次 load 前应用值：公共唯一入口 `loadWith` 在 `core/player/load.go:38-48`，它先设置 header/UA 再 `mpvCommand(loadArgs...)`；它被 Emby 和 source 使用，但也被 Android/桌面共用。要避免改变桌面语义，最小门控是 Android-only helper 在 `loadWith` 中调用（或以 Android build-tag helper 在其前设置），由 Android 实现做采样值/用户档位处理、非 Android 实现 no-op；`loadWith` 不宜另创一个只服务部分入口的加载路径。核心 Android vs 桌面运行时标记若无现成标志，优先 build tags（Go `android`），不要靠 UI 层分别覆盖。
- 起播调用链：Emby `playback.go:233-241,257-258` → `loadWith`；源 `source.go:46-58,78` → `loadWith`；URL `playurl.go:45-47` → `loadWith`。本地 `playFile` 是另一条直接 `mpv_command(loadfile)` 路（`player.go:764-788`），须确认需求是否要覆盖本地播放；仅“所有 load 前”才需要在那里也调用同一 helper。Android平台 `playFile` 的调用适用性本次未继续追踪，待实现者按播放入口范围核实。

## 证据边界

- 本结论基于仓库锁定的 mpv 源码 commit 在线 raw GitHub 源码及该 commit 文档，没有在真机用该 so 实测 property 返回码/生效时延。demuxer 工作循环说明的是逻辑传播到当前 demux 内部限额，不承诺网络读取线程瞬时完成或缓存立刻填充。
- `Prop` 的字符串读取可表示属性当前值；最小实现写入时应检查 `mpv_set_property_string` 返回值。现有 `setProp` 只记 debug、吞返回错误（`player.go:742-759`），适合常规偏好但不利于 UI 确认自定义值真的接受。

## 实施收敛：用本片选项而非全局基线

主线程另核实v0.36.0 `DOCS/man/input.rst:448-471`（旧语法）与workflow锁定commit `input.rst:542-607`（新语法），均明确loadfile options在文件播放期间生效、结束恢复；v0.36.0 `player/loadfile.c:1158,1851`使用M_SETOPT_BACKUP及restore_backups。因此采用loadfile本片demuxer-max-bytes，不需额外采样/恢复基线。项目注释版本与workflow源码锁所用loadfile语法不同，代码保留既有旧新语法探测，不能把workflow锁当实际已装so的运行证据。

Linux本地0.35.1真实libmpv探针：全局预设96MiB→本片128MiB→stop后96MiB→自动下一片仍96MiB，全部通过。只是Linux版本运行证据，不能替代Android so/真机。临时WAV在TemporaryDirectory清理，实例已terminate_destroy。
