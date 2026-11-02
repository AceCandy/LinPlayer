# tvOS 下一集预热与换集无黑屏衔接

## 目标
- [x] 下一集预热：按 Android `nextPreloadAllowed` 串行发送 `player.warmPrepared`，并 `AetherEngine.prewarm` 预读 `prepareNext` 返回的代理地址；放弃时取消并丢弃预读。
- [x] 换集无黑屏：`switchPlayer` 以 `close(handOver: true)` 保留引擎并 `prepareForItemReplacement()`，新控制器接管；失败重试不接管，未接管时 `releaseEngine()`。
- [x] 沿用引擎的旧状态隔离：加载期间忽略上一集 `.ended`、时钟与字幕，退出不误报进度。
- 视频倍速上限 2×（引擎限制）本次不改。

## 验证
仅静态检查（speed-controls、gen-bindings --check、git diff --check）与独立复核；Swift 类型检查、截图比对与真机待 Mac 验证。
