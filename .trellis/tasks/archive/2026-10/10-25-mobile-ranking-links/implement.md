# 实施与验证计划

状态：实现、独立复核、针对性验证及两仓本地代码提交完成，按用户批准归档。两仓历史提交尚未获得整体推送确认，本轮不推送、不打包、不部署。

验证结果与未通过项见 [验证记录](research/verification.md)；门禁已执行不代表所有扩展检查均通过，bus 竞态及全仓隐私扫描的存量误报单独记录。

## 1. 先建立回归

- [x] 复核两仓工作树，读取即将修改的完整代码及适用规范，保留其它任务改动。
- [x] Compose：隐私快捷入口 bounds 同行且无普通聚合请求；两个原路由可达。
- [x] Compose：至少三个榜单条目时点击前三名标题/海报进入准确详情；后续排行行、返回分类/滚动状态不退化。
- [x] Compose：JavRanking 前三名及普通行显示完整番号、“战力 N”，覆盖 0/缺失值，点击仍只传来源身份。
- [x] 核心：合法红果/黄果/JavRanking 类型和编号、真实 HTTP 往返、积分 JSON 的缺失/null/0/正数；保留唯一、空、歧义、空间与主服边界断言。
- [x] MSG 隔离 PostgreSQL：黄果 movie/tv/legacy 库的正确身份与错型拒绝，隐藏/禁用/无授权/无绑定；红果合集、JavRanking 番号到详情 ID 的注册路由回归。
- [x] 对新增的布局、标题点击、积分、typed library 用例记录修复前的真实失败；禁止因缺数据库而跳过后声称通过。

## 2. 最小实现

- [x] 按 design.md 调整隐私快捷栏、整卡点击与积分展示。
- [x] 修复 MSG 黄果库类型资格，复用既有类型常量与规则；不动红果/JavRanking 匹配语义。
- [x] 如回归暴露其它影响 R1-R5 的真实缺陷，先更新证据与方案；涉及行为/安全/数据取舍扩大范围时重新征求批准。
- [x] 只同步本次新增契约，更新任务研究记录，不整理无关代码或文档。

## 3. 独立复核与门禁

- [x] 新视角审查实际 diff、共享调用方和真实字段；可委派只读核验，主线程最终决策。
- [x] LinPlayer：项目工具链下执行 `go test ./emby ./bus -count=1`（cwd `core`），对受影响范围执行 vet/race；完整核心门禁按环境能力执行并单独报告。
- [x] MSG：隔离的 `MEDIASTATION_TEST_POSTGRES_DSN` 下执行 repository 的 ExtensionCatalogMatches / ExtensionJavRankingMatches 及 handler 的 ExtensionRoutesAuthCacheAndMatching 回归，必要时添加本任务专属测试并执行受影响范围 race。
- [x] Android：使用项目 JDK/SDK/Gradle 缓存，执行 `:app:testDebugUnitTest` 的 `PhoneDiscoverUiTest`、`PhoneAggregateCacheTest`、`TvPrivacyTest`；不执行 APK 打包。
- [x] 运行 `python3 scripts/check-android-args.py`、两仓 `git diff --check`，检查本次文件无敏感信息和调试残留。
- [x] 检查 Compose 渲染（深/浅色、窄屏与较大字体），记录图中番号/战力可读性及入口排列；截图和测试不替代实机验收。
- [x] 清理新建临时服务/测试库/日志；交付已改、已验、未验和剩余风险。不擅自提交、归档、推送、部署或打包。

## 回滚点

- LinPlayer 与 MSG 改动分别可回滚，无 schema 或文件迁移；禁止用回退无关工作树改动代替回滚。
- 测试环境不能安全隔离时停止数据库执行并如实报告，不使用生产 DSN 或放宽断言。
