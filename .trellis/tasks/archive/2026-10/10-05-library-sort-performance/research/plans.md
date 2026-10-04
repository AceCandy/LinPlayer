# 排序计划前后对照

MediaStationGo HEAD ca18bee084487ada81bbed89879cec035b609bb6；保留前轮工作树。隔离PostgreSQL15，合成4,000作品/80,000文件，首屏120，Ascending，不计总数；基准单独执行。普通movie与Series helper，非生产HTTP。

|路径/排序|候选SQL前→后|候选计划合计ms前→后|文件访问前→后|服务方法ms前→后|
|---|---|---|---|---|
|movie名称|3→1|63.931→22.109|0→0|150.982→114.639|
|movie上映日期|1→1|74.463→30.370|80000→0|142.572→102.659|
|Series名称|3→1|14.984→5.776|150→120|39.648→26.557|
|Series上映日期|1→1|184.094→52.361|84000→4000|189.856→61.365|

访问=(Actual Rows+Rows Removed by Filter)*Actual Loops，累计media基础表，包含资格。上映日期样本每作品不同release/year；大量缺失或并列数据仍必须计算原文件日期，不能把此收益外推。服务方法含选页+hydration，不含HTTP/图片/网络/客户端。各值单次观察，不是生产耗时保证；名称movie页内加载仍占较大部分。

红测试在旧代码下名称candidate_queries=3和上映日期media visits越界都失败；修改后两类均绿。较早临时日志service_ms除数写错的值已弃用，表格只使用修正/1000后的最终red/green日志。

最小实现：共享批次max(50,limit)，至少一页避免重复排候选，小页及不足补取仍保留；PremiereDate在限定作品候选内按release/year的COALESCE值分区计数，仅并列计算原scoped MAX文件时间，唯一key不会被文件次键改变顺序。名称只在NULL标题时计算MIN(scan_title)，去无用MAX文件日期投影。未增索引/缓存/依赖/接口。

混合电影库的日期排序使用合格集文件release/year降级表达式，不能替换成整剧字段；本轮没有改其独立排序查询。NFO/红果没有套用普通元数据日期优化，共享批次消费者保留行为回归。
