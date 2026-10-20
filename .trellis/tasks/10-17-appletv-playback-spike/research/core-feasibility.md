# Apple TV 核心接入初查

仅依据当前工作树静态检查，未执行编译或设备测试。本文不是 tvOS 支持声明。

## 可复用基础

- `core/ffi/main.go:88` 起导出 C ABI：版本、初始化、异步命令、事件、取消、释放和关闭。Swift 可以围绕 C 接口设计桥接，但库的目标构建和加载仍需实际验证。
- `core/ffi/main.go:229` 的事件读取会阻塞，返回字符串要求由 `lp_free` 释放，不能在 UI 线程直接持续读取。
- `core/player/transport.go:53` 明确 `engine="exo"` 表示调用方解码；其它字符串走 mpv。当前没有 Apple 播放器引擎名，不能随意传入新名字假设可以绕过 mpv。
- `core/player/playback.go:205` 和 `:230` 仅在 `useMpv` 时初始化并加载 mpv；返回值包含播放地址、续播位置、外挂字幕和播放会话信息。
- `core/player/transport.go:85` 提供 `emby.reportProgress`，`:67` 提供 `player.stopPlayback`，可作为宿主上报的接入基础。

## 已发现的限制

- `core/player/player.go:29` 的 cgo 块无 tvOS 独立实现，声明并调用 mpv 函数。运行时选择宿主播放并不会自动移除这些编译和链接依赖。
- `core/player/surface_other.go:1` 使用 `!android`；`SetSurface` 对非零 kind 只记录不适用日志并返回 0。虽然 `core/ffi/main.go:284` 的注释列出 `CAMetalLayer`，当前实现不能据此认定 Metal 输出可用。
- `core/player/transport.go:125` 的 `player.seek` 直接发 mpv 命令。若选择宿主播放器，拖动、暂停和轨道操作必须由宿主对应实现处理，不能照搬这条命令验证宿主播放器。
- `scripts/build-core.sh:21` 只按 GOOS 选择库名，`:47` 使用 `-buildmode=c-shared`；Darwin 分支不是 tvOS 编译支持的证据。
- `core/go.mod:7` 指定 Go 1.27，实际包含第三方依赖。文件开头“零第三方依赖”的旧注释不能作为移植判断依据。

## 后续验证顺序

1. 确认 Mac、Xcode 和真机可用条件，核查目标版本的 Go / tvOS 支持及 libmpv 构建依据。
2. 先验证最小 Go C ABI 库的 tvOS 链接、启动和返回值，再验证项目核心及其依赖；区分运行时问题和项目依赖问题。
3. 优先调查 libmpv 能否满足最小播放目标。宿主播放器是候选路径，尚未作出选择；不能用其有限格式支持替代未经确认的兼容性要求。
4. 核心初始化通过后才接入 Emby 播放闭环，验证首帧、遥控器操作、字幕、进度和停止上报。

本次代码初查未修改产品代码、未启动服务、未产生安装包。后续远程环境检查及 Go 工具安装结果见 remote-environment.md；项目 tvOS 构建、签名安装和媒体兼容性仍未验证。
