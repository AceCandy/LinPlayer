# 设计

复用首页 Material3 PullToRefreshBox，收藏接已有 reload 与原分页 fetch，刷新失败保留内容。搜索去除 includeEpisodes 状态，防抖查询使用关键词/模式/刷新序号作为 LaunchedEffect key 取消旧请求；refresh 序号进入触发键。聚合请求归页面作用域，取消与旧 partial 按运行号隔离，保留关键词快照。框右侧复用 LpIconButton 和已有 globe 图标，不改公共输入框组件。

修改 SearchPage、ListPages、PhoneBrowseUiTest 以及手机规范；保留上一轮未提交改动，不改服务端/Go/TV。

## 搜索布局追加

使用无顶栏的既有 LpImmersive，状态栏安全区及底栏留白仍保留。LpField 增加可选 trailingIcon 和键盘搜索回调，默认行为不变；聚合图标放输入框内部。开启聚合直接提交当前关键词，输入变化只清理过期结果，后续通过 IME Search/下拉提交。结果前使用 Sp.x16 间距，栏目仅显示来源名。

## 聚合数量追加

共享核心 aggPerSource 从8调整为50，复用既有 Emby Search 默认50条请求；同步TV上限文案。换源候选20条、并发与超时保持既有值。插件仅放宽已有首批返回结果截取上限，不追加分页请求。

## 搜索与Tab导航追加

搜索是临时压栈动作；switchTab保存离开Tab栈前先弹出当前Search，避免restoreState把搜索作为目标Tab内容恢复。保留其它二级页和滚动位置，点当前Tab仍回根页。LpIcons新增同线性族layers路径，仅底栏聚合与搜索开关使用；搜索提示不再称为地球图标。

## 聚合总览加载追加

Overview的Counts/Resume原先直接使用命令ctx，无总时限。每服建立共用子context，复用aggregate包既有perServerTimeout=20s，两个请求并发且均受总时限约束；统计失败继续保留已有降级语义，Resume超时返回中文可见错误。手机仍使用一次性最终表与既有刷新/失败显示，不改命令签名、接口或服务端。MediaStationGo基准提交b6ad63a且工作树干净，现有Counts/Resume路由已核查；本轮不改变HTTP参数。
