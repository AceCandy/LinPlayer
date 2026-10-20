# 设计

- 保留旧 emby.personItems 数组契约，新增 emby.personItemsPage(person_id,type,start_index,limit,is_adult)。type仅Movie/Series。
- 新结果 items、total(未知为null)、next_index、has_more。游标按服务器原始页推进，之后统一屏蔽过滤；短页不代表终点，已知总数或原始空页结束。
- 使用现有 GET Users/{user}/Items、PersonIds、Recursive、IncludeItemTypes、PremiereDate Descending、EnableTotalRecordCount=true；复用会话、取消、错误归类和displaycache。
- Android新增Route.Person及共用PersonPage；现有MediaCard、NetImage、LpScaffold及主题。按导航条目/会话隔离页面状态，保留已加载快照和网格位置。详情和作品并发，各自重试。
- 分类总数按相应实时分页返回，未加载分类不编造数字。缓存页不能推进实时游标；partial与final替换同一范围。
- 桌面沿用PersonPage及Card，添加分类/滚动分页/重试；页面取消及请求代次检查防止迟到写入，返回保留页面实例。
- 生成绑定从COMMANDS.md统一更新；旧命令兼容，无ABI破坏。
- 参考已批准build/people-proposal效果稿；Yamby已观察，Hills启动失败，不声称观察成功。
- MSG源码基准6002c53b6e344648a17a2997924636237dcbaab3；有既存未提交改动。人物作品支持PersonIds、分类和StartIndex/Limit/TotalRecordCount，实服联调尚缺。
