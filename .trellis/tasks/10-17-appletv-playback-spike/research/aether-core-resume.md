# AetherEngine 与完整核心：进度和新进程续播验证

## 范围与依据

用户确认独立 AetherEngine 7.39.0 探针颜色和声音正常后，授权继续衔接 Go 核心。仅修改忽略目录的临时 Swift 宿主与编排脚本，未改产品源码或生成绑定。

- 核心 `player.play(engine=exo)` 是已存在的宿主解码模式，返回 `play_url / resume_secs / media_source_id / play_session_id`。不能传未识别的 aether 字符串，否则会落到 mpv 路径。命令与字段核对 `core/player/transport.go:24-64`、`core/player/playback.go:92-102`。
- 位置/暂停回报沿 `emby.reportProgress`，停止沿 `player.stopPlayback`，由核心维持本次播放会话。reported=true 不等于服务端已保存。`core/player/transport.go:67-126`。
- ABI 为 1；临时桥接串行后台调用 lp_call/lp_next_event，按 seq 等待 result，返回字符串立即 lp_free，关停后检查分配/释放计数。没有初始化 mpv 或 GL 渲染通道。
- MediaStationGo 只读基准提交 `44529d55a739475e1dfcf83d5548a19227c9d357`，工作树有其它任务改动；相关差异为 playback_state 投影去除旧 favorite 列、emby_resume 改用 FavoriteRelation 聚合，不是本任务修改。不能据源码提交认定部署实例完全相同。
- 基准三个 Sessions/Playing 路由均调用 RecordProgress；长于十分钟的媒体需至少 60 秒，有效时长不可得时可以返回成功而不保存。出处 `internal/handler/emby_playstate_handlers.go:13-76`、`internal/service/emby_user_data.go:263-275`、`internal/service/playback.go:44-95`。已按关键位置抽查。

## 临时验证设计

第一轮核心登录并解析固定选定媒体源，AetherEngine 从明确位置播放，回报实际位置和暂停状态，跳到两分钟后停止。独立用户级 GET Item 检查媒体身份、未完播状态及 UserData.PlaybackPositionTicks 与实际停止位置相差小于 1.5 秒。

第二轮必须在首个进程正常停止/关停/退出后新建进程，使用新的 UUID 数据根，不传 resume_secs。验证核心续播决策与刚读回的服务端位置相差小于 1.5 秒，再验证 AetherEngine 实际时间前进与原生层 ready。第二轮停止后再读回。两轮必须均具备应用自己的完成标志，不能只检查 devicectl 返回值。

## 输入和构建

- 沿用 macOS 27.0.1 / Xcode 27.1 / tvOS 27 SDK / Swift 6.4，Go 1.27.2。显式 DEVELOPER_DIR，不改全局选择。
- 核心源码、嵌入资源与 Go 模块从本地提供；模块按 go.sum 核验，远端 GOPROXY=off。未删改核心的播放器引用。
- 固定 MPVKit 1.1.0-n9.0.2，28 个非 GPL、非 macOS 专属依赖逐个校验发布 manifest 中的 SHA-256，只提取 tvOS arm64 真机切片。静态库通过 load_hidden 链接，构建后核查不会导出 mpv/FFmpeg 符号，以免影响 Aether 前缀动态框架。完整共存情况仍以链接与真机结果为准。
- 临时宿主与 Python 编排已经独立只读审查，未发现可确认缺陷；Python 语法检查通过。最终设备运行与清理结果仍需单列。

## 构建问题与修正

- 完整 Go c-archive 编译一直返回 0。首次宿主链接失败，错误筛选漏掉 ld: 诊断；补充后确认是临时脚本使用当前链接器不支持的 -hidden_library。按远程 man ld 改为 -load_hidden，并关闭 Debug dylib 包装以核查实际可执行文件符号。这是探针参数错误。
- 随后只剩 6 个 Vulkan 符号缺失，定位临时提取脚本只处理 framework 目录，漏掉 LibraryPath 直接指向 .a 文件的 MoltenVK。补充文件型切片提取后，核验 28 个二进制均存在；远端 nm 确认 6 个符号齐全。不能仅以归档校验和通过或下载函数成功代替切片内容完整性。

## 真机结果（2026-10-18）

- 首次通过构建后的安装返回 0，但启动被设备休眠阻止，未计运行通过。用户继续后重新构建、签名、严格验签、安装均返回 0；最终符号隔离检查通过，内嵌动态框架 9 个。
- 本轮样本匹配 H.264 / 8-bit / BT.709 / 源时长 4911.082 秒特征。独立前置选片后，同一条目 ID 和媒体源 ID 仅在内存中传给两轮应用；核心解析后断言所选媒体源相同，服务端读回断言条目身份相同，未记录其实际值。
- 第一轮由 Go C ABI 完成核心初始化、登录与 player.play 宿主模式取流，AetherEngine 实际播放至 69.598 秒，原生层 ready=true，解析时长 4911.247 秒。播放、暂停、恢复和跳转后的进度回报均 reported=true；暂停位置漂移 0 秒，跳转后实际位置 126.000075 秒。
- 第一轮停止位置 126.000075 秒，核心正常登出（server_ok=true）、关停；应用输出 automated_pass=true done=1 后退出，devicectl 返回 0。事件 C 字符串分配 21、释放 21，error 事件计数 0。
- 首个进程退出后，独立服务端详情读回 PlaybackPositionTicks 对应 126.000 秒，条目身份一致且 Played 不为 true，与实际停止位置相符。
- 第二轮新建进程、重新核心初始化和登录，并使用新的 UUID 数据根；未传 resume_secs / resume_exact，也未把读回位置当作起播参数。核心自行选出 126.000 秒，与服务端读回一致；读回值仅用于断言。AetherEngine 随后实际播放至 135.196523 秒且原生层 ready=true。这排除了同进程状态和上一轮本地历史遮掩服务端续播失败。
- 第二轮停止位置 135.396525 秒，再次通过核心上报。独立服务端读回为 135.396 秒，身份与未完播状态检查通过。核心登出 server_ok=true、关停，事件 C 字符串分配 19、释放 19，error 事件计数 0；应用完成标志及 devicectl 返回 0 均存在。
- 额外校验了编排器实际读回条件：正常位置通过；身份不一致、零位置、明显位置偏差、已完播、没有停止位置五种场景均被拒绝。共 6 项断言通过；没有把 reported=true 当作持久化验收。
- 本轮补查公开信息，自报兼容接口版本为 v4.8.10.0；不能据此断定为原版 Emby，部署源码提交未核实。

## 清理与边界

- 两轮播放器、完整核心及各自本地数据通道已正常停止，核心服务端登出均为 true；独立前置会话也已登出。应用卸载返回 0，远程临时目录自动删除后独立查询残留为 0，没有本轮调试服务继续运行。
- 核心按现有逻辑在应用临时沙盒保存账号配置与观看历史，卸载后删除。凭据未写入仓库或构建输入；固定版本无凭据输入归档与脚本保留在已有忽略规则覆盖的 .toolchain/tvos-aether-probe，额外解包副本和字节码缓存已清理。
- 本轮通过完整核心 + AetherEngine 的指定 SDR 样本起播、暂停/恢复/跳转、进度保存与新进程/全新数据根续播。颜色和声音的人工正常反馈来自此前独立 AetherEngine 探针，本轮未追加人工画质/声音验收；原生层 ready 与轨道枚举不替代这部分观察。
- 本轮未重测字幕；外部 SRT 的显示与关闭证据见独立探针记录。内嵌字幕、复杂 ASS/PGS、HDR/DV、可选对象音频、长期稳定性及不同服务端兼容性均未验证。
- 没有修改产品源码或生成绑定，没有正式 tvOS 媒体库/播放 UI，没有生成正式安装包或提交。结果证明这一有限场景的技术闭环，不是完整平台支持。
