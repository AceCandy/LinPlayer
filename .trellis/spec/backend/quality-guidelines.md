# 核心质量检查

## 编码与生命周期

- Go 修改按现有包风格并 gofmt，不增加未请求的抽象或静默回退。
- handler 的 context.Context 沿调用链传递，失效请求不得继续覆盖当前状态。
- 图片通道：Emby 登录/重登使用 `AllowEmbyDefault` 转发尺寸；第三方图床使用 `AllowDefault` 保留签名 query。同 URL、同请求头合并回源，单等待者取消不影响其他等待者，全部离开或服务关闭才取消；排队取得名额后复查缓存。
- 播放问题先核查 libmpv 配置、日志与时序；不复制播放器已经提供的能力。
- 修共享函数前核查全部调用者，包括不同宿主与入口。

## FFI / 绑定

- C 字符串入参立即复制；lp_next_event 返回的 C 字符串由宿主 lp_free 释放。
- 事件泵只允许单消费者，不在页面另启动 lp_next_event 消费者。
- `core/ffi/abi.go` 的 LP_ABI 是唯一真值；导出签名、事件信封、错误形状或 surface 语义的破坏性变化才递增。
- 生成器读取 ABI 与命令表，生成两端绑定；命令名是字符串，必须跑四方对账。

## 门禁与证据

- 核心变更：`bash scripts/check-core.sh`，包含 go vet、无缓存 go test、出库、FFI 头文件、C# 核心契约与差分对账。
- 命令表或绑定变更：`bash scripts/check-bindings.sh`。
- Emby 相关改动核查 `core/cmd/diffcheck/corpus/` 的既有差分语料及相关测试；真实 API 联调按 [基准](../shared/product-and-api.md) 执行。
- 回归先证实原故障会失败，再验证修复；未执行的设备播放、服务端联调必须说明。

依据：[ABI](../../../core/ffi/abi.go)、[FFI](../../../core/ffi/main.go)、[核心门禁](../../../scripts/check-core.sh)、[绑定门禁](../../../scripts/check-bindings.sh)。
