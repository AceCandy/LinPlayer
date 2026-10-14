# 设计

客户端复用 PullToRefreshBox 和 snapshotFlow，首屏与追加状态独立，榜单/主服/会话变化取消旧请求。按唯一来源 ID 去重；空页或无新增停止，失败保留已加载内容并提供重试。列表逐条 lazy item，底部 inset 放在 contentPadding。图片登记沿现有账号图片白名单生命周期，测试覆盖真实代理和账号重建。MSG HEAD 9716637f，扩展目录当前仅 TMDb/豆瓣、Bangumi 单独周历，无精确时间；不编造数据。


用户已授权修改 MSG。扩展榜单复用现有红果 4 榜、黄果 AI 3 榜和 Bangumi 每日放送，不新增评分榜采集。黄果目录、内容、图片和匹配遵守 IncludeNSFW。红果/黄果来源 ID 为最长 32 位字符串，匹配沿既有 Emby 身份和真实文件绑定。Bangumi 海报通过 MSG 同源鉴权代理。

日历时间使用公开 bangumi-data（https://github.com/bangumi-data/bangumi-data，CC BY 4.0）。每次周历刷新匿名获取一次，5 秒超时/16 MiB 上限；仅接受精确 Bangumi ID、P7D 周期、明确 RFC3339 时刻、与周历一致的日本星期。冲突或缺失保留未知，失败保留同作品同星期的旧时刻。broadcast_time=HH:mm、timezone=Asia/Tokyo、time_source=bangumi-data；客户端缓存和手机/TV 展示均传递，不推断集数或已播状态。
