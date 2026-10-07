# Android 手机与 TV

## 入口与主题

- LinPlayerApp 初始化进程级核心；MainActivity 只按形态选择 PhoneRoot / TvRoot，Activity 重建不能重复初始化核心。
- 手机主题在 ui/theme/Theme.kt，由 LpTheme 提供 Lp.colors；R / Sp / Dim 使用当前源码刻度，不用桌面刻度。
- 手机共用组件在 ui/components；TV 采用 tv/kit 的 TvC / TvSp / TvR / TvW。
- 手机服务器探测结果按 `server` 匹配账号、`ok` 转为卡片状态 `ok` / `down`；渲染端不可再按 `up` 判成功。缺少探测结果表示未检测，与连接失败区分；`PhoneManagementUiTest` 核验成功色及三态文字。
- 手机主导航是 Navigation Compose / Route；TV 使用 TvNav，换集与导航轨切页遵守既有返回栈和焦点记忆。
- 不使用 Material You 动态取色替换既定配色。浅色正文、次级与弱提示文字在 bg 和 s1/s2/s3 合成底上至少 4.5:1，由 PhoneThemeTest.lightTextContrast 断言；实际组件与叠图仍需渲染核查。
- 深色 → 浅色 → 深色要检查背景、说明、箭头、数值和选中项；设备强制深色的表现不能只用 JVM 截图下结论。
- PhoneRoot 始终铺当前主题 bg，壁纸在其上绘制；不能因配置了壁纸而让根背景透明。标准 Material 表面色先将 s1/s2/s3 合成到 bg；共用面板与按钮使用合成后的实色主题表面，不加玻璃高光；首页服名不加底色。
- 浅色文字叠图时必须在文字区铺浅色渐变底，不能沿用固定黑遮罩；浅色主按钮各个渐变停色都要满足文字对比度。系统栏跟应用主题同步，API 24/25 的白色导航图标保留深色底。

## 资源与出包

- Android Emby 播放模式为 `mpv` / `exo` / `auto`，由共用 `PlayerController` 解析实际内核，不能将 `auto` 直接传给核心。存量配置和默认 MPV 保持；显式路由优先，设置变化只影响下次播放。自动先 Media3，仅明确解码/格式不支持时回退 MPV 一次；网络、鉴权、DRM、资源被抢占、损坏内容和未知错误不自动换核。自动模式长按播放明确用 MPV。
- 回退捕获实际版本、续播、暂停、倍率、音量及选轨/字幕关闭，先停输出并等待 `player.stopPlayback` 完成，再切内核；Compose 释放旧 Media3，手机前台服务重绑新内核。零秒续播用已有 `from_start=true`。状态监听只在当前起播成功后消费；跨内核轨道按标题/语言等身份匹配，不复用运行期 ID。非 Emby 来源保持现有 MPV 路径。`PlayerControllerTest`、`PlayerEngineLifecycleTest`、`PhoneEngineSettingsTest` 与 `TvFocusTest` 覆盖这些契约，真机仍需独立验收。
- 用户手动选轨立即清除该类待恢复身份，晚到的旧轨不得覆盖新选择。TV 换目标先停 Media3 输出；离页通过控制器立即登记收尾任务，后续手机/TV 起播等待整次收尾（含 TV 播放标题清理），不能让旧 stop 停掉新会话。
- 手机回退与 TV 轨表恢复窗口从 `controller.ready` 起播成功后计时，effect 的 key 与放行条件使用同一不可变快照；等待旧 stop 或慢取流时不得查询旧轨表、消费轮询次数或应用详情选轨。`PhoneEnginePlaybackTest` / `TvTrackTimingTest` 挂起起播超过 11.2 秒后再释放，断言成功后仍按身份/ff_index 恢复一次。
- 手机版本/选集面板通过页面目标回调起播，不能直接调用 `player.play` 绕过 Media3 加载与控制器。同页换目标显式等待一次 stop；离页收尾按页面存续登记，不能按 itemId 重复停止。`PhoneEnginePlaybackTest` 断言初次、换版本、切集各一次 play，后两次各一次 stop，且沿用实际内核。
- 起播依赖异步读取的偏好时，`LaunchedEffect` 的 key 和放行条件使用同一不可变快照，例如 `val playbackPrefs = trackPrefs`；不能以旧 null key 启动协程、再读取已经更新的可变状态，否则重组前后都会起播。等待收尾后检查协程仍有效，再提交播放命令。
- TV 刷新率归播放页窗口拥有：只用同当前物理分辨率的 supportedModes，按源帧率整倍频匹配（0.01 Hz 容差，区分 23.976/24 等）；保留已请求/当前匹配模式，不反复写相同偏好。切集/换版本/回退只重启采样，离页恢复入页 preferredDisplayModeId，不改其它窗口属性。无匹配或持续未知帧率恢复原偏好，系统忽略请求不影响播放。
- TV Media3 在 STATE_READY 后读取当前格式/选中视频轨帧率；窗口管理期间关闭 Surface 帧率策略，离开后仅对未释放实例恢复。MPV 帧率来自 player.opts 的 container-fps 字符串；mpvGet 的实际返回为 {name,value} 对象。lpinterp 补帧滤镜启用或滤镜读取失败时保留原显示偏好，不能按源帧率降频或擅自关补帧。采样命令返回后检查取消和 ready，关闭的窗口拥有者拒绝迟到写入。回归见 TvRefreshRateTest / TvRefreshRatePlaybackTest，实际 HDMI 切屏与黑屏仍需真机验证。

- API 分层主题属性同时检查 values-vXX 和 values-night-vXX；night 资源优先级可能遮蔽版本资源。
- JNI 入口在 release R8 下必须保留。libmpv.so 不入仓，拉取后校验 ELF / ABI；不能把指针文本当运行库。
- release signingConfig 必须实际接入；签名材料在忽略的本地文件。最终 APK 统一交付路径见 [构建与交付](../shared/build-release.md)。

- 首页合集和各库最新按 LazyColumn 可见栏目触发，任务归页面作用域，离屏不重复取消；刷新恢复空栏目占位，账号变化/离页取消失效任务。
- 手机首页不展示或请求「接下来看」。每次恢复 RESUMED 更新继续观看；首页采用紧凑服务器栏，媒体库 → 继续观看 → 各库最新 → 合集，不展示大轮播、不调用随机推荐；库入口及最新结果复用页面缓存，首次/显式下拉/失效通知才重取。刷新主请求组结束、失败或取消后必须收起指示器。

依据：[入口](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/MainActivity.kt)、[手机主题](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/theme/Theme.kt)、[TV 主题](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/tv/kit/TvTheme.kt)、[主题回归](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneThemeTest.kt)、[安卓历史](../../../docs/lessons/android.md)。

- 首页栏目保留左侧竖杠；有更多入口时点标题导航，不另画「更多」。首页媒体库首行只展示Fit封面，has_primary=false不请求图片并居中显示库名；有图时加载/失败也显示库名，成功后隐藏；不能恢复封面下重复库名。首页设置无圈，搜索位于悬浮栏聚合左侧，作为普通页面压入原Tab栈。

- 手机登录后浏览页统一显示悬浮导航，播放/登录/添加服务器除外；纵向用户滚动累计24dp切换显隐并滑移淡入淡出，横滑不触发，换页恢复。底部留白固定，当前Tab在二级页点击返回根页。搜索不重复压栈，库页全局搜索保留viewId，二级页不另放导航搜索按钮。库页排序入口在库名同一行最右侧：无底色递减横线图标、当前排序和方向箭头；点击打开排序/升降序及条件面板，默认无筛选行，已选条件才显示清除条。方向按库保留并进入filterKey与请求；切换排序取消旧分页任务。
- 收藏总览无排序；电影/剧分类页共用媒体库的 `posterColumns()`、网格刻度与 `MediaSortControl`，不能另用自适应列数。分类排序仅重排已加载条目，通过 `grid.requestScrollToItem(0)` 在下一次测量回到首项，避免按旧海报 key 恢复锚点；不触发收藏查询、不重置原始分页游标，新页按当前规则合并排序。`Item.from` 保留核心已有 `sort_name` / `date_updated`，缺值在升降序均沉底。已有匹配项后点击加载更多，避免重排触发网络；回归见 `FavoriteLocalSortTest` / `PhoneBrowseUiTest`。
- `MediaSortControl` 在顶部栏内使用对称垂直留白；不能带入列表的单侧 bottom padding。几何回归比较可见标题与排序文字的纵向中心，而不是只比较外层点击区域。
- 手机收藏总览按 `library_ids` 匹配 Views 库 ID，使用实际库名与 Views 顺序，空库不画；多库收藏在各库分别展示，栏目/缓存 key 使用库 ID。点击标题进入库内收藏网格，仍使用收藏接口与原分页游标、本地排序。未匹配任何已知库的条目保留原类型栏目，库信息失败不能整页失败或丢项；库缓存绑定服务器/用户，旧接口不额外请求 Views，分类不依赖 library_type。

- 手机搜索/收藏总览与分类页使用已有 `PullToRefreshBox` 下拉刷新，空态与失败态提供可滚动容器。收藏刷新成功才替换数据与原始分页游标，失败保留已有内容；取消/失败/成功均结束指示器。搜索保留当前关键词及 parent_id，下拉按当前模式重查。搜索只传 Series/Movie，移除包括集与聚合筛选；搜索框内右侧叠层图标切换聚合，开启时高亮且具有 selected 语义，库内范围隐藏图标。聚合开启时提交已有关键词，后续由键盘搜索键或下拉触发，页面作用域处理 partial 并隔离旧运行。

- 手机搜索无独立标题/返回顶栏，状态栏安全区与底部悬浮栏留白仍保留；使用 LpField.trailingIcon 内置聚合图标、onSearch 提供键盘搜索动作；结果区与输入框相隔 Sp.x16。聚合栏目只显示来源名与横滑海报，不展示标识查询 warning 或数量后缀，真实请求失败仍显示。

- 手机搜索是临时导航动作：`switchTab` 在保存离开Tab栈前弹出当前Search，避免restoreState恢复成搜索页；系统返回仍恢复原浏览页，非搜索的二级页与滚动状态仍保留。导航回归须覆盖“聚合→搜索→其它Tab→聚合”，仅断言Tab选中不能证明显示目标内容。悬浮栏聚合视界和框内聚合开关统一使用LpIcons.layers。

- 手机详情的当前集定位在横向 `LazyListState` 中完成，当前 ID 出现在已加载分集后滚动一次；定位标记与列表状态需可保存，避免补页/纵向离屏返回强行复位，切季按季 ID 隔离。`PhoneDetailOptionsTest` 验证第8集详情打开后第8集和「当前集」可见，不能仅断言选中标记存在。播放选项使用小播放按钮上方的纵向紧凑描边按钮，无字幕隐藏字幕、实际账号线路少于两条隐藏线路；标题下日期与总时长只显示一次，时长保留到秒、无日期回落年份；顶部透明图标保留触摸区域，单集隐藏收藏，右上角直接提供下载。播放按钮集成续播剩余秒级时长与底色进度，单集头图不重复显示进度条；右侧箭头复用 `LpMenu` 浮出仅「从头开始播放」，不能恢复居中弹窗。单集剧名大标题和「SxEy：集名」排列；选集标题「来自第 X 季」点击浮出季菜单，替代季芯片栏，保留原同季重试和迟到响应隔离。

- 手机详情简介直接显示三行正文，点击展开/收起，无重复标题/底卡；媒体信息随选中版本更新，文件摘要位于等高横滑轨道卡上方。使用内容高度和换行适配大字号，不能固定高度截断参数；`PhoneDetailOptionsTest` 覆盖展开、横滑和深浅主题的 1.3 倍字号渲染。可选字段与路径展示契约见 [JSON 边界](type-safety.md#媒体信息可选字段)。

- 手机横屏播放器采用顶部剧名、中央快退/暂停/快进、左侧截图/锁屏、右侧倍速和底部单集标题/普通进度条/弹幕、音轨、字幕入口布局；竖屏保留既有布局。叠图控件使用固定白色前景，不能随浅色主题变黑。面板打开隐藏中间和左右控件；底部实际测量高度传入面板让位，不能固定92dp覆盖大字号/插件入口。`PhonePlayerOsdTest` 覆盖深浅主题、横竖屏、640×360及1.3倍字号下按钮不重叠与操作分派。

- 手机详情未续播按钮使用紧凑宽度并居中排列图标文字，单集简介位于播放操作之后、选集之前。季号从 `SeasonInfo.index_no` 读取，显示「第X季：自定义名称」，默认季名不重复；分集卡时长/剩余时间只在封面左下展示，封面下不再显示未看/还剩状态行。演员接入详情已有 `people[].role`，缺失/空白角色不画。`PhoneDetailOptionsTest` 覆盖真实季响应字段、默认/自定义季名、简介顺序和角色显示。
- 手机播放器移除选集快捷按钮，横屏右下仅弹幕、音轨、字幕；音轨用音符，扬声器仍用于音量反馈。轨道语言复用 `langCn`，中文名称作为标题回落或副标题，不重复堆叠代码徽标；保留字幕关闭与真实切轨命令。`PhonePlayerPanelTest` 覆盖语言名称、未知音轨隐藏及选中请求。
- 手机播放选集复用 `AppState.seasonEpisodes(parentId, loaded, onPage)` 逐页展示，失败保留列表并按已加载条数续取；当前集到达后只定位一次，补页不强制复位手动滚动。轨道读取失败显示错误和重试，与成功空结果区分；请求和临时状态按面板、条目及实际 Media3 实例隔离，取消不能转成空结果。`PhonePlayerPanelTest` 覆盖第80集定位、短页续取、末集可选、失败页续取及旧轨道失败晚到；选集仍交页面回调起播。
