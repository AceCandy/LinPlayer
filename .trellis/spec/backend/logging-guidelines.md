# 日志与脱敏

- 核心统一使用 `bus.Logf(level, format, args...)`，发送 log 事件及 `{level,msg}`；日志是可丢事件，不得为日志阻塞播放。
- 记录失败原因、操作阶段和非敏感状态；不记录真实服务器地址、资源路径、账号、token 或凭据参数。
- 离机报告复用 `core/system/report.go` 的脱敏入口，**先脱敏再截断**；不能只抹正文而放过 JSON 字段、用户目录或 URL。
- 不新增上传原日志的旁路；宿主日志也遵守 [通用隐私规则](../shared/development.md)。

依据：[日志分派](../../../core/bus/bus.go)、[报告脱敏](../../../core/system/report.go)、[脱敏测试](../../../core/system/report_test.go)。更改脱敏行为需对共享语料及凭据、路径、URL 场景做回归。
