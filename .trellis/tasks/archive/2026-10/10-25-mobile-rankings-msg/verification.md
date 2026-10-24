# 验证与审查记录

## 改动
- 隐私聚合页开放本服排行榜，核心精确放行三条榜单命令；黄果 AI / JavRanking 与普通来源隔离。
- 榜单保存分类、列表、页码、滚动和追加错误；本返回栈成功请求不重拉，新进入、取消、失败和刷新仍加载。
- 主服切换只交接当前榜单快照；本服详情不再重复调用切服。
- 服务端配套改动见 MediaStationGo 的 extension-rankings-images 任务。

## 已验证
- 修复前，往返测试丢失第二页作品；跨服返回测试新增请求（6 -> 9）；修复后通过。
- PhoneDiscoverUiTest 14 项与 TvPrivacyTest 3 项全部通过，无跳过；覆盖多分类往返、分页延续、新进入刷新、取消重试、追加错误返回、隐私当前服匹配及跨服详情。
- go test ./emby ./bus -count=1 通过；emby 包全量 race 通过；bus 的范围策略与私密扩展测试定向 race 通过；两包 vet 通过。
- TestPrivateRankingCommandScopeHTTP 通过真实 bus.Invoke + HTTP 验证当前备用线路、字符串编号、50条页码和跨服拒绝，不只调用 handler。
- check-android-args.py（266处）、git diff --check 通过。
- 独立只读审查及主线程复核未确认本次改动的阻断问题。

## 范围外与未验证
- bus 全包 race 未通过：queue_test.go 141/162 的测试全局时间参数写入与 queue.watchStall 并发；仅执行原队列测试也复现，这两个文件本次无改动。不混入修复。
- 全仓敏感扫描把既有 journal-2.md:24 的四段版本号识别为可疑值；原文件本次无改动。仅检查本次暂存内容后提交。
- 没有打包、推送或进行真实手机/真实 MSG 部署联调；Compose 替身测试不等同于实机验收。
- 保留其它任务 10-18-unified-product-completion/resume.md 的原有修改，不归档该任务。
