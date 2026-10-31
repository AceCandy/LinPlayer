# 复用详情页 PlaybackInfo 加速起播

## Goal

详情页 return_media 取到的 PlaybackInfo 留给紧接着的正式起播一次性复用，少一次服务器往返

## Requirements

- 用户 2026-10-31 同意修改“正式播放仍重新获取 PlaybackInfo”的规定。
- 只复用一次（PlaySessionId 贯穿一次播放）；同 session、item、分级过滤，两分钟内有效；取消预热保留已完成响应，但使在途查询失效。
- 流影起播不复用；未命中照旧 ResolveStream。

## Acceptance Criteria

- [x] 真实 PlayResolve 入口首开复用、停止后再次打开重新请求 PlaybackInfo。
- [x] 查询乱序、预热开关两种状态下的取消/起播/替换均不能让旧响应重新填缓存。
- [x] 缓存缺少新版本时重新取流；私密分类每次正式命令仍验证能力。
- [x] 账号、条目、分级不同或过期时不复用。
- [x] 起播日志 phase=startup_resolve 带 reused。
- [x] 规范 quality-guidelines.md 同步。

## Validation

- 新增回归先复现缺失版本失败、私密校验绕过及迟到响应覆盖，修正后通过。
- go vet ./player ./emby 与 go test -race -count=1 ./player ./emby 通过。
- 未进行 Hills 同条件真机首帧对比，不能据此承诺等速；后续由 android-playback-device-validation 跟踪。
