# 排序支持核验

MediaStationGo基线ca18bee084487ada81bbed89879cec035b609bb6，开工工作树干净。LinPlayer已有未提交多轮UI，全部保留。

|选项|SortBy|核验|
|---|---|---|
|更新日期|DateLastContentAdded|普通库维护的作品最新文件时间，支持升降序|
|上映日期|PremiereDate|上映日期主键，支持升降序|
|名称|SortName|与Name同义，支持升降序|
|年份|ProductionYear|电影特殊分支正确；普通metadata/Series原按上映日期回退，本轮明确year主键|
|评分|CommunityRating|普通库支持升降序|
|加入时间|DateCreated|普通库别名到DateLastContentAdded，合并，不展示重复入口|
|随机|Random|请求内种子固定，但各HTTP页重抽种子；可能重复漏项，暂不启用；若需要须先提供客户端保持的种子契约|
|最近观看|DatePlayed|仅续播筛选排序，普通库不启用|

来源：MediaStationGo internal/service/emby_items_helpers.go primarySupportedEmbySort/libraryWorkSortParams，emby_metadata_scope.go metadataOrderSQL/seriesOrderSQL，emby_movie_items.go movieLibraryItems，emby_compat.go Items randomSeed；LinPlayer core/emby/lists.go Items透明转发。

服务端优化：年份排序不再误走上映日期与文件创建日期LATERAL统计。回归给年份和上映日期相反的样本，覆盖movie/tv/mixed和两方向、第一页/尾页/总数，捕获SQL与实际PostgreSQL EXPLAIN。真实大库耗时尚未测，不宣称速度倍数、不盲加索引。

补查NFO：internal/repository/nfo_library_candidates.go NFOWorkCandidates已有root.release_date/year/rating；emby_nfo.go nfoLibraryItems原投影丢掉且回退title，现按需投影目标字段后排序，不改资格与hydration。红果作品无release/year，合集评分未定义，暂不扩展缺少数据的排序。
