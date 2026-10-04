# 验证记录

## 改动与回归
- 搜索、收藏和库页共享 MediaFilterChip；搜索条件横向滚动，收藏排序固定在网格上方。
- 聚合服务器标题与统计分层，续播卡复用首页信息样式；普通卡不显示时长。
- 聚合整体失败独立呈现，成功才替换缓存。首次失败及刷新失败两条用例先在旧实现失败，修改后通过。
- PhoneBrowseUiTest 7、PhoneLibraryUiTest 4、FloatingTabTest 3、PhoneThemeTest 8、ResumeCardTest 7，共 29 项通过，无跳过。
- 已人工检查 browse-ui 下六张真实 Compose 截图：393×873、1.3 倍字号、深浅主题。长服务器名省略、当前标记、续播分集/时长、固定排序与搜索条件可访问。
- Android 参数检查 263 处通过；响应字段检查 202 处通过，另有既有放行 242 处。git diff --check 通过。

## 独立复核
独立只读审查未发现本轮明确的布局、取消或缓存覆盖缺陷。静默 E_UNSUPPORTED 仍按既有契约降级，不显示可重试错误。已恢复设计文档中仅当前活跃服条目开放长按菜单的约束，本轮未调整跨服路由和写操作。

## 未覆盖与遗留
- 未做本轮真机验收、真实海报加载、服务器端到端及设备手势验证。截图使用 FakeCore 测试数据，不能代替真机验收。
- 跨源搜索原有异常只上报提示，随后可能显示“没搜到东西”；留待后续错误状态专项处理，本轮只修聚合总览失败。
- 播放器选集上限等全量调研事项维持后续独立批次。
- 保留此前未提交工作，不提交、不归档；未启动调试服务。

## 发布包
- assembleRelease 双 ABI 构建通过。构建提示 TV EpisodePage Compose 堆栈映射警告，未阻塞构建；未因此修改 TV 代码。
- 正式目录 build/android 两包与本轮中间产物 SHA256 一致；liblpcore.so 与 libmpv.so 的 ELF machine 分别为 183 / 40。
- 手机 60.14 MiB、TV ABI 56.62 MiB，满足脚本整数 MiB ≤ 60 判据。
- 两包 apksigner verify 通过，v2/v3=true、v1=false。
- 手机 SHA256：71eff241fffb1fcc3afabcd5931e925f16eb3ad304b8cb79c2f8d785f5bf7e44。
- TV ABI SHA256：e207da1b2c290ebce1a53a733ca3c5c1c5705bab19b4c8240d6134deca7be7fd。
