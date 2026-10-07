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

- 聚合总览每服的统计与继续观看共用单服总时限（现有20秒），不能只依赖连接/响应头/响应体空闲超时；持续有数据的慢请求也须受总时限约束。单项失败保留另一项及健康服结果；继续观看超时返回可见错误。回归使用真实HTTP挂起请求，不仅给父ctx加短deadline（后者无法验证handler自身是否设置超时）。

- mpv起播字幕在 SubLang/SubRegex 均为空时优先明确简体标记，与Android `preferredTrackIndex` 一致；显式正则/语言及关闭字幕始终优先，不把chi/zho推断成简体。外挂字幕沿用FILE_LOADED时序；`Test字幕默认简体不覆盖显式偏好` 覆盖默认、正则、语言和关闭。

## 场景：详情预热取消与正式起播

### 1. 范围 / 触发
离开详情、新详情预热、正式起播和停播必须取消旧预热，包括尚未完成的 PlaybackInfo；防止晚到请求抢带宽或替换正式播放代理。

### 2. 签名
`prefs.preloadItem`、`prefs.preloadCancel`、`Play` / `PlayResolve`、`Stop`；内部 `cancelWarm()` 与 `proxyFor(ctx, upstreamURL, prefs, readAhead)`。

### 3. 契约
命令字段与响应不变，无新增环境变量。预热拥有独立可取消 context，不能使用命令返回即失效的请求 context。取消覆盖取流、代理初始化、字节预热；共享代理和已有缓存保留，正式播放仍独立解析 PlaybackInfo。

### 4. 校验 / 错误
缺 item_id → EInvalid；预热关闭 → skipped；取消后的取流错误不记失败日志、不发 preload.done。代理初始化失败仍沿用直连回退。

### 5. 正常 / 基础 / 错误案例
正常：同流起播复用代理和缓存；基础：转码继续直连；错误：旧取流在取消后返回，不得创建或发布替代代理。

### 6. 必要测试
真实 HTTP 挂起 PlaybackInfo，分别从离页、正式起播、新详情取消，放行旧响应后断言旧流请求为零、正式代理未替换、缓存文件未删。C27 验证取消后句柄复用；运行 player / preload / prefetch 竞态检查。模拟测试不能证明真机首帧收益。

### 7. 错误与正确做法
错误：只 `preloader.Cancel()`，晚到的 `Warm()` 会重置取消标志。
正确：取流传入独立 warmCtx，关键阶段复查 `ctx.Err()`；取消后等待在途代理创建退出，正式起播再继续。禁止为取消预热而关闭已发布的共享代理。
