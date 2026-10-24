# 技术方案

## 边界与现有实现

- 需求正本为本任务 `prd.md` 的 R1-R5；本任务视为一个榜单体验修复，不拆出独立架构或数据迁移。
- 复用 LinPlayer 的 `Shortcut`、`pressable`、`openExtension`、`Route.Detail` 和 MSG 的 `ExtensionLibraryMatches`。不新增端点、命令、绑定或导航结果类型。
- Android 设计按现有原生源码和 `docs/go-migration/UI_MOBILE.md`；`docs/DESIGN.md` 是 Apple Web 风格参考，不将其主题套入原生榜单。
- 只修改任务文件；另一个进行中任务的 `resume.md` 仍保持开工时的原有修改。

## 入口与点击

1. `AggregatePage` 的隐私分支保持早返回，不进入普通聚合数据读取。两个原 `Shortcut` 放在同一 `Row`，各 `weight(1f)`，沿用普通快捷栏间距、图标和路由。
2. `Pod` 把唯一点击修饰符从海报容器移至整卡，海报内部不保留嵌套点击，避免一次操作产生重复解析。排行行继续使用已有整行点击。
3. 所有来源仍只发送 `source/external_id/media_type/server_id`，禁止标题搜索、缓存授权或从积分推导媒体身份。
4. 解析到红果 `hg-group-*`、黄果 `hga-*` 或 JavRanking `nfo-*` 后走原详情；切服与返回快照交接不重写。

## MSG 黄果库资格

`extensionCatalogMatches` 保留来源表、真实媒体绑定和作品类型联接。库资格按来源区分：

- 红果只允许 `LibraryTypeHongGuo`。
- 黄果允许旧混合库，以及 `HuangGuoAILibraryKind(lib.Type)` 所代表的匹配类型专用库；电影不能借剧集库或相反类型绑定产生匹配。
- 继续应用库启用、当前允许/隐藏库、成人可见性、作品投影就绪及真实绑定限制。只扩展漏掉的合法库类型，不放松访问控制。

红果合集与 JavRanking 番号规则已存在，不为未确认的问题另建匹配器。JavRanking 继续限定名称为“日本中字”的当前可见启用库，使用已有完整番号与边界匹配、NFO 逻辑作品去重及歧义反馈。

## JavRanking 展示字段

- MSG 已返回 `external_id` 和整数 `score`，不另加重复 `code`/`number` 字段。
- 核心 `extensionEntry` 添加可空整数积分，并在榜单输出保留可用的 `score`；字段缺失或 null 保持未知，明确的 0 仍展示。
- Android `Rank` 从 JavRanking 的现有 `external_id` 取得番号，读取可空积分。前三名和普通行均显示番号与“战力 N”文字；不套星形评分，不按十分制格式化。
- 非 JavRanking 的评分、标题与副标题不改。核心新增可选输出对旧宿主兼容，不改 ABI 或生成绑定。
- 旧展示快照可暂时不带积分，实时结果按原流程补齐；不为展示新增请求或修改能力缓存刷新策略。

## 预期文件

LinPlayer：

- `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/AggregatePage.kt`：隐私入口并排。
- `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/DiscoverPages.kt`：整卡点击与番号/积分展示。
- `core/emby/extension.go`：保留积分。
- `PhoneAggregateCacheTest.kt`、`PhoneDiscoverUiTest.kt`、`core/emby/extension_test.go`：布局、点击和真实 HTTP 字段回归；独立来源测试使用 MSG 合法的类型/编号。
- `docs/go-migration/UI_MOBILE.md`、适用 Android 规范：记录本次明确的布局、点击与积分语义，不整理无关章节。

MediaStationGo：

- `internal/repository/emby_ext_matches.go`：黄果库类型与作品资格。
- `internal/repository/emby_ext_catalog_matches_test.go`、`internal/handler/emby_ext_test.go`：隔离数据库与注册路由匹配断言。
- `.trellis/spec/backend/emby-extension-discovery.md`：明确三类黄果库的精确匹配边界；若 API 目录说明因此不准确，仅同步相关描述。

## 兼容、回滚与剩余限制

- 不改表结构、现有身份、媒体文件、绑定关系、图片权限或上游同步；回滚仅需还原本次对应提交/文件，不需要数据迁移。
- 保留缺能力、零匹配、多匹配、权限/鉴权失败的真实反馈；不自动探测或刷新隐私能力，不任取候选。
- 分层测试不能验证用户手机实际点击位置、生产媒体绑定、服务端部署及能力缓存是否已更新。若测试通过后仍存在问题，需用部署后的脱敏错误与匹配响应继续定位，不能将配置/数据未命中伪装修复成功。
