# 设计

服务器卡保持原手势容器及外边距，名称使用 weight 限宽，独立当前标签与右侧连通短文字；成功探测字段为 ok，修正卡片旧 up 分支。插件源不可用不冒充网络探测结果。
设置根页从大其它组拆为内容与数据、扩展与账号、应用，通用更名播放与外观。LpCell 增加默认关闭的 mediaStyle，只设置页显式启用，保留 onClick 最后位置；复用原行结构，图标底与开关/当前值采用现有 token。SegRow 当前调用者仅设置页，直接统一蓝色并允许文字换行，保持原回调。代理选项复用 MediaFilterChip。
修改范围为 ServersPage.kt、SettingsPage.kt、Base.kt、UI_MOBILE.md 及必要回归，不调整通用菜单/弹窗的全局外观。
