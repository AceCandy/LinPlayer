# 设计与边界

删除LibraryPage SORTS年份单项，更新对应测试/设计规范；服务端已修复的ProductionYear能力保留兼容其它客户端。
性能链路：LibraryPage limit120 -> core Items透明HTTP -> MediaStationGo Items不计数时121项前瞻 -> metadataWorkPage/seriesWorkPage/movieLibraryItems候选资格与页内资料。优先捕获SortName/PremiereDate实际候选、批次、计数与payload计划；按证据选择最小等价优化，保护缺标题文件回退与上映日期年/文件时间/ID并列次序。
基线MediaStationGo ca18bee，存在上一轮6个未提交路径。库类型未知先覆盖普通电影和剧集路径，不读取生产隐私数据，不改生产库。
