# 榜单链路核验

## 确定缺口

- `AggregatePage.kt:82-92`：隐私入口纵向排列，与普通 `Shortcuts` 的 Row/weight 不同。
- `DiscoverPages.kt:399-425`：前三名外层 Column 无点击，只有海报 Box 的 pressable；标题不在可点击区域。现有往返测试主要点击第 20/25/45 行，没覆盖前三名标题。
- `core/emby/extension.go:137-194`：没有 score 解码与输出；MSG 的 `emby_ext_catalogs.go:89-105` 已返回原始 code（external_id）、score。
- MSG `emby_ext_matches.go:167`：lib.type 仅等于 source，黄果漏掉 movie/tv 专用库。合法类型定义与强制分类在 `internal/model/library_media.go:3-27`、`huangguoai-catalog.md`。

## 已检查但未发现额外断点

- `openExtension` 保留 source/external_id/media_type/server_id；主服账号身份由 SessionOf 解析为当前线路；activateMediaServer 检查返回线路/用户。
- capabilities 按服务端扩展基址和用户隔离，只在登录及普通首页显式刷新更新；旧缓存没有来源时 extResolve 会正确拒绝。
- MSG `applyMediaViewFilter` 对 extensionCatalogMatches 使用已有 media m 别名，没有确认 SQL 别名错误。
- 红果 source_id -> 真实媒体绑定 -> ready album -> hg-group-ID；标准 Emby 详情支持 hg- 分支。
- JavRanking 完整番号及边界 -> 可见启用“日本中字”库 -> 现存 NFO 绑定 -> distinct nfo-ID；详情支持 nfo- 分支，多逻辑作品不自动选第一项。
- MSG `testdb.OpenPostgres` 无测试 DSN 时跳过；必须明确数据库回归真实执行，不能只看命令退出码。

## 独立核验结论

只读核验分别检查了服务端 SQL/详情身份和客户端来源/账号/能力/路由链路。未发现能单独解释用户全部红果/JavRanking点击失败的额外确定缺陷；整卡点击修复后仍需保留并检查真实匹配失败反馈。

## 非目标与隐私

不读取生产数据库、账号或原始请求日志；不把真实线路、用户身份或凭据写入任务材料。两个仓库当前版本见 PRD，不将旧任务测试记录作为本次测试通过证据。
