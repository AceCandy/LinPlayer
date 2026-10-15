# 实施与验证

1. 加库字段、幂等迁移和设置兼容投影，隔离 PostgreSQL 测试。
2. 添加请求范围解析、共享过滤与缓存隔离，覆盖普通/NFO/红果/黄果及续播分页；补库归属响应。
3. LinPlayer 接入参数和字段，更新命令契约及接口目录；HTTP 测试验证三态与缺省兼容。
4. 运行相关 Go 测试/vet 和静态检查；独立只读审查后修复。禁止自动出包。
5. 记录验证结果及未覆盖实服/设备项；关闭临时测试服务。

## 验证结果
- MSG：隔离 PostgreSQL 上迁移、四来源三态分类/续播分页、实际历史来源、默认 Profile/用户/全局权限、季归属、真实 HTTP 参数与缓存隔离测试通过。
- 相关回归通过：MediaVisibility、FavoriteLibraryMembership、Views、BrowseRequestErrors、ShowsRejectEmptyParent、ItemsOptionalTotal、Continuation；相关 service/handler/database/repository vet 通过。
- LinPlayer：emby/aggregate/displaycache 包测试与 vet 通过；季归属透传测试通过；生成绑定 --check 通过。
- MSG Web：tsc --noEmit 与完整 eslint 通过；两仓 git diff --check 通过。
- 独立只读复核完成；人工核验绑定表确有 work_id 列。null 明确为非法参数，不等同缺省。
- 故障注入：通过临时 Go overlay 旁路分类交集，新回归按预期失败；工作树源码未被修改。
- 既有失败：TestConfiguredAdultLibrariesDoNotInferMediaRestrictions 的标题为空，在隔离 HEAD 源码复现相同失败；不修改无关元数据行为。
- 未验证：线上数据库升级、实服联调、浏览器交互、真机与打包；本轮未部署、未提交，不包含隐私空间 UI。
- MSG 有其他并发红果/黄果修改，保留原状，不计入本任务交付。

## 设置入口迁移验证
- 系统设置删除成人库名单及专用加载/选择控件；媒体库弹窗增加单库开关，经管理员 PATCH 写入，字段独立更新，事务内沿用首次成人库默认隐藏策略。
- 新 HTTP 测试验证 true/false、封面互不覆盖、权限、缓存失效、首次隐藏/后续偏好保留、黄果 AI 拒绝与整笔回滚。相关成人迁移、库列表、Emby HTTP 回归及 vet 通过。
- Web tsc/noEmit、完整 eslint、diff check 通过。agent-browser 本地组件夹具验证开关回显和黄果 AI 固定状态，390/768/1440 宽度及明暗主题检查无横向溢出；未连接真实账号验证完整保存链路。
- 独立只读复核无确认缺陷。保留旧设置 API 兼容，不再提供系统设置 UI 分类入口。
- 后续同类字段归属迁移须同时确认管理写入口、查询响应和前端编辑位置，不能仅完成数据库与读接口就视为完整迁移。
- 未编译部署或访问线上数据；临时预览文件、浏览器、Vite 和测试数据库均清理。

## 清理旧成人策略（覆盖前述兼容保留结论）
- 已移除成人设置投影、全局开关/PIN 设置页、Profile 成人字段/表单/展示及首次标记重置用户。旧 adult.* 写入 HTTP 400。
- 升级事务搬迁未转换的显式名单；已有迁移标记时保留库字段；清除旧 adult.* 配置和 play_profiles.allow_adult，无库名/路径推断。黄果 AI 固定成人仍为当前来源规则。
- 隔离 PostgreSQL 测试通过：旧/已迁移/新库、重启幂等、旧字段删除、错误回滚、用户偏好不变、Profile PIN/库范围、缓存失效、Emby 三态、HTTP 拒绝旧设置。相关 Go vet 通过。
- Web tsc/noEmit、完整 eslint、diff check 通过；agent-browser 设置页回归通过（保存/草稿/刷新/权限、分类移除、明暗主题及390/768/1023/1024/1440宽度）。独立只读复核完成。
- 未上线、未访问真实数据库、未打包；升级将删除旧字段/配置，回滚旧程序须恢复对应数据库备份。临时测试数据库和 Vite 已关闭。

## 手机实施计划
1. 接通播放/聚合/本地历史分类，回归三态与续播隔离。
2. 手机模式、缓存代数与长按入口，回归长按/单击、退出、迟到响应、搜索记录。
3. 运行相关Go/Kotlin测试与参数检查，独立只读复核；明确未测实服/设备，不打包。

## 手机实施验证结果
- 已实现服名长按无PIN进入、单击切服、私密标题点击/首页返回退出；导航、缓存和迟到回调按空间隔离。私密搜索不写普通记录，下载及未隔离插件入口隐藏。
- 播放、准备、排队、聚合及本地续播贯穿三态分类；历史保存实际选中媒体源库归属，展示/清空/恢复实时核验库分类与权限。
- emby、history、datasource、aggregate、displaycache包回归通过；player分类、准备、历史补建、Exo及相关续播回归通过，相关包vet通过。临时overlay移除续播隔离后新测试按预期失败，真实源码未改动。
- Android BrowseCache、PhoneHomeRefresh、PhoneRecordsUi、PrivateSpace、PlayerActivity、PlayerLaunchPage相关测试通过，覆盖实际长按/单击、缓存代数、迟到结果、下载菜单与旧Intent拒绝。
- Android参数检查264处、生成绑定320命令ABI1、diff检查通过。独立审查发现的共用卡片下载入口已修复并回归；备用线路疑虑按config/account.go同服不同入口及ActiveLineURL契约核对，不改回失效旧线路。
- 未验证真实MSG联调、真机播放/PiP/后台行为；未执行完整构建门禁或打包。完整player测试的两项libmpv测试初始化失败（mpv_create、初始化-1），相关定向回归通过，不能宣称全量播放器测试通过。
- 本轮不提交、不部署、不打包；首版不增加后台自动锁定。

## 隐私空间收敛为本服（覆盖先前私密本地历史接入方案）
- 用户明确隐私只看当前服服务端资料，保留普通模式既有本地历史。去除私密首页历史、聚合Tab、聚合搜索和跨服换源；保留同服版本选择，禁止插件接管/导航进入私密空间。请求层拒绝本地历史、跨服聚合和覆盖当前server/user的媒体请求。
- 私密播放只用本服实时进度，禁用本地续播和主服读写，Capture及已看回调不写本地历史；当前服Sessions上报、从头、精确位置和切集共用规则保留。
- MSG只读核验基准cc85d86c，仅未跟踪core，未读取；无完整历史事件接口，Resume与IsPlayed/DatePlayed不能冒充完整历史。本轮未修改MSG。
- Go history/emby/aggregate/datasource/displaycache及player私密/准备/补建/Exo/续播相关定向回归通过，player/history vet通过。新增HTTP回归验证本服7秒优先于本地200秒、不访问配置的主服、本地记录不改写且本服停止上报保留；从头/切核精确位置保留。
- Android 8项相关回归通过：PrivateSpaceTest、真实PhoneRoot私密/普通导航、长按进入退出、私密详情入口。测试夹具漏导入/命令返回和输入节点定位均已修正。
- 故障注入移除私密Capture保护，新回归按预期失败，临时overlay已自动清理，工作树未改动。独立只读复核无确认缺陷。参数264处、绑定320命令ABI1、diff检查通过。
- 未验证真实MSG账号联调、真机播放/PiP/后台；未打包、部署或提交。此前完整player的两项libmpv初始化失败仍为未解决环境验证缺口，本轮没有宣称全量通过。
- 根因记录：隐私功能的数据来源和服务器范围必须先按用户目标确认，不能默认复用现有全局本地历史/跨服能力；服务端继续播放与完整观看历史需分别核实契约。
