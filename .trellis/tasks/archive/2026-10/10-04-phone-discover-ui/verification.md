# 验证记录

- 本轮修改 DiscoverPages.kt、PhoneDiscoverUiTest.kt 与 UI_MOBILE §7.13/7.14；保留此前工作树改动。
- 独立只读审查：未发现高严重度问题；随后主线程复核差异和实际截图，修正深色主题评分/入库文字对比度。
- 最终测试结果：PhoneDiscoverUiTest 4、PhoneBrowseUiTest 7、PhoneThemeTest 8，合计 19，0 失败。
- 覆盖页内重试确实新增请求、未解锁不请求日历、榜单标题搜索和日历入库详情导航。
- 实际 Compose 截图：393dp 排行榜、320dp 日历，1.3 字号、深浅主题；包含前三名与长标题。截图位于忽略的 app/build/discover-ui。
- 参数门禁 262 处、字段门禁 202 处（既有放行 240 处）通过；git diff --check 通过。
- 未验证：真机触控、真实上游榜单/日历数据、实际订单解锁与真实播放器启动。
- 剩余风险：已有 TV Compose 映射警告；测试中使用替身数据，不能替代真机验收。
- 最终合并测试/发布进程在测试通过后收到终止信号（143），未报告原因；发布改为单独重跑，不使用中断产物交付。

- 正式包 `app-arm64-v8a-release.apk`：60.14 MiB，SHA256 `89c452574f3a62b158af228486f023a6273b8b88b03cd924010fd4a8cd525d16`；源/目标一致，ELF e_machine=183。

- 正式包 `app-tv-armeabi-v7a-release.apk`：56.62 MiB，SHA256 `c2fa926219e2bdc6510b377c2dfd4c4d629a54d6f92030a06e8548816f9a61d5`；源/目标一致，ELF e_machine=40。

- 单独 assembleRelease 成功；双 ABI 正式包 v2/v3 验签通过，整数 MiB 体积门禁通过。无调试服务启动；临时日志已清理。
