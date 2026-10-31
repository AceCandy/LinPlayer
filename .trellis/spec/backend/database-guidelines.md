# 核心持久化规范

保留原规范路径以兼容已有任务引用。当前 Go 配置和历史使用 JSON 文件，不套用初始化模板的 ORM / SQL 迁移规则。

## 读取与写入

- `config.Load()` 只有文件不存在时允许默认空配置；已有文件格式损坏返回 `ErrCorrupt`，调用者不得吞错后保存默认配置。
- `AppConfig.Save()` 保留未知 JSON 键，写临时文件 → Sync → Close → Rename；失败清理临时文件，不直接截断原配置。
- 数据路径使用 `core/paths`；不在功能包另造数据根。
- 初始化尚未完成时不从 `config.Current()` 取默认配置再写盘；后台任务遵守配置就绪约束。
- `core/config/adopt.go` 只在新根缺配置时接管指定配置 / 历史，不顺带迁移缓存、日志或下载。扩大迁移范围需明确需求。
- `Prefs.liuying_speed` 为流影独立持久倍率，缺省或越界回1倍；`player.setPlaybackPrefs` 仅在显式提供该键时更新，非法类型/非有限值/越界返回 `E_INVALID`，保存失败恢复内存快照。普通 `default_speed` 保持独立。`player.play(speed_scope="liuying")` 仅替换本次起播的偏好快照，不回写普通倍率；省略/空串仍普通，未知作用域拒绝。回归见 `player/liuying_speed_test.go`。

## 回归检查

覆盖新装缺文件、已有损坏 JSON、未知字段保存、写入失败和临时文件清理。读失败不能改写原文件，旧数据接管不能覆盖新根配置。
参照 [实现](../../../core/config/config.go)、[配置测试](../../../core/config/config_test.go)、[数据接管](../../../core/config/adopt.go)，运行核心门禁。

## 场景：指定主进度服

### 1. 范围 / 触发
Emby 播放续播与副服回写；未指定主服时保留旧本地跨服续播。

### 2. 签名
`prefs.getPrimaryProgressServer`、`prefs.setPrimaryProgressServer({server_id:string})`、`prefs.retryPrimaryProgressSync`；`Prefs.primary_progress_server={server,user_id}`；`paths.ProgressSyncFile()` → `progress-sync.json`。

### 3. 契约
选择固定账号主键及用户，空字符串关闭；切服不迁移队列。返回 `server/user_id/name/valid/pending/conflicts/error`，凭据只留核心。可信唯一 TMDB（电影）或剧 TMDB+季集（分集）匹配，候选需完整，版本时长差≤1秒；未知/不确定按所在服远端进度起播。主服0/回退直接覆盖旧本地最大值，显式从头优先。主服正常播放保留普通上报，不自做CAS。

副服回写前原子保存固定身份、基线Revision和目标快照；读取→条件POST→回读核验。丢响应且当前版本变化为相同状态时确认成功；不同状态标永久冲突，重试不强制覆盖。网络gate串行，磁盘锁不跨网络。账号epoch失效等待在途写入结束，切换完成后旧会话不能联网；单次同步5秒、手动重试20秒预算。

### 4. 校验 / 错误
坏账号/非Emby/缺用户 → EInvalid；配置保存失败恢复内存值；文件损坏不覆盖；磁盘或网络失败展示状态，不宣称已保存。主服无接口、不可用、不唯一、时长不兼容不阻止播放。旧主服待同步记录保留，重试只当前固定身份。

### 5. 正常 / 基础 / 错误案例
正常：主服0覆盖副服与本地正进度，副服1秒回写准确。基础：独有资源保留所在服远端进度。错误：主服被其他设备改动，旧基线不得覆盖；同名异ID不得匹配。

### 6. 必要测试
`progress/store_test.go` 覆盖零、失败落盘、重启重试、冲突、丢响应、旧owner、在途切换、损坏保护；`history/primary_match_test.go` 覆盖唯一性和短页推进；`player/primary_progress_test.go` 覆盖主服零/回退/从头；prefs覆盖固定用户、删除、重登及关闭。运行核心/绑定门禁，真实客户端与服务端HTTP及隔离PostgreSQL联调；真机多端单独验收。

### 7. 错误与正确做法
错误：按最大值合并主服与本地进度，或按更新时间强制覆盖冲突。正确：以可信主服完整快照为准，版本CAS拒绝陈旧写入。列表/详情未做批量主服状态覆盖；不能把起播同步宣称为全库显示同步。

## MSG 扩展展示快照

`emby.extRankings/extRankingItems/extCalendar` 使用统一 displaycache（1000条、无TTL、每条512KiB），以服务器/用户/榜单页隔离。只保留文字、来源ID、媒体类型、排名与星期；图片URL、媒体库匹配与播放授权不落快照。最终结果仍实时，网络失败由宿主保留旧展示，401/403/不支持/无效数据删除该快照。`extCapabilities/extResolve` 不缓存；能力与匹配不充当播放授权。测试：`emby/extension_test.go`、`displaycache/cache_test.go`。

扩展海报仍经统一图片代理：同源301/302保留认证；跨scheme/host移除Emby及标准认证/Cookie，之后跳回也不恢复，保留十跳上限。`localserve/redirect_auth_test.go` 覆盖同源、跨源和跨源后跳回；不能依赖Go默认重定向策略保护自定义令牌头。


## 弹幕手选记忆与源身份

`paths.DanmakuMatchesFile()` 保存手选映射，原子写入、损坏拒绝覆盖，不随清缓存删除。键为服务器/用户/条目或剧季身份的散列，不记录账号令牌。源地址/鉴权指纹改变、停用或删除后不复用旧映射；未知媒体身份只临时挂载。下一集查真实集表的唯一普通集号，禁止分集ID加减和数组位置兜底。缓存文件名散列完整复合键，避免长ID截断碰撞。回归：`danmaku/resolve_test.go`。

播放器以 `danmakuBegin` 分配代数，`danmakuSet` 校验代数后替换原始语料；时间偏移只参与排版和热力统计，不累加修改原始时间，换语料归零。回归：`player/danmaku_session_test.go`。

服务器 raw 弹幕按当前账号和媒体实时请求，不写第三方弹幕持久缓存；能力查询同样实时检查，不跨账号复用权限结果。叠加前复制源缓存语料，避免排序或追加修改共享底层数组。

## 聚合主服与统计刷新

### 1. 范围
手机聚合扩展能力固定主服；聚合展示刷新保留统计和续播，与主进度同步独立。

### 2. 签名
`account.setAggregatePrimaryServer(server_id: string)`；空串清除。`emby.aggregateOverview(counts_only?: bool)`；`emby.extRankings(include_disabled?: bool)`。

### 3. 契约
`AppConfig.aggregate_primary_server` 持久保存稳定账号标识，不随当前服切换；账号列表新增 `aggregate_primary`。总览新增 `line_url/counts_known/counts_error/resume_unchanged`。`counts_only=true` 仅对已有续播快照的服跳过续播，首次仍取；省略时保持完整刷新。快照按账号集合、用户、线路和聚合开关隔离，切当前服复用；统计与续播独立合并，失败不覆写成功值。线路不落盘，回放从当前配置恢复。目录默认仍过滤未开启项，显式 `include_disabled=true` 返回完整目录与 `enabled`。

### 4. 错误
主服不存在/非Emby/缺用户或参数类型错误 → E_INVALID；保存失败 → E_INTERNAL并回滚内存。删除主服清除选择。统计失败标记未知，真实0保持已知；续播失败不保存为空成功。

### 5. 案例
正常：主服A、当前B时手机榜单使用A的身份；切服后统计快照仍可显示。基础：无主服沿当前服。失败：首次统计失败不显示0，已有17时失败仍展示17；保存失败不改变主服。

### 6. 回归
`aggregate_primary_test.go` 检查保存/切服/重启/删除、拒绝无效及落盘失败回滚；`refresh_test.go` 检查有效线路、缓存后统计刷新零续播请求、失败保留、首次未知和旧调用完整刷新。`PhoneAggregateCacheTest` 检查下拉不位移与真实0覆盖；`PhoneDiscoverUiTest` 检查图片代理、两级分类、主服请求及跨服详情身份。

### 7. 错误与正确
错误：用当前服地址加载所有服条目，或把统计请求失败当0。正确：图片使用所属服有效线路，统计通过已知标志区分真实0和未知；只在成功后覆盖。

扩展外部图片来源必须经账号模块 AllowImageOrigin 登记，不能只调用 localserve.AllowDefault，否则账号白名单重建会令已显示海报变成404。emby 通过初始化回调接入，避免包循环依赖；第三方请求保持无 Emby 凭据且不改图片查询参数。回归 extension_images_test.go 必须经过真实本地 HTTP 代理并在取图前重建账号白名单，URL 字符串断言与合成图片不能替代此检查。

- 扩展展示快照允许 `broadcast_time/timezone/time_source`，仍剔除图片 URL、实时媒体库匹配和播放授权；独立来源 `external_id` 作为字符串贯穿解析与匹配，不能转为浮点或 int64。

## 本地观看历史分类

`Record.is_adult`与`Candidate.is_adult`为可空bool；nil是未分类，不能自动视作false。已分类Capture的记录ID分别带`:normal`/`:adult`，相同作品不能互相覆盖；缺省写入不能吞并已分类记录。带分类的本服/跨服续播只处理同类快照；历史列表、清空及恢复以实际选中媒体源的library_ids查询当前账号Views核验分类与权限，无归属时仅采用明确分类。清空原子删除本次核验可见的记录ID。缺省查询/清空保留全量语义。分类来自本次播放请求上下文，需贯穿预热、准备槽、排队和历史补建；准备消费还需匹配分类。此字段是播放时快照，不冒充实时库分类，旧未分类记录保留。

隐私播放（is_adult=true）只采用当前服实时进度，跳过本地续播匹配、Capture和主服Prepare/Sync；手动已看回调也不落本地记录。内存中的条目资料仅用于起播、阈值和本服上报，普通/未分类播放沿原逻辑。既有历史不因这项策略删除。

## tvOS 安全后端

`ios && tvos` 构建在 FFI 注册命令前安装 `statefile.Keychain`，配置/屏蔽名单的 JSON 及事务由原包拥有。仅不存在允许新装；Keychain 不可访问/损坏不能映射成不存在，已有配置缺隐私名单必须失败。新装创建空名单与稳定随机设备标识并保存。`config.Save` 直接写安全后端；`blocklist.Set` 安全写失败恢复内存并向命令传播错误。其它平台保持原文件路径/写入策略。

用内存后端覆盖清缓存恢复、未知字段、退出不复活、坏 JSON/null、读写失败；这不证明 Keychain 应用权限，正式签名 app 的跨进程/清缓存验证仍是独立真机门禁。


## 播放位置片头片尾标记

1. 范围：核心与Android手机/TV、桌面手动标记，不新增自动识别。
2. 签名：`player.getSkipRange(item_id,scope?,runtime_secs?)`，`player.setSkipRange(item_id,scope?,action?,position?,runtime_secs?,旧区间字段?)`；命令表为真源。
3. 契约：显式season/episode按片段继承，episode>season>旧剧；读取auto选择可靠季，否则本集。季ID优先，缺失才用剧ID+季号，0有效。无scope保留旧整条替换和全零删除。片尾outro_len/outro_end_offset分别表示距结尾的开始/结束距离，保护后续内容；旧绝对值兼容回退。
4. 错误：非法类型、负数、非有限数、倒序、缺播放时长和越界写入拒绝；自定义起止先有完整默认区间。写前复制map，同类标记写入串行，落盘失败恢复旧偏好；不自动迁移或删除旧键。
5. 案例：季一第1集标记可用于第2集，但不能用于季二；仅本集片头不能覆盖继承的季级片尾。清除本集恢复下层规则。
6. 回归：SkipMarkersScopesAndOffsets覆盖季/集、局部更新、片尾偏移、非法区间及回滚；SkipMarkerIdentityAndLegacy覆盖缺剧ID但有季ID、特别篇0、电影、旧双字段回退。手机面板/TV焦点与桌面seekcheck --skip-markers-only验证真实UI入口。
7. 正反例：不能由宿主各自拼存储键，不能用保存回调触发seek。保存/刷新期间保护自动跳过，当前新标记区间仅显示手动操作。TV忙碌时保持同一焦点节点，不能以禁用重建行造成焦点跳回首项。

标记刷新后只重置已变化片段的“本次已跳过”状态，未改动片段保留；当前位置位于新标记内仍禁止立即自动跳转。清除恢复继承区间的手机/TV回归必须先触发旧区间，再保存/清除，再进入新区间；只测保存payload看不见旧状态屏蔽。读取及写入保护状态绑定播放controller，防止同集换版本留住旧busy状态。
