# 远程构建环境检查与工具安装

当前结论：macOS 14.8.9 / Xcode 16.2 的 Apple TV 开发连接、描述文件、签名安装与最小 Go 真机执行均通过。以下保留各阶段排查历史；最终运行证据见 tvos-abi-probe.md。完整核心与播放尚未验证。

## 已验证

- SSH 非交互登录成功；设备为 Apple M1 MacBook Air，macOS 14.1.1。
- 初查时仅有 Command Line Tools。用户下载解压后，已确认 Xcode 15.4（15F31d），将其从下载目录移至 `/Applications/Xcode.app` 并通过 `open` 请求启动。目标目录原先不存在，未覆盖其它版本。
- 用户完成首次启动后，`xcodebuild -checkFirstLaunchStatus` 返回 0，`-showsdks` 列出 tvOS / tvOS Simulator 17.5，Apple clang 15.0.0 可调用。全局开发目录仍是 Command Line Tools，使用 DEVELOPER_DIR 指向 `/Applications/Xcode.app/Contents/Developer`。
- 初次 `devicectl list devices` 未发现设备，`xcdevice list --timeout 8` 仅列出 Mac。之后修正目标电视的代理绕过规则、输入用户提供的 PIN 后，命令返回 0，并显示 available (paired)。配对码不保存到文件。
- 使用 Mac 内置 `dns-sd` 查询 AirPlay Bonjour 服务，读到 Apple TV 的 `model=AppleTV14,1`、`osvers=27.0`。这是设备广播信息，不是开发配对或运行验收证据。未保留广播中的设备名称、网络地址或标识符。
- 已安装官方 Go 1.27.2 Darwin arm64 到 `~/Developer/LinPlayer/.toolchain/go1.27.2`，使用绝对路径调用，不修改全局 PATH。
- 下载元数据取自 https://go.dev/dl/?mode=json ，SHA-256 为 `76812b213b1b2302c978d28fa52fa92d541704b9e7d9d5db8002c50e4018c4c5`，开发机与 Mac 两侧均校验通过。
- `go version` 返回 `go version go1.27.2 darwin/arm64`；`go tool dist list` 的 Apple 目标只有 darwin/amd64、darwin/arm64、ios/amd64、ios/arm64，没有独立 tvos 目标。

## 工具链选择与阻塞

- Apple 官方支持表 《Xcode Support》（Apple Developer，路径 `/support/xcode/`） 列出：Xcode 27 / 27.1 包含 tvOS 27 SDK，要求 macOS Tahoe 26.6 或更高。这仅是采用该工具版本的系统要求，不是当前播放验证必须升级系统的证据。
- 同表列出 Xcode 15.4 支持 macOS 14.x、包含 tvOS 17.5 SDK，Device Support 列为 tvOS 12 or later。先在当前系统使用 Xcode 15.4 验证；该宽泛支持范围不能替代 tvOS 27 真机的配对、签名安装及调试实测。
- Homebrew 当前 xcodes 2.1.0 元数据要求 macOS 15，因此没有在当前系统强行安装该版本。未安装非必要辅助工具。
- `sudo -n true` 未获得免交互管理员权限；系统升级和可能需要的管理员认证不能假设可通过 SSH 自动完成。
- 保留当前系统，不再将系统升级列为既定前置条件。Xcode 首次初始化及设备配对已完成。用户已创建 Apple Development 证书；当前可见 1 个有效签名身份，解锁登录钥匙串后 SSH 实际调用私钥签名通过，详见下文。未检查或保存账户凭据。
- 配对后的 `device info details` 能返回硬件与 tvOS 27.0，但提示不能获取完整信息；`device info ddiServices` 返回 `RemotePairingError 1005: This device does not support tunnel connections`，状态为 tunnel disconnected、ddiServicesAvailable false。原因尚未确定，不能将 paired 当作开发连接可用，也不能未经核实就要求升级系统。

## 工具链判断规则

此前把设备运行系统版本与构建 SDK 版本要求混同，过早提出必须升级系统。后续必须分别核查宿主 macOS、Xcode 所含 SDK、应用最低部署版本、设备安装与调试支持、所用 API 和依赖要求。优先验证兼容当前系统的工具链，只有具体要求或实测失败能证明必要时，才提出系统升级。

## 网络与清理

- Mac 直连下载 Go 较慢，改由开发机下载并校验后，通过局域网传输；重复慢速下载已停止。
- Homebrew 元数据刷新和系统安装器列表查询长时间未完成，已停止本次启动的对应进程；未执行软件升级。
- Go 安装压缩包及临时解压目录已删除，开发机临时目录已自动清理；未启动常驻服务。
- SSH 私钥受口令保护，失去 agent 时即使服务端接受公钥也无法完成认证；用户在本地终端解锁到固定 agent socket 后已恢复连接。不要将认证问题误判成 Mac 未安装公钥，也不要索取私钥内容或口令。

## 未验证

已完成配对、最小 Go C ABI 编译链接、tvOS 17.5 模拟器执行，以及后续 tvOS 27 真机应用的 provisioning、签名、安装和 Go 导出函数执行，见 tvos-abi-probe.md。尚未执行完整项目构建、libmpv 适配或真实播放；最小探针通过不等于项目已支持 tvOS。

## 配对失败与代理修正

- 症状：设备已出现在 Xcode 的 Discovered 列表，配对约 10 秒后报 `com.apple.dt.CoreDeviceError` / `com.apple.dt.RemotePairingError`，错误码 -1。
- 证据：`remotepairingd` 日志显示 Bonjour 解析成功，但后续连接被转到本机 SOCKS 代理；发送首条握手消息后约 10 秒连接关闭。无需开启私密日志，即可识别代理路径。
- 从 Mac 对设备广播的配对端口直接建立 TCP 连接成功，耗时约 0.14 秒。配对服务广播包含版本范围；不能把其中协议字段直接当作设备系统版本。
- `networksetup -getproxybypassdomains` 显示仅一个例外项，内容却是一整串逗号分隔的域名及地址模式。显示上包含局域网模式不等于系统将其作为独立模式执行。
- 最小修正：保持原例外项不变，追加电视实际 `.local` 主机名作为独立例外项；读回确认精确主机项存在。未关闭全局代理，也未改写其它站点规则。
- 修正后同一 Xcode 的 `devicectl manage pair --device <device-id>` 进入 `Enter pairing PIN`，输入后成功配对，确认此前握手阻塞已解除；开发 tunnel 与安装仍待验证。第一次短时限探测超时后已使用较长交互时限重试，不把等待用户输入的超时误判为网络失败。
- 排查规则：SSH 短暂不可达时先重试并检查局域网邻居 / 服务发现，本次重试后原地址恢复，无需用户重新查 IP。Xcode unknown error 先定位发现、TCP、代理、握手、PIN 的具体阶段，不直接归因为工具版本。
- 不将真实主机名、地址、设备标识、配对码、代理站点列表或原始日志写入仓库。若代理客户端重新覆盖系统例外项，应回查精确主机项是否仍存在，再定位该客户端配置来源。

## 开发证书与私钥签名检查

- 用户创建证书后，`security find-identity -p codesigning` 显示 1 个 matching identity，但 `-v` 显示 0 个有效身份。证书类型为 Apple Development，颁发者为 WWDR G3。
- 搜索可见 WWDR 中间证书时，仅找到已于 2023 年到期的旧版。官方证书页 《Apple PKI》（Apple 官方证书页，路径 `/certificateauthority/`） 提供 `/certificateauthority/AppleWWDRCAG3.cer`，G3 有效期至 2030 年。
- 下载官方 G3 后，使用系统根证书验证叶证书与 G3 的 codeSign 信任链，返回 0；以普通证书导入用户登录钥匙串，未设置信任覆盖、未添加自定义信任根、未删除旧证书。随后 `find-identity -v -p codesigning` 显示 1 个有效身份。
- 注意：导入前单独 `verify-cert -p codeSign -L` 也返回 0，所以不能把全部签名状态问题唯一归因于缺失 G3；需要区分证书链验证、identity 枚举与实际私钥访问。`verify-cert` 的策略名是 `codeSign`，与 `find-identity` 使用的 `codesigning` 不同。
- 对独立临时 C 可执行文件实际执行开发证书签名，`codesign` 返回 1，错误为 `errSecInternalComponent`；尚未证实是登录钥匙串锁定还是私钥访问控制，不能宣称自动签名已可用。
- 用户在 Mac 本地解锁登录钥匙串后，复测显示 1 个有效身份；临时 C 可执行文件的 `codesign` 和 `codesign --verify --strict` 均返回 0，确认 SSH 会话可以实际使用开发证书私钥。未批量改动私钥 ACL；临时证书导出、下载及签名探针均已清理。
- 上述验证仅覆盖签名身份和私钥访问，尚未验证 tvOS provisioning profile、应用安装或真机运行。

## 开发连接复测

- 签名通过后，`devicectl list devices` 仍返回 `No devices found`。
- 根据 pymobiledevice3 的 `service_connection.py` 中长度前缀与 plist 编码、`lockdown.py` 中 `QueryType` 请求格式，从 Mac 直接向设备服务端口发送只读查询，立即返回 `Connection reset by peer`，与 Xcode 旧设备连接日志一致。此探针不经过系统 HTTP/SOCKS 代理，未执行配对修改；不能据此单独判定是工具版本不兼容。
- Apple 官方说明 tvOS 没有 Developer Mode 开关，配对后出现开发者设置；不再要求用户寻找该开关。当前仍未建立可安装应用的开发连接。

## 重新配对及版本兼容性核查

- 用户从电视遗忘 Mac 后，旧记录的 unpair 返回 RemotePairingError 1005，pair 返回已经配对。重启当前用户的 CoreDeviceService 与 remotepairingd 后，用户重新完成配对。电视广播标识与 CoreDevice 标识均发生变化；后续必须从当前 Bonjour 广播重新解析标识，不能沿用旧标识得出设备不存在的结论。
- 用户退出 VPN 后，`scutil --proxy` 中 HTTP、HTTPS、SOCKS、PAC 的 Enable 均为 0。新标识对应的 details 返回 paired，但 tunnel disconnected、ddiServicesAvailable false；xcdevice 与 CoreDevice 可用设备列表均未列出真机。证书有效不代表开发连接有效。
- Xcode 最新日志出现 `AMDeviceStartSession failed: 0xe800000f (kAMDUnsupportedError)`。这比配对成功更接近实际开发会话失败阶段，但错误本身没有指明唯一根因。
- `devicectl list preferredDDI` 显示 tvOS DDI 与宿主 CoreDevice 355.28 的 contentIsCompatible、isUsable 均为 true。该检查是宿主组件间兼容性，不代表已向 tvOS 27 设备挂载或启用 DDI，不能套用网上“宿主 DDI 太旧”的案例直接删换镜像。
- Xcode 的 `DVTIgnoredDevices` 存在但为空；没有目标设备可移除。将 Show run destination 改为 Always 并触发 xcdevice enable 后仍无可用真机。不要仅凭 autoConnectStyle 日志就删除全部偏好设置。
- Apple 官方 Xcode 16 发布说明列出相关修复：配对时 Devices and Simulators 显示过时信息（123271553），再次配对后设备落入 Disconnected（131072881），以及 tvOS 17 及以后设备按需连接行为修复（115668388）。这些是升级旧工具链的依据，但尚不能证明它们是本机问题的精确根因。
- 官方 Xcode 16.2 发布说明要求 macOS Sonoma 14.5 或以后。支持表列出 Sonoma 14.5 至 Sequoia 15.x；因此若选择验证包含上述修复的工具链，可保留 Sonoma 大版本，先使用 14.5 或以后 14.x 配合 Xcode 16.2。当前 14.1.1 不满足这一组合的要求；本轮未升级系统或安装新 Xcode。
- 官方支持表仍将 Xcode 15.4 Device Support 写为 tvOS 12 or later，16.2 写为 tvOS 15 or later。不能把 SDK 17.5 或 18.2 当成真机系统上限；也没有找到能够保证 16.2 连接 tvOS 27 的精确版本声明或实测。若要求与 tvOS 27 同代 SDK，Xcode 27 要求 macOS Tahoe 26.6 或以后；该条件不等于本次验证必需。

来源：
- Apple Developer《Xcode 16 Release Notes》（文档路径 `/documentation/xcode-release-notes/xcode-16-release-notes`）
- Apple Developer《Xcode 16.2 Release Notes》（文档路径 `/documentation/xcode-release-notes/xcode-16_2-release-notes`）
- 《Xcode Support》（Apple Developer，路径 `/support/xcode/`）
- Stack Overflow《Trouble pairing Apple TV with Xcode》（问题编号 59584407） （社区案例，仅作线索）

## Sonoma 更新及 Xcode 16.2 并排安装

- 用户完成系统更新后，SSH 实测 `sw_vers -productVersion` 为 14.8.9。此前多次 SSH 超时或中断，恢复后重新检查磁盘，确认没有已解压的新版应用或残留 xip 进程，才重新执行解压。
- 使用系统 `xip --expand` 解压用户下载的 Xcode 16.2 压缩包。xip 提示未尝试验证压缩包签名，因此另对解压后的应用执行 `codesign --verify --strict`，返回 0；签名链为 Software Signing → Apple Code Signing Certification Authority → Apple Root CA。
- 将新版安装到 `/Applications/Xcode-16.2.app`，未覆盖 `/Applications/Xcode.app` 中的 15.4。使用新版 DEVELOPER_DIR 执行 `xcodebuild -version`，返回 Xcode 16.2、Build version 16C5032a。
- `-checkFirstLaunchStatus` 返回 69；`-runFirstLaunch` 返回 77，明确提示尚未同意许可，需要管理员方式完成首次启动。已通过 `open` 请求打开新版 GUI，等待用户本地确认许可及必要组件。尚未用新版验证真机连接，也未启动模拟器或构建产品代码。
- 用户原有下载压缩包保留；没有新建需要入仓的证书、设备标识、诊断日志或构建产物。

## Xcode 16.2 开发连接实测

- 用户完成首次启动后，`xcodebuild -checkFirstLaunchStatus` 返回 0；新版 `devicectl list devices` 返回 1 台设备，paired、tunnel connected。`xcdevice list` 列出 Apple TV 真机，available true、tvOS 27.0，无错误。
- 后续 details 显示 34 项 capability、ddiServicesAvailable true；`device info ddiServices` 返回 0，开发磁盘服务可用。宿主 CoreDevice 397.28，tvOS DDI 的 buildUpdate 为 16C5032a，contentIsCompatible 与 isUsable 均为 true。
- 这证明当前 tvOS 27 设备可以通过 Sonoma 14.8.9 / Xcode 16.2 建立开发连接，无需先升级到与设备同代 SDK。系统与工具链同时发生变化，不能把成功唯一归因于某一发布说明中的修复项。
- 临时最小 Go archive 已使用 tvOS 18.2 SDK 再次构建通过。第一次临时 Xcode 工程按 scheme + generic tvOS destination 构建，报“tvOS 18.2 未安装”；改为直接 target 构建则正常进入自动 provisioning。随后按实际 available 真机的 UDID 进行 scheme 构建，仍报“tvOS 18.2 must be installed to run the scheme”。因此前述 target 构建不能用来证明完整真机 scheme 流程不需要平台运行时。
- 直接 target 构建返回团队没有可用于生成 provisioning profile 的设备，以及未找到对应 tvOS App Development profile。尚未安装临时应用，不能把证书与开发连接通过当成签名安装成功。临时源、Go 缓存及工程均由独立 TemporaryDirectory 自动清理。
- 基于明确的 scheme 构建失败，已启动新版 `xcodebuild -downloadPlatform tvOS`，实际下载 tvOS 18.2 Simulator (22K154)，约 4.36 GB；这是补齐本机构建组件，不是用模拟器结果代替后续真机验收。
- 上述下载与导入最终返回 0。按当前 available Apple TV 的实际 UDID 执行 scheme 构建并附带 `-allowProvisioningUpdates -allowProvisioningDeviceRegistration` 后，Xcode 成功取得 `tvOS Team Provisioning Profile: com.linplayer.tvosprobe`，进入 ProcessProductPackaging 与最终 CodeSign。通用目标的“团队无设备”不代表账号必然不能为 tvOS 签名，需使用实际设备目标验证。
- 最终 CodeSign 返回 `errSecInternalComponent`，构建失败，未执行安装或启动。当前阻塞回到 SSH 会话的私钥访问；需用户在 Mac 本地解锁登录钥匙串后复测，不索取密码、不批量改私钥 ACL。所有本轮临时探针目录及构建缓存由 TemporaryDirectory 清理；Xcode 自己管理的描述文件保留在用户 Library 内，不复制入仓。

## 签名会话差异与最终通过

- 用户再次解锁后，强制 `ControlMaster=no / ControlPath=none` 的新 SSH 连接仍在应用 CodeSign 和独立宿主签名中返回 errSecInternalComponent；该连接的 `security show-keychain-info` 返回 36，securityd 记录解锁尝试与 CSSM 异常。
- 移除上述强制新连接选项、复用既有 SSH 连接后，同一个登录钥匙串查询返回 0、显示 no-timeout，同一有效身份的独立宿主签名返回 0。随后在复用连接下重新运行探针，Go archive、应用构建、严格验签、安装、控制台启动全部返回 0，输出 GO_TVOS_PROBE=42。
- 根因分类为隐含假设：把本地解锁和某一连接中成功使用私钥，误当成随后所有 SSH 会话都能访问私钥。网络排查时引入强制新连接选项后没有复核这个前提，导致反复解锁仍无效。对比同一身份、同一机器的实际签名才识别出连接上下文差异；本轮没有修改私钥 ACL 或信任覆盖。
- 后续规则：网络恢复后先检查是否仍强制新 SSH 连接；签名前在实际将执行构建的连接中运行钥匙串查询和独立签名预检。失败时区分会话访问、证书有效性、provisioning 和应用安装，不反复重配对，也不从 errSecInternalComponent 单独推断应批量修改 ACL。
- 最终临时应用卸载返回 0，探针临时目录残留为 0，启动中的模拟器为 0。旧 Xcode 和必要运行时保留；没有构建完整产品或验证播放。
