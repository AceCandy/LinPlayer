# 实施与审查记录

- [x] 解析 SeasonId、串联候选并接入共享卡片与详情。
- [x] 补齐桌面列表、播放选集抽屉和 Android 浏览缓存。
- [x] Android 78 项回归通过：ArtworkFallbackTest、BrowseCacheTest、PhonePosterMotionTest、SeasonSelectionTest。
- [x] Go emby 全包测试、vet、Android 参数/响应字段检查和桌面风格检查通过。
- [x] 桌面 productcheck --images-only：真实回环 HTTP、卡片/列表/抽屉、成功即停、全失败、归属往返及原轮播回归通过。
- [x] 故障注入：移除季ID透传或恢复无本集主图直接跳过的旧门槛，回归按预期失败；恢复后通过。
- [x] 独立审查并点验：服务端实际 payload 不受 Fields 限制地输出季/剧ID；Season详情 Type 仍为Season；已完成Task的await仍同步完成，以新增断言保护缓存同步命中。
- [x] Compose切换条目的测试先等待重组生效再判断请求，避免把测试同步缺失误判成实现故障。
- [x] 代码提交 4e05b4ab，归档需求与审查记录。
- 打包属于后续交付动作，待用户确认平台后执行构建和产物验签。

未验证：真实服务器缺图场景、手机/TV真机及Windows/Linux实际窗口画质。桌面测试有既存依赖安全与WindowsBase版本告警，不属本次变更。
