# 核心持久化规范

保留原规范路径以兼容已有任务引用。当前 Go 配置和历史使用 JSON 文件，不套用初始化模板的 ORM / SQL 迁移规则。

## 读取与写入

- `config.Load()` 只有文件不存在时允许默认空配置；已有文件格式损坏返回 `ErrCorrupt`，调用者不得吞错后保存默认配置。
- `AppConfig.Save()` 保留未知 JSON 键，写临时文件 → Sync → Close → Rename；失败清理临时文件，不直接截断原配置。
- 数据路径使用 `core/paths`；不在功能包另造数据根。
- 初始化尚未完成时不从 `config.Current()` 取默认配置再写盘；后台任务遵守配置就绪约束。
- `core/config/adopt.go` 只在新根缺配置时接管指定配置 / 历史，不顺带迁移缓存、日志或下载。扩大迁移范围需明确需求。

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
