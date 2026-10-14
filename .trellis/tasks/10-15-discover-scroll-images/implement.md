# 实施与验证

1. 客户端滚动加载/刷新与布局，测试分页累计、失败重试、切分类取消和底部展示。
2. 图片生命周期修复，真实 HTTP 代理回归，避免泄露 MSG token 至外部图片。
3. MSG 范围已获授权，补充现有来源目录、同源图片和可信周历时间，验证接口。
4. 独立复核，定向 Go/Android 测试与契约检查；用户本轮已授权提交、归档与 Android 手机出包。

## 客户端阶段结果

- 排行榜已移除翻页按钮，新增下拉刷新与触底追加，逐项 LazyColumn，底部 inset 参与滚动；去重、空页/无新增结束、失败原页重试。
- 外部图片登记接入账号层，真实 HTTP 日历→图片代理测试修复前 HTTP404，修复后成功；验证第三方无 Emby 凭据且签名查询不变。
- Go account/emby/net/localserve 测试通过；Android PhoneDiscoverUiTest 与 TV extension 定向测试通过；参数/字段及 diff 检查通过，已查看刷新后长榜单渲染图。
- 独立只读复核建议的“连续刷新失败后重试入口丢失”与源码保留 refresh-error 分支不符，新增 repeatedRefreshFailureKeepsRowsAndRetry 后通过，未按误报修改逻辑。
- 公开 Bangumi 周历及按 MSG 规则规范化的首个图片样本联网返回 HTTP200 image/jpeg；未使用真实 MSG 账号，未验证用户手机或用户服务器。
- MSG HEAD 9716637f。TMDb 图片由 MSG 图片路由返回，豆瓣/Bangumi 原上游URL。红果、黄果 AI 网站目录未接入扩展；Bangumi 单列日历。broadcast_time 当前为 null。用户已明确授权修改 MSG；正在补齐榜单与可信时间，保留无关 TMDb 工作树改动。


## 两端实现与最终复核

- MSG 仅修改扩展 handler/repository、Bangumi 周历服务及对应测试、API 目录和扩展规范；保留已有 TMDb 分层工作树。新增红果 4 榜、黄果 AI 3 榜、Bangumi 每日放送，来源身份字符串贯穿展示与实时匹配。黄果遵守成人开关/用户/播放配置权限。
- Bangumi 海报由 MSG 同源鉴权路由代理；红果/黄果复用本地 artwork。真实 HTTP 测试验证 GET 图片字节、HEAD 空正文、Bangumi 上游没有播放器令牌。
- bangumi-data 的 CDN 数据已成功下载并核实包含显式 P7D/RFC3339 周期；后续公开周历与数据联合覆盖率请求超时，因此不声称完整覆盖。仅明确 ID、同星期的时间可显示；未知不猜，时间源失败保留同作品同星期的旧时间。
- 时间字段贯穿 Go 解析、展示缓存、手机与 TV。手机 01:30 时间节点截图已人工查看；保留未知时间和日本时区说明。
- 独立只读复核后点验：普通 collect 挂起串行，不会并发执行 collector；displayBlock 在 reader.join 后 ensureActive，取消不会回写旧结果。审查的 comic 缺榜判断混淆已停用漫画 comic 与漫剧 comic-drama；已新增真实两种分类测试。成人发现与媒体库可见性是不同权限，现有 canServeHuangGuoAIArtwork 优先放行 discover；新增允许成人但空可见库仍可浏览、匹配为空测试，未按误报修改权限。

## 验证

- LinPlayer check-core.sh 全部门禁通过（使用项目 mpv 动态库、python3 兼容命令与本地 dotnet 工具链）；定向 account/emby/displaycache/localserve 通过。
- Android PhoneDiscoverUiTest、TvFocusTest.extension*、DisplayCacheTest 通过；Robolectric 渲染查看时间节点和此前列表布局。
- Android 参数 264 处、字段 203 处检查通过（既有 255 处无法解析类型豁免未增加）；隐私检查 1915 个已跟踪文件及 4 个新增文件无新增敏感项；diff --check 通过。
- MSG 临时 PostgreSQL 下 handler/repository/service 的 TestExtension*、TestDiscoverBangumi* 通过，包括 -race；新增分类和空库边界后的 handler -race 再次通过。
- MSG Web tsc -b 与所改 API 目录 ESLint 通过。未做全站 Web UI 验收。
- 没有使用用户真实账号/服务器，没有真机播放或双端部署联调；没有新 APK、提交或部署。旧 APK 不含本次修改。需要更新 MSG 与 LinPlayer 才能使用新增完整能力；旧周历快照按现有 6 小时刷新周期更新。


## 交付前审查与修复

用户授权再次审查、有问题修复、提交归档并出手机安装包。独立审查与主线程点验发现两处边界：放送已结束不到一周的条目可能继续显示本周时间；普通元数据中的独立来源标识可能与红果/黄果真实绑定一并返回。新增回归测试在旧实现下均失败，修复后通过。时间现在按日本本周验证放送周期处于起止区间，合法本周已播时刻仍保留；普通 metadata 查询限定 TMDb/豆瓣/Bangumi，独立来源只走真实绑定。

- 修复后的两处代码另行独立复核通过。
- MSG 工作树与 HEAD 加本次 12 个文件的隔离源码均通过 handler/repository/service 定向 -race 回归，证实不依赖未提交 TMDb hierarchy 工作。
- LinPlayer 完整核心门禁重新通过；Android 对应测试任务检查通过（源码未变，测试结果复用）；参数/字段/隐私和 diff 门禁通过；MSG API 目录 tsc 与 ESLint 通过。
- 当前提交只包含本任务文件，MSG 的其他未提交工作保留。随后执行手机 arm64 release 构建与最终产物验签；真实服务器/手机播放验收仍未覆盖。
