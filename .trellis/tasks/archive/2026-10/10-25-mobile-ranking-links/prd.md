# 修复手机榜单关联跳转与隐私入口排列

## 目标

手机隐私空间快捷入口与普通空间保持一致；榜单中有可见已入库关联作品时，可以进入正确详情，JavRanking 同时展示番号和战力积分。

## 基准与边界

- 基准：LinPlayer `fe50d315`、MediaStationGo `e1d3767d`；MSG 工作树干净，LinPlayer 原有 `10-18-unified-product-completion/resume.md` 修改保留不动。
- 手机点击已经接入实时 `emby.extResolve`，只传来源身份，不按作品标题搜索：`apps/android/app/src/main/kotlin/xyz/linplayer/app/data/Extension.kt:31`。

## 需求

### R1：隐私入口并排

隐私空间“流影”“排行榜”横向并排，复用普通快捷入口外观及路由，不读取普通聚合快照。当前缺陷为两个纵向全宽按钮：`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/AggregatePage.kt:82`。

### R2：榜单点击与红果合集

前三名海报、标题及新增展示文字均可触发同一点击；红果榜单唯一匹配当前用户可见的已入库作品后进入对应合集。

前三名仅海报绑定点击，标题区域不响应；其它排行行绑定整行点击：`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/DiscoverPages.kt:399`、`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/DiscoverPages.kt:405`、`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/DiscoverPages.kt:456`。MSG 已有红果合集匹配，返回 `hg-group-<related_album_id>`：`../MediaStationGo/internal/repository/emby_ext_matches.go:158`。

### R3：黄果 AI 关联详情

电影专用库、剧集专用库和旧混合库均可按各自合法作品类型匹配正确关联详情，保留权限、类型和文件资格约束，沿用独立作品身份。

当前查询固定 `lib.type=huangguoai`，漏掉 `huangguoai_movie`、`huangguoai_tv`：`../MediaStationGo/internal/repository/emby_ext_matches.go:167`；三类合法库定义在 `../MediaStationGo/internal/model/library_media.go:3`。

### R4：JavRanking 关联与展示

唯一完整番号命中后进入 NFO 作品详情，列表与前三名均可见番号和战力积分；积分不得当作十分制评分。

MSG 已按完整番号及边界匹配可见的“日本中字”库，不会任取歧义结果：`../MediaStationGo/internal/repository/javranking.go:259`、`../MediaStationGo/internal/repository/emby_ext_matches.go:127`。MSG 已返回 `external_id`（番号）和 `score`（战力积分），LinPlayer 的 `extensionEntry` 未保留积分，手机未显示番号：`../MediaStationGo/internal/handler/emby_ext_catalogs.go:89`、`core/emby/extension.go:137`、`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/DiscoverPages.kt:122`。

### R5：错误、空间与返回状态

无匹配、多个匹配或请求失败明确反馈；隐私空间仅使用当前服，普通空间沿用主服解析，返回仍保留原榜单状态。

## 验收标准

- [x] AC1（R1）：两个入口处于同一横行、可分别进入原页面；隐私入口不读取普通聚合快照。
- [x] AC2（R2）：使用真实 MSG 注册路由与来源绑定夹具验证红果解析到合集；手机点击前三名标题和海报、后续排行行均打开对应详情，返回不丢分类与列表。
- [x] AC3（R3）：电影专用库仅匹配电影、剧集专用库仅匹配剧集、旧混合库兼容两类；类型冲突、隐藏/禁用/未授权/无文件时不产生错误关联。
- [x] AC4（R4）：完整番号、特殊字符、相似番号边界和歧义行为保持；手机行与前三名显示番号、真实积分（含 0），不渲染为评分。
- [x] AC5（R5）：核心真实命令/HTTP 与手机 Compose 回归覆盖来源身份、当前服、失败反馈和返回状态。
- [x] AC6：实现后独立复核，执行针对性测试并记录未验证项；本轮不默认打包、提交、推送或部署。

## 范围外

- 不改图片接口及其权限，不新增上游在线资料页或未入库播放能力。
- 不扩大 JavRanking 的目标库范围，不按中文标题模糊匹配，不任取多个结果的第一项。
- 不调整其它端布局、不迁移媒体数据、不处理其它进行中任务或无关失败。

## 风险与验证边界

- 已核对红果与 JavRanking 的 SQL、详情 ID 分发、来源传递及账号/线路解析，没有确认额外断点；不将“标题区不可点”推定为用户所有点击失败的唯一原因。
- 能力缓存只在登录/普通首页主动刷新更新；旧部署、旧能力缓存、无入库绑定、权限过滤及多匹配均可能阻止导航，不绕过这些边界。
- 验证使用隔离夹具，不读写生产数据库；MSG 注册路由测试、核心 HTTP 测试及 Compose 测试分层执行，不能宣称等同真实部署与实机联调。上线仍需检查部署后的实际匹配结果。
