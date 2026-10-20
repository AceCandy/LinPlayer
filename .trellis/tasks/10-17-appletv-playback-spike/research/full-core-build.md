# 完整核心 tvOS 编译与宿主链接探针

## 环境与输入

- macOS 14.8.9、Xcode 16.2、tvOS 18.2 SDK、Go 1.27.2。
- 当前工作树的 `core/ffi`，包含 Go 源码、C 源码和 `go:embed` 资源；未修改产品代码。
- `GOOS=ios GOARCH=arm64 CGO_ENABLED=1 GOTOOLCHAIN=local`，CC 包装器指定 `arm64-apple-tvos17.0` 与 appletvos SDK。
- `go build -mod=readonly -buildmode=c-archive -o <temporary>/core.a ./ffi`。
- Mac 直接访问 Go 模块代理出现 TLS handshake timeout。改为开发机下载模块，以模块校验和核验，再传输临时下载缓存；Mac 使用 `GOPROXY=off`。原始源码打包曾漏带嵌入资源，补齐后重试；该输入错误不能归因于 tvOS。

## 实测结果

- 完整静态库构建两次均返回 0；生成 C 头文件和 archive。这证明编译闭包通过，不证明宿主最终链接或真机运行通过。
- 临时 C 宿主包含生成的头文件并调用 `lp_abi_version`，以相同 tvOS target 链接 archive，附加 CoreFoundation、Security、Foundation、resolv。
- 宿主链接返回 1，缺失 17 个 mpv 外部符号：创建/初始化/销毁、命令、属性、日志、事件、时间及 render-context API。错误中没有其它未解析符号。
- 本轮未提供 libmpv，因此此结果确认当前完整核心无法仅靠 Go archive 与系统框架形成应用；不是 tvOS 版 libmpv 本身不兼容的证据。
- 两端临时源码、模块副本、编译缓存与产物均由临时目录上下文清理；未安装应用、未启动模拟器或服务。

## 服务端前置检查

- 用户提供测试服务和普通账号。实际地址与凭据不写入本文。
- 开发机与远程 Mac 的公开信息接口返回 200，自报 Emby Server v4.8.10.0。
- 用户名/密码认证返回 200，响应包含有效 token 和用户标识；媒体库接口返回 200，得到 11 个库；最近更新接口返回 200。
- 递归影片/分集查询在 15 秒内未返回；缩小查询后成功。最近更新样本实际为 Series，不能视作已选定可播条目，也不能假定服务端严格执行类型参数。
- 测试会话均请求登出，返回 204。凭据和会话仅在进程内存使用，未输出 token、用户标识、片名或媒体地址。
- 以上为直接 HTTP 前置检查，未通过 Go 核心发起真实认证、播放解析或播放上报；没有验证真实播放、字幕、拖动和续播。

## 下一步

候选版本、实际 tvOS 切片和缺失符号已按下节核验。接下来验证完整依赖链接、渲染通道和真机执行；静态符号核验不能替代视频输出验证。

## libmpv 候选二进制核验

- 固定 MPVKit tag `1.1.0-n9.0.2`，从该 tag 的 `Package.swift` 读取 LGPL `Libmpv` 下载地址和 SHA-256，不以默认分支作为版本依据。
- 实际下载 release 资产，SHA-256 与 manifest 一致；`Info.plist` 包含 `tvos-arm64_arm64e`，支持 arm64、arm64e，平台为 tvos，无 simulator variant。
- 将该切片库临时传入 Mac，以 `xcrun nm -gU` 检查上述 17 个符号，返回 0，缺失符号为 0。此结果仅确认符号名存在，不确认 ABI、依赖闭包或运行时兼容性。
- `vtool -show-build` 对该库返回 1，未获得最低系统版本证据；不能把构建脚本中的 deployment target 当作资产的独立实测结果。
- 下载包与远程二进制均已清理，没有将候选库加入产品依赖。
- 候选 Package.swift 声明了 FFmpeg、字幕、字体、libplacebo、MoltenVK 等其它依赖。不能只链接单个 Libmpv 切片就宣称完整链接可用。
- 外部源码调查发现 tvOS 示例使用 CAMetalLayer、gpu-next、Vulkan/MoltenVK；当前核心是 OpenGL render-context，尚未选择接入方式。默认分支示例仅为调查线索，实施前必须核对固定 tag 下的对应源码。

来源：

- https://github.com/mpvkit/MPVKit/releases/tag/1.1.0-n9.0.2
- https://github.com/mpvkit/MPVKit/blob/1.1.0-n9.0.2/Package.swift
- https://github.com/mpvkit/MPVKit/blob/1.1.0-n9.0.2/Sources/BuildScripts/XCFrameworkBuild/main.swift

## 完整依赖与真机安装

- 按固定 tag 的 Package.swift 取得 28 个非 GPL 变体、非 macOS 专属依赖，逐个核验 SHA-256，并提取 tvOS arm64 真机切片；未将二进制加入仓库或产品依赖。
- 完整核心与这些依赖进行原生链接，首次缺少 CoreGraphics、CoreText、IOSurface 符号；补充对应系统框架后，临时 UIKit / GLKit 宿主完整构建和开发签名均返回 0，严格签名校验返回 0。
- 完整探针两次成功安装到真实 Apple TV，安装返回 0。启动均返回 1，未出现探针执行标志；第二次取得明确系统诊断：`System is asleep - foreground app launch forbidden`。因此本轮尚未执行核心初始化或 GL 初始化，不能归因于 ABI、GL 或播放器运行时。
- 每次启动尝试后均卸载临时应用，返回 0；远程临时源码、完整库与构建缓存已清理。没有启动模拟器。
- 已请用户用遥控器唤醒设备后继续。待验证：完整 ABI 初始化/事件释放、OpenGL ES render-context、通过核心登录/选片/取流、真实画面和声音、暂停/拖动/字幕/上报及续播。
- 临时宿主使用单一后台事件消费者，GL ABI 仅在主线程、当前 EAGL 上下文调用；探针已做独立只读审查，明确 GLKit 绘制与 swap 上报仅是待实测的临时接入，未将其视作最终平台设计。

## 唤醒后的完整核心执行

- 用户唤醒设备后，应用成功进入 UIApplication 生命周期。实测 `lp_abi_version=1`、`lp_init=0`；EAGL OpenGL ES 3 上下文创建与 current 成功，`lp_gl_init=0`。
- 单一后台事件消费者通过 ABI 发出登录、最近更新、季列表及分集命令，均取得成功结果。这是通过完整 Go 核心在真机执行，区别于此前直接 HTTP 检查。
- 临时宿主把 `emby.seasonEpisodes` 的分页对象误当数组迭代，触发 Objective-C 异常，未进入播放。实际返回为 `Page{items,total}`（`core/emby/detail.go` 的 SeasonEpisodes、`core/emby/emby.go` 的 Page）；这属于探针错误，不是核心或 tvOS 不兼容。
- 修正方法是按真实磁盘上的 handler 返回类型及 JSON 标签提取 `items`。后续每个桥接命令都应独立核对具体返回结构，不能依据相邻列表命令或压缩研究结论推断数组/分页形状。
- 该轮临时应用已卸载；因异常未执行正常登出与核心关停，不能把它计入完整生命周期/内存释放验收。卸载删除设备上的探针沙盒，但不能据此证明服务端令牌已撤销。

## 首轮真实播放闭环

- 修正分页提取后，完整应用构建、签名、安装、启动、正常退出、卸载均返回 0；完成标志存在。
- 通过 Go C ABI 登录、读取最近更新、季与分集并选择可播条目，再以核心默认 mpv 路径调用 `player.play`，命令成功，收到一次 `player.fileLoaded`。
- 起播后位置 11.633 秒；暂停状态为真，两秒采样位置漂移 0.000 秒；恢复后位置 14.800 秒且暂停状态为假；跳转目标为 30 秒，五秒后位置 34.867 秒。
- GL render 成功调用 2020 次，中心区域像素采样中 16 次检测到非黑内容。这是渲染数据证据，仍须区分用户可见画质及人工声音确认。
- 样本有一个视频轨、一个音频轨、无字幕轨；未把该样本计为字幕切换验收。
- `emby.reportProgress` 返回 `reported=true`，`player.stopPlayback` 返回 `reported=true`。服务端是否保存及重新打开的续播另做后续检查。
- 正常执行 `lp_gl_uninit` 后 `lp_shutdown`；C 字符串计数为分配 205、释放 205。事件中的 error 级日志计数为 0。这不是长期内存/线程压力测试。
- `emby.logout` 命令成功，但该轮没有额外输出 `server_ok` 字段，不能只据命令成功证明服务端登出成功；下一轮应明确检查此字段。

## 字幕切换与首个续播检查

- 通过 `player.addSubtitle` 的 text/format 接口挂载临时 SRT，再由 `player.setTrack` 切换；读取真实轨道表，开启后选中字幕数为 1，关闭后为 0。字幕文本、缓存仅留在临时探针沙盒，应用随后卸载。此处是轨道状态验收，不等于人工确认可见字幕字形或复杂 ASS 效果。
- 该轮正常完成两次起播，`hwdec-current` 为 `videotoolbox`；GL render 2558 次、非黑采样 21 次，事件 C 字符串分配/释放均为 306，error 日志计数为 0，严格签名/安装/运行/卸载均返回 0。
- 显式进度与停止上报均成功，但停止位置 33.900 秒后，`emby.itemDetail.resume_secs=0`，重新打开回执续播位置为 0、五秒后播放位置约 4.900 秒。因此该样本续播失败，不能将 HTTP 成功计为持久化通过。
- 独立 HTTP 复核同一选片路径：条目和媒体源 `RunTimeTicks` 均为 0，UserData 的位置与播放次数也为 0。额外诊断会话登出返回 204。后续读取各库少量最近更新，发现另有明确时长的电影候选，准备在超过记录门槛的位置复验。
- `emby.logout.server_ok=true`，探针正常关闭核心并卸载。该轮明确检查了服务端登出结果。

## 服务端基准对照

- 本轮只读核查 MediaStationGo HEAD `ce30e2a5fd2e8f90de4774b68a54a539f80e34b3`，已有其它任务对规范、hongguo_series、library_metadata_repository 的修改及未跟踪 library_listing_plan_test；未修改这些文件。不能假定部署实例与这个 HEAD 完全一致。
- 实际公开信息中的服务名与 MediaStationGo 相符；v4.8.10.0 是兼容接口版本，不能据此断定部署的是原版 Emby。
- 基准 `internal/service/emby_user_data.go` 的 RecordProgress 在请求/探测均无正时长时直接返回 nil；`internal/service/playback.go` 的 shouldRecordPlaybackProgress 对长于十分钟的内容要求位置至少 60 秒，其余至少 20 秒。不满足条件时上报可返回成功但不落进度。
- 三个 Sessions/Playing 路由都调用 RecordProgress，非纯 stub；详情 UserData 与写入链路使用 legacy playback_histories。此为源码线索，不能替代部署实例的真实读回验证。

## 有明确时长的电影：续播闭环通过

- 通过核心 `emby.views` 与逐库少量 `emby.listLatest`，选择声明时长 4800 秒的 Movie；mpv 实际解析时长 4911.082 秒。未记录片名、用户标识、媒体标识或地址。
- 同一完整核心和 OpenGL ES 宿主起播成功，11.411 秒后仍正常播放；暂停两秒位置漂移 0.000 秒，恢复后位置 14.615 秒；跳到 30 秒后约五秒读到 34.868 秒。
- 样本有一个视频轨、两个音频轨，无原生字幕轨。临时 SRT 挂载成功，真实轨道表开启后选中数为 1、关闭后为 0；复杂 ASS/PGS、原生字幕及用户实际看见字形均未验收。
- 跳转到 120 秒后，上报和停止均 `reported=true`；停止位置 123.924 秒，核心重新请求详情读到服务端 `resume_secs=123.923`。重新调用 `player.play` 未传位置，回执采用 123.924 秒，随后实际播放位置 126.894 秒；VideoToolbox 硬解生效。
- 服务端读回确认了进度已保存；此次重新打开仍在同一应用进程内，不能替代杀进程/重装后的冷启动续播验证，也不能唯一断定核心最终续播位置来自服务端而非本地历史。
- 两次 fileLoaded、2429 次成功 render、20 次非黑像素采样；C 字符串分配与释放均为 300，error 日志计数为 0。核心正常关停，服务端登出 `server_ok=true`。
- 构建、签名、严格验签、安装、启动、正常退出、卸载均返回 0。未改产品代码，未生成或保留正式安装包。
- 用户尚未反馈实际画面、声音和字幕观察；自动证据不替代这部分人工验收。未知时长/短位置的分集样本仍保留为未通过续播的场景，不能将正常电影结果推广至所有内容。
