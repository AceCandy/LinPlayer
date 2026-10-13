# 设计

Go emby 包新增 extCapabilities、extRankings、extRankingItems、extCalendar、extResolve。统一会话、HTTP策略与错误分类；扩展请求禁止重定向，基址仅移除末尾 /emby，保留反代路径。能力实时探测，不以缓存授权。

榜单目录归一化为 id/label/group/source（group为来源），条目为现有展示字段与 source/external_id/media_type；榜单每页按服务端顺序，宿主提供翻页，空页或第500页停止。公共日历按 Asia/Tokyo 周历展示，不推算精确时刻或分集。

目录、每页榜单和完整周历共用1000条无TTL displaycache；快照只存文字与外部身份，不保存图片URL、令牌、匹配结果。三端消费partial，网络错误保留旧展示，鉴权/权限/不支持不得保留伪成功。点击实时 extResolve，唯一精确匹配进既有详情；无匹配和多匹配明确提示，不按标题猜第一条。播放解析不变。

普通Emby不支持扩展时只影响该页。删除旧 ranking 采集与命令，Bangumi 日历仅保留已登录个人在看流程。公共榜单/日历由 MSG 提供；弹幕（含自定义多源）、个人同步、提醒和图片仍保留客户端原有逻辑。取消沿用宿主协程/CallDisplayAsync，页面与账号变化拒绝迟到结果。
