# 设计

- 核心 AppConfig 保存 aggregate_primary_server，账号列表暴露 aggregate_primary；专用设置命令原子保存且失败回滚，删除账号清除选择。
- 手机 AppState 从账号表持有主服标识，扩展请求显式传 server_id，沿既有 sessionFrom 和 displaycache 键隔离线路/用户。展示条目保留请求来源，点击实时匹配后切到所属服进入详情。
- 聚合命令增加可选 counts_only（旧调用默认完整读取）、counts_error；UI 字段级保留旧统计/续播。聚合持久快照合并统计刷新，失败不抹旧值；缓存读取恢复有效线路，线路 URL 不落快照。
- 图片使用来源服线路及统一 /img；第三方图片不转发 Emby 凭据。
- 页面复用 PullToRefreshBox、LpLoading、Panel、LpRow 空标题；首次居中大动画、热刷新不插行。
- 手机通过 include_disabled=true 取得完整目录，旧调用仍仅返回开启项；榜单保留 source/group/enabled，体系和榜单两级切换；未开启显示原因。日历不修改上游契约，只明确未知时刻。
- 配置新增字段可回滚，旧宿主忽略可选返回字段；默认完整聚合参数不变。新命令更新命令表并生成绑定。
