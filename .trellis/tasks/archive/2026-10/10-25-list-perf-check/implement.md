# 实现
- 依据：Compose 编译器报告（strong skipping 开、Item 稳定、MediaCard 可跳过；仅 menu 列表按实例比较）+ 静态扫描。
- `rememberCardActions` / resumeMenu / 详情分集卡与季卡：菜单 remember。
- `DetailPage`：atTop / scrolled 改 derivedStateOf；分集条 copy 列表 remember。
- `SearchPage`：聚合列表 key=server_id + contentType，emby_items 解析 remember；partition/chunked remember。
- `HomePage`：bannerItems remember；插件横轨 key 拼序号。
- 未做：卡片 onGloballyPositioned 换 onVisibilityChanged —— 新 API 不计父级裁剪，4 个海报动效测试失败，已撤回。
- 验证：全量 1022 测试 0 失败；新增 PhoneListStabilityTest 2 例，旧代码上均失败。
