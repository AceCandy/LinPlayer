# tvOS 最小 Go C ABI 编译与链接实验

## 目的与边界

在当前 macOS 14.1.1 / Xcode 15.4 上验证 Go 1.27.2 能否通过 tvOS SDK 产出可被 tvOS 原生宿主链接的 C 接口。此实验不含项目核心、Emby、libmpv、应用签名或真机执行。

## 输入

临时 Go 文件：

```go
package main

import "C"

//export LPProbeAdd
func LPProbeAdd(a, b C.int) C.int { return a + b }

func main() {}
```

临时 C 宿主：

```c
#include "probe.h"
int main(void) { return LPProbeAdd(20, 22) == 42 ? 0 : 1; }
```

- `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`。
- `GOOS=ios GOARCH=arm64 CGO_ENABLED=1 GOTOOLCHAIN=local GO111MODULE=off`。
- CC 包装器调用 Xcode clang，附加 `-target arm64-apple-tvos17.0 -isysroot <xcrun --sdk appletvos --show-sdk-path>`，保留所有传入参数。
- Go 调用：`go build -buildmode=c-archive -o probe.a main.go`。
- C 链接：`clang -target arm64-apple-tvos17.0 -isysroot <tvOS SDK> host.c probe.a -framework CoreFoundation -o probe`。

## 结果与复核

1. 最小 c-archive 构建返回 0，生成 archive 和 C 头文件。
2. 初次 C 宿主链接未添加 CoreFoundation，失败符号均为 `CFBundle*` / `CFString*` / `CFURL*` / `CFRelease`。据此补充真实需要的 `-framework CoreFoundation` 后，链接返回 0。
3. `xcrun vtool -show-build probe` 返回 `LC_BUILD_VERSION`，`platform TVOS`、`minos 17.0`、`sdk 17.5`、链接器版本 1053.12；不是仅凭构建文件名判断平台。
4. 符号表可读取。宿主内的加法判断尚未在设备上执行，不能宣称运行结果为 42。
5. 所有源文件、包装器、GOCACHE、静态库和可执行文件都在独立临时目录内，实验结束后清理；未改项目代码或全局工具链配置。

## 结论及下一步

当前 macOS / Xcode 可以完成最小 Go C ABI 到 tvOS 可执行文件的编译和链接，现阶段没有据此要求升级系统的理由。但 `GOOS=ios` 复用路径仍需 Apple TV 真机验证运行时、初始化、线程、内存和退出行为；完整核心及播放器的依赖兼容性也未验证。

下一步解决已配对设备的开发 tunnel 和签名，再把最小探针接入 tvOS 应用外壳验证真机启动与返回值。成功后才扩大到项目核心及播放链路。

## tvOS 17.5 模拟器实际执行

- 后续环境检查发现用户已安装 tvOS 17.5 模拟器运行时，可直接用于独立验证，无需下载。
- 沿用上述最小 Go 导出函数，将 CC 与 C 链接目标改为 `arm64-apple-tvos17.0-simulator`，SDK 改为 appletvsimulator，保留 CoreFoundation 链接。
- 宿主将返回值输出为 `GO_TVOS_PROBE=<value>`，返回值不等于 42 时以非零状态退出。
- 构建完成后，`vtool -show-build` 确认 TVOSSIMULATOR / minos 17.0 / sdk 17.5。
- 启动一个原先关闭的 tvOS 17.5 模拟器，等待 bootstatus 完成，然后通过 `simctl spawn` 执行探针。输出 `GO_TVOS_PROBE=42`，进程返回 0。
- 验证后已关闭本次启动的模拟器，删除临时源码、GOCACHE 和构建产物。既有模拟器设备及运行时保留。
- 这只验证模拟器上的 Go 运行时启动和一次 C→Go 调用，未验证 UIKit 应用生命周期、线程压力、完整核心、播放或真机执行。

## tvOS 27 真机应用实际执行

### 条件与方法

- 宿主系统 macOS 14.8.9，Xcode 16.2（16C5032a），tvOS 18.2 SDK，Go 1.27.2；设备为 Apple TV 4K 第三代、tvOS 27.0。
- 沿用本文的 LPProbeAdd 导出函数、GOOS=ios / arm64 / c-archive 路径，clang target 为 arm64-apple-tvos17.0；使用新版 DEVELOPER_DIR 与 SDK。Go 缓存位于实验临时目录。
- 临时 Objective-C / UIKit 应用通过生成的 probe.h 调用 LPProbeAdd(20, 22)，将返回值写入 stdout 并设置 UILabel 文本。四秒后主动退出，结果不为 42 时退出码为 1。这只是诊断程序，不是产品生命周期设计。
- 临时 Xcode 工程以 appletvos SDK、TVOS_DEPLOYMENT_TARGET=17.0、TARGETED_DEVICE_FAMILY=3、Automatic signing 构建；链接 probe.a、UIKit、CoreFoundation。开发团队仅从匹配有效签名身份的公有证书中读取，实际值不写入源码或记录。
- 使用 xcdevice 当前 available 真机的 identifier 作为构建 destination，不使用重新配对前的标识。构建命令形态：

```sh
xcodebuild -project <temporary-project> -scheme Probe -configuration Debug \
  -sdk appletvos -destination 'platform=tvOS,id=<current-device-id>' \
  -derivedDataPath <temporary-derived-data> \
  -allowProvisioningUpdates -allowProvisioningDeviceRegistration build
codesign --verify --strict <temporary-app>
xcrun devicectl device install app --device <current-device-id> <temporary-app>
xcrun devicectl device process launch --device <current-device-id> \
  --console --terminate-existing com.linplayer.tvosprobe
```

### 已验证结果

- go_archive_exit=0，signed_app_build_exit=0，app_signature_exit=0。
- install_exit=0，launch_exit=0；控制台精确读取到一次 GO_TVOS_PROBE=42，断言通过。
- Apple 自动生成 tvOS 开发描述文件，包含 1 台注册设备并允许调试。账号、证书身份、设备标识与描述文件内容不复制到仓库。
- 实验后卸载 com.linplayer.tvosprobe，返回 0；临时源、工程、库、签名产物与 Go 缓存清理完成，残留探针目录为 0，启动中的模拟器为 0。Xcode 管理的用户描述文件保留用于后续开发。

### 限制

此结果证明最小 Go 运行时能够在 tvOS UIKit 应用内启动并完成一次原生→Go 调用，不是完整 LinPlayer 核心 ABI 验收。没有验证核心初始化、异步命令与事件、内存释放、压力与长期运行、Emby、播放器、音视频或 UI 实际渲染。界面设置了文本，但本轮没有以截图或人工观察验证其显示。
