# 实施与验证

1. 更新真实HTTP回归，证明 warm cancel/play/replace 期间有效查询仍返回、取消页面仍终止，先在旧实现上观察失败。
2. 分开 return_media 查询 ctx 与 warmCtx；保持旧预热取消契约。
3. 手机冷详情先呈现正文，更新挂起媒体查询回归；桌面媒体查询接入页面取消/恢复。
4. 定向 Go 测试（含 race）、Compose 详情回归；按环境运行 vet 和相关静态检查，不出包。
5. 独立只读审查 diff 与取消边界，修正遗漏；同步规范，报告验证限制。

## 实施结果
- 已分离页面媒体查询 ctx 与视频 warmCtx；停止、起播、替换预热不打断版本查询，页面取消仍生效。
- 非剧集冷详情直接显示元数据，版本区域异步更新；剧集季集原有首批呈现与完整缓存发布条件保持不变。
- 桌面媒体查询传入页面 token；离页取消，返回时恢复未完成的查询；已显示选项不重置，迟到回调检查捕获的旧 token。

## 验证记录
- 新 TestDetailMediaWarmCancellationKeepsQuery 在修复前 cancel/play/replace 三场景均因 context canceled 失败，修复后全部通过。
- 手机挂起媒体查询回归在修复前因正文不存在失败；修复后 PhoneDetailCacheTest 37、PhoneDetailOptionsTest 24、SeasonSelectionTest 36 共97项通过。
- go test -race ./player、./net/preload、./net/prefetch 通过；go vet ./player ./emby ./net/preload ./net/prefetch 通过。首次 player 全包因未加载测试 libmpv 失败，补上本地库路径后通过。
- 桌面无还原编译通过，保留既有依赖/空值告警。风格七项、Android参数259处、diff空白检查通过。
- 核心与UI改动分别经独立只读审查。关于旧桌面响应可能覆盖新选项的意见已复核：LoadMedia与Dispatcher回调均检查捕获token；Detach取消该token，Attach只替换字段，不能让旧token恢复，故该具体覆盖路径不成立。返回已显示选项时不重新应用默认选择。
- 已停止本次启动的Gradle daemon。未打包、未修改服务端；未执行包含出库的整套check-core门禁、真机首帧/导航/服务端联调，线上版本尚未改变。

## 提交记录
- 修复提交：aded3a41。暂存的7个文件通过敏感信息检查。
