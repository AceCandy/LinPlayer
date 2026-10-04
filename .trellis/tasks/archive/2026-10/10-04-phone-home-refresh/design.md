# 边界与方案

行为位于手机 HomePage 的缓存、导航生命周期及 reload。使用现有 Navigation 生命周期和 Material3 PullToRefreshBox；合并续播与首屏请求为页面 RESUMED 生命周期协程，首次/显式刷新才重取全页，普通恢复只更新续播。请求并发，各块独立完成；离页取消，手动刷新指示器由主请求组收尾。按需栏目沿用 reload 批次。

修改 HomePage.kt、增加 PhoneHomeRefreshTest.kt，并更新 android 规范。已有 PhoneThemeTest 作为主题验证，不改 TV 夹具或服务端。

最终轮播方案：按首页库序遍历 latest 缓存，去重最多5条；缺缓存时一次补一个所需库，合并进现有 requested 调度，列表共用查询结果。空/失败库继续后面的库，所有库完成且没有结果时隐藏轮播。维持原 Backdrop/Logo 呈现，不增加随机接口、不修改核心随机命令（其它端仍用）。
