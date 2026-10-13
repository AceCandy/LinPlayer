# Android 手机与 TV

## 入口与主题

- LinPlayerApp 初始化进程级核心；MainActivity 只按形态选择 PhoneRoot / TvRoot，Activity 重建不能重复初始化核心。
- 手机主题在 ui/theme/Theme.kt，由 LpTheme 提供 Lp.colors；R / Sp / Dim 使用当前源码刻度，不用桌面刻度。
- 手机共用组件在 ui/components；TV 采用 tv/kit 的 TvC / TvSp / TvR / TvW。
- 手机服务器探测结果按 `server` 匹配账号、`ok` 转为卡片状态 `ok` / `down`；渲染端不可再按 `up` 判成功。缺少探测结果表示未检测，与连接失败区分；`PhoneManagementUiTest` 核验成功色及三态文字。
- 手机主导航是 Navigation Compose / Route；TV 使用 TvNav，换集与导航轨切页遵守既有返回栈和焦点记忆。
- 默认橙金保留影院底色；本机外观设置可选蓝/绿/紫/Monet，深浅独立。Monet只在API31起使用Material3动态中性色与强调色，旧版本明确回退橙金；mediaAccent/mediaOnAccent为acc/accFg别名，避免选中态出现第二套蓝色。浅色正文、次级与弱提示文字在 bg 和 s1/s2/s3 合成底上至少 4.5:1，由 PhoneThemeTest.lightTextContrast 断言；实际组件与叠图仍需渲染核查。
- 深色 → 浅色 → 深色要检查背景、说明、箭头、数值和选中项；设备强制深色的表现不能只用 JVM 截图下结论。
- PhoneRoot 始终铺当前主题 bg，壁纸在其上绘制；不能因配置了壁纸而让根背景透明。标准 Material 表面色先将 s1/s2/s3 合成到 bg；共用面板与按钮使用合成后的实色主题表面，不加玻璃高光；首页服名不加底色。
- 浅色文字叠图时必须在文字区铺浅色渐变底，不能沿用固定黑遮罩；浅色主按钮各个渐变停色都要满足文字对比度。系统栏跟应用主题同步，API 24/25 的白色导航图标保留深色底。

## 资源与出包

- Android Emby 播放模式为 `mpv` / `exo` / `auto`，由共用 `PlayerController` 解析实际内核，不能将 `auto` 直接传给核心。存量配置和默认 MPV 保持；显式路由优先，设置变化只影响下次播放。自动先 Media3，仅明确解码/格式不支持时回退 MPV 一次；网络、鉴权、DRM、资源被抢占、损坏内容和未知错误不自动换核。自动模式长按播放明确用 MPV。
- 回退捕获实际版本、续播、暂停、倍率、音量及选轨/字幕关闭，先停输出并等待 `player.stopPlayback` 完成，再切内核；Compose 释放旧 Media3，手机前台服务重绑新内核。零秒续播用已有 `from_start=true`。状态监听只在当前起播成功后消费；跨内核轨道按标题/语言等身份匹配，不复用运行期 ID。非 Emby 来源保持现有 MPV 路径。`PlayerControllerTest`、`PlayerEngineLifecycleTest`、`PhoneEngineSettingsTest` 与 `TvFocusTest` 覆盖这些契约，真机仍需独立验收。
- 跨内核轨道恢复仅接受有效标题/语言的唯一匹配；两个内核的同类序号不能证明身份，重复标签或标题/语言均未知时不提交恢复选轨命令，保留新内核当前选择。歧义不消费 pending，后续唯一匹配仍可恢复；forced/SDH/位图未进入身份模型，不能声称完整语义匹配。`PlayerControllerTest` 覆盖歧义拒选、后续唯一恢复、手选音轨及字幕关闭。
- MPV恢复、详情初选和面板手选均经控制器的transportMutex等待命令回执，与seek/stop共用屏障；手选在排队前登记，详情初选晚到不得覆盖同类手选。不可撤回的选轨命令用NonCancellable等待，手机/TV点击UNDISPATCHED登记，面板关闭不丢已登记手选；离页/换片关闭提交门并推进代数，旧排队选择失效，停播和新起播等在途命令收尾。核心永久无回执仍持续等待，不用取消提前放锁。`PlayerControllerTest`、`PhonePlayerPanelTest`、`TvTrackTimingTest`覆盖交错、面板关闭和字幕/off键盘接线，不能替代真机。
- 用户手动选轨立即清除该类待恢复身份，晚到的旧轨不得覆盖新选择。TV 换目标先停 Media3 输出；离页通过控制器立即登记收尾任务，后续手机/TV 起播等待整次收尾（含 TV 播放标题清理），不能让旧 stop 停掉新会话。
- 手机回退与 TV 轨表恢复窗口从 `controller.ready` 起播成功后计时，effect 的 key 与放行条件使用同一不可变快照；等待旧 stop 或慢取流时不得查询旧轨表、消费轮询次数或应用详情选轨。`PhoneEnginePlaybackTest` / `TvTrackTimingTest` 挂起起播超过 11.2 秒后再释放，断言成功后仍按身份/ff_index 恢复一次。
- 手机版本/选集面板通过页面目标回调起播，不能直接调用 `player.play` 绕过 Media3 加载与控制器。同页换目标显式等待一次 stop；离页收尾按页面存续登记，不能按 itemId 重复停止。`PhoneEnginePlaybackTest` 断言初次、换版本、切集各一次 play，后两次各一次 stop，且沿用实际内核。
- 手机页面手动暂停先调用 `PlaybackService.onUserPause(true)` 清除短暂失焦的恢复意图，手动音量先调用 `onUserVolume()` 清除 duck 前旧值，再走原控制器；不能仅凭服务 500ms 状态轮询判断用户意图。未手动操作仍自动恢复播放/原音量。`PlaybackServiceTest` 覆盖上述分支与两内核通知控制；媒体会话跳转回归直接调用已登记回调，系统锁屏派发、来电及后台策略需真机验证。
- 手机同页换版本/选集先经控制器恢复服务保存的临时 duck 音量，再停止服务采样与旧会话；成功起播重绑并重新采样。服务用绑定代数隔离旧查询/排队控制，状态查询返回后检查取消与绑定，旧实例销毁仅清理自己仍拥有的绑定。同实例重新采样保留焦点恢复意图。`PhoneEnginePlaybackTest` 检查每次切换新增停止服务记录及 duck 后音量恢复；`PlaybackServiceTest` 给不可撤回的旧查询完成机会，核验不再上报与新绑定仍可控制。
- 起播依赖异步读取的偏好时，`LaunchedEffect` 的 key 和放行条件使用同一不可变快照，例如 `val playbackPrefs = trackPrefs`；不能以旧 null key 启动协程、再读取已经更新的可变状态，否则重组前后都会起播。等待收尾后检查协程仍有效，再提交播放命令。
- TV 刷新率归播放页窗口拥有：只用同当前物理分辨率的 supportedModes，按源帧率整倍频匹配（0.01 Hz 容差，区分 23.976/24 等）；保留已请求/当前匹配模式，不反复写相同偏好。切集/换版本/回退只重启采样，离页恢复入页 preferredDisplayModeId，不改其它窗口属性。无匹配或持续未知帧率恢复原偏好，系统忽略请求不影响播放。
- TV Media3 在 STATE_READY 后读取当前格式/选中视频轨帧率；窗口管理期间关闭 Surface 帧率策略，离开后仅对未释放实例恢复。MPV 帧率来自 player.opts 的 container-fps 字符串；mpvGet 的实际返回为 {name,value} 对象。lpinterp 补帧滤镜启用或滤镜读取失败时保留原显示偏好，不能按源帧率降频或擅自关补帧。采样命令返回后检查取消和 ready，关闭的窗口拥有者拒绝迟到写入。回归见 TvRefreshRateTest / TvRefreshRatePlaybackTest，实际 HDMI 切屏与黑屏仍需真机验证。

- Media3 诊断在 `rememberExoPlayer` 共用入口先于页面 load 注册，每个实例一个观察器；媒体 transition 重开阶段计时，首次 READY 与实际 `onRenderedFirstFrame` 分开记录 `phase` 和 `media_elapsed_ms`，起点不包含核心取流或点击前耗时。6 秒 READY 无视频首帧只记症状，不能自动换核，也不能用时钟推进当作呈现证据。
- 手机起播阶段诊断使用 `elapsedRealtime`；详情点击起点通过导航 entry 的弱引用表一次消费，不进入路由序列化或磁盘；恢复/其它入口明确 `origin=page`，同页换目标为 `target`。点击时间在等待 stop 前局部捕获，完成后与新目标一起提交；一次实际内核尝试一个数字 attempt。
- 手机 seek 阶段诊断在页面拥有的 `ObserveSeekTiming` 中可选挂接共用控制器；通知/媒体会话同样经控制器登记 request/submitted，页面与服务复用原有状态采样传入 paused/source，不增加核心查询。按 seek revision 隔离新旧请求，失败、取消、覆盖、begin/stop、后台和离页中断；15 秒诊断超时独立于 UI pending，不取消不可撤回命令或提前放开锁。
- `seek_target_observed` 仅表示提交后非缓冲、位置进入目标 ±1 秒；后续非暂停/非缓冲样本实际时钟累计推进至少 250ms 才记录 `seek_clock_advanced frame_verified=false`，不是目标画面呈现。暂停到目标只记位置；缓冲清推进基线。数值包含页面250ms/服务500ms采样等待与推进阈值，EOF附近未满足阈值可超时，不认定播放故障。日志仅阶段、数字attempt、内核、耗时、白名单采样来源/暂停状态，不含媒体/用户/地址；IO写现有本地Logs。TV未登记该可选诊断，默认observePosition参数保留原行为。回归见SeekTimingTest、PhoneEnginePlaybackTest和PlaybackServiceTest。
- `startup_request` / `request_complete` 记录核心请求前后；Media3 再记录 `load` 和真实 `first_frame`，提供 `request_ms` / `address_to_load_ms` / `load_to_frame_ms` 与 `total_ms`。MPV 请求包含解析与加载，不能当作纯取流耗时；`first_frame_supported=false`，不以 file-loaded/time-pos/Surface 绑定冒充首帧。偏好晚到创建播放器仅更换 listener，不关闭当前测量；加载前旧媒体错误不结束新测量。失败、后台、离页及页面主动 seek/暂停关闭测量，重复/迟到首帧不再上报。日志仅白名单枚举和数值，经页面协程 IO 写入；诊断不增加播放或网络请求。`StartupTimingTest` / `PhoneEnginePlaybackTest` 验证阶段接线、导航消费、换目标和回退，真机首帧耗时仍需实测。
- 缓冲诊断只在 RESUMED、想播放、无抑制、非近 EOF 时累计：前缓冲按倍率折算；数据不足与已有数据不动分别计时，连续 12 秒无 >=250ms 进展记录一次。暂停、生命周期事件、seek、倍率/类别变化和实际进展重置窗口；停止/错误结束本媒体诊断。日志只含阶段与数值，IO 线程写现有 Logs，离页撤播放器/生命周期监听并取消采样，不新增上报或控制动作。`PlaybackHealthMonitorTest` 与 `Media3DiagnosticsTest` 验证判定、真实 listener 接入及离页撤销；实际首帧渲染与低内存设备需真机验证。

- API 分层主题属性同时检查 values-vXX 和 values-night-vXX；night 资源优先级可能遮蔽版本资源。
- JNI 入口在 release R8 下必须保留。libmpv.so 不入仓，拉取后校验 ELF / ABI；不能把指针文本当运行库。
- release signingConfig 必须实际接入；签名材料在忽略的本地文件。最终 APK 统一交付路径见 [构建与交付](../shared/build-release.md)。

- 首页合集和各库最新按 LazyColumn 可见栏目触发，任务归页面作用域，离屏不重复取消；刷新保留原快照，账号变化/离页取消失效任务。
- 手机首页不展示或请求「接下来看」。每次恢复 RESUMED 更新继续观看；首页采用紧凑服务器栏，媒体库 → 继续观看 → 各库最新 → 合集，电影Hero复用首页已有作品、不调用随机推荐；库入口及最新结果复用页面缓存，重新进入/显式下拉/失效通知时后台重取。刷新主请求组结束、失败或取消后必须收起指示器。
- 手机聚合页快捷入口为下载、排行榜、日历、流影；先展示进程内总览快照，并以首页成功续播快照更新当前服（含空列表），不能覆盖其它服。进入/显式刷新/失效广播后台刷新总览，不另请求首页续播；刷新失败保留已有内容，换服/用户清理缓存并拒绝旧响应。回归见 `PhoneAggregateCacheTest`。
- 聚合新增流影二级页，顶部返回，右侧「卍解」开关替代标签栏，默认关闭显示普通流影、开启显示 18+；隐藏悬浮导航且不预留其高度，网页全宽填满顶栏下方至系统导航安全区的空间，返回聚合后恢复导航；网页按需加载、本页内保留位置，隐藏/后台暂停媒体，离页销毁，不暴露原生脚本桥。两地址从 `LP_LIUYING_URL` / `LP_LIUYING_ADULT_URL` 环境或本地配置注入，未配置明确提示。WebView 配置标准 WebChromeClient，自动播放策略固定允许；暂停前先记录正在播放的媒体，返回仅恢复该集合，不恢复用户已暂停或已移出页面的视频。网页脚本完成后才暂停原生 WebView，迟到回调必须检查当前状态与挂载关系；重复后台通知不得覆盖待恢复集合。回归见 `PhoneLiuyingTest`。
- 手机与 TV 日历不再要求赞助或订单，进入即加载；不得读取订单偏好或先查询赞助地址。上游登录、日历库匹配及提醒开关保留；手机回归见 `PhoneDiscoverUiTest`。

依据：[入口](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/MainActivity.kt)、[手机主题](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/theme/Theme.kt)、[TV 主题](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/tv/kit/TvTheme.kt)、[主题回归](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneThemeTest.kt)、[安卓历史](../../../docs/lessons/android.md)。

- 首页栏目保留左侧竖杠；有更多入口时点标题导航，不另画「更多」。首页媒体库首行只展示Fit封面，has_primary=false不请求图片并居中显示库名；有图时加载/失败也显示库名，成功后隐藏；不能恢复封面下重复库名。首页设置无圈，搜索位于悬浮栏聚合左侧，作为普通页面压入原Tab栈。

- 手机登录后浏览页统一显示悬浮导航，播放/流影/登录/添加服务器除外；纵向用户滚动累计24dp切换显隐并滑移淡入淡出，横滑不触发，换页恢复。底部留白固定，当前Tab在二级页点击返回根页。搜索不重复压栈，悬浮搜索始终进入全局搜索根页，二级页不另放导航搜索按钮。库页排序入口在库名同一行最右侧：无底色递减横线图标、当前排序和方向箭头；点击打开排序/升降序及条件面板，默认无筛选行，已选条件才显示清除条。方向按库保留并进入filterKey与请求；切换排序取消旧分页任务。
- 收藏总览无排序；电影/剧分类页共用媒体库的 `posterColumns()`、网格刻度与 `MediaSortControl`，不能另用自适应列数。分类排序仅重排已加载条目，通过 `grid.requestScrollToItem(0)` 在下一次测量回到首项，避免按旧海报 key 恢复锚点；不触发收藏查询、不重置原始分页游标，新页按当前规则合并排序。`Item.from` 保留核心已有 `sort_name` / `date_updated`，缺值在升降序均沉底。已有匹配项后点击加载更多，避免重排触发网络；回归见 `FavoriteLocalSortTest` / `PhoneBrowseUiTest`。
- `MediaSortControl` 在顶部栏内使用对称垂直留白；不能带入列表的单侧 bottom padding。几何回归比较可见标题与排序文字的纵向中心，而不是只比较外层点击区域。
- 手机收藏总览按 `library_ids` 匹配 Views 库 ID，使用实际库名与 Views 顺序，空库不画；多库收藏在各库分别展示，栏目/缓存 key 使用库 ID。点击标题进入库内收藏网格，仍使用收藏接口与原分页游标、本地排序。未匹配任何已知库的条目保留原类型栏目，库信息失败不能整页失败或丢项；库缓存绑定服务器/用户，旧接口不额外请求 Views，分类不依赖 library_type。

- 手机搜索/收藏总览与分类页使用已有 `PullToRefreshBox` 下拉刷新，空态与失败态提供可滚动容器。收藏刷新成功才替换数据与原始分页游标，失败保留已有内容；取消/失败/成功均结束指示器。搜索保留当前关键词及 parent_id，下拉按当前模式重查。搜索只传 Series/Movie，移除包括集与聚合筛选；搜索框内右侧叠层图标切换聚合，开启时高亮且具有 selected 语义，库内范围隐藏图标。聚合开启时提交已有关键词，后续由键盘搜索键或下拉触发，页面作用域处理 partial 并隔离旧运行。

- 手机搜索无独立标题/返回顶栏，状态栏安全区与底部悬浮栏留白仍保留；使用 LpField.trailingIcon 内置聚合图标、onSearch 提供键盘搜索动作；结果区与输入框相隔 Sp.x16。聚合栏目只显示来源名与横滑海报，不展示标识查询 warning 或数量后缀，真实请求失败仍显示。

- 手机搜索是临时导航动作：`switchTab` 在保存离开Tab栈前弹出当前Search，避免restoreState恢复成搜索页；系统返回仍恢复原浏览页，非搜索的二级页与滚动状态仍保留。导航回归须覆盖“聚合→搜索→其它Tab→聚合”，仅断言Tab选中不能证明显示目标内容。悬浮栏聚合视界和框内聚合开关统一使用LpIcons.layers。

- 手机详情的当前集定位在横向 `LazyListState` 中完成，当前 ID 出现在已加载分集后滚动一次；定位标记与列表状态需可保存，避免补页/纵向离屏返回强行复位，切季按季 ID 隔离。`PhoneDetailOptionsTest` 验证第8集详情打开后第8集和「当前集」可见，不能仅断言选中标记存在。播放选项使用小播放按钮上方的纵向紧凑描边按钮，无字幕隐藏字幕、实际账号线路少于两条隐藏线路；标题下日期与总时长只显示一次，时长保留到秒、无日期回落年份；顶部透明图标保留触摸区域，单集隐藏收藏，右上角直接提供下载。播放按钮集成续播剩余秒级时长与底色进度，单集头图不重复显示进度条；右侧箭头复用 `LpMenu` 浮出仅「从头开始播放」，不能恢复居中弹窗。单集剧名大标题和「SxEy：集名」排列；选集标题「来自第 X 季」点击浮出季菜单，替代季芯片栏，保留原同季重试和迟到响应隔离。

- 手机详情简介直接显示三行正文，点击展开/收起，无重复标题/底卡；媒体信息随选中版本更新，文件摘要位于等高横滑轨道卡上方。使用内容高度和换行适配大字号，不能固定高度截断参数；`PhoneDetailOptionsTest` 覆盖展开、横滑和深浅主题的 1.3 倍字号渲染。可选字段与路径展示契约见 [JSON 边界](type-safety.md#媒体信息可选字段)。

- 手机横屏播放器采用顶部剧名、中央快退/暂停/快进、左侧截图/锁屏、右侧倍速和底部单集标题/上下集/波浪进度条/弹幕、音轨、字幕入口布局；竖屏保留既有布局。叠图控件使用固定白色前景，不能随浅色主题变黑。面板打开隐藏中间和左右控件；底部实际测量高度传入面板让位，不能固定92dp覆盖大字号/插件入口。`PhonePlayerOsdTest` 覆盖深浅主题、横竖屏、640×360及1.3倍字号下按钮不重叠与操作分派。

- 手机详情未续播按钮使用紧凑宽度并居中排列图标文字，单集简介位于播放操作之后、选集之前。季号从 `SeasonInfo.index_no` 读取，显示「第X季：自定义名称」，默认季名不重复；分集卡时长/剩余时间只在封面左下展示，封面下不再显示未看/还剩状态行。演员接入详情已有 `people[].role`，缺失/空白角色不画。`PhoneDetailOptionsTest` 覆盖真实季响应字段、默认/自定义季名、简介顺序和角色显示。
- 手机播放器移除选集快捷按钮，横屏右下仅弹幕、音轨、字幕；音轨用音符，扬声器仍用于音量反馈。轨道语言复用 `langCn`，中文名称作为标题回落或副标题，不重复堆叠代码徽标；保留字幕关闭与真实切轨命令。`PhonePlayerPanelTest` 覆盖语言名称、未知音轨隐藏及选中请求。
- 手机播放选集复用 `AppState.seasonEpisodes(parentId, loaded, onPage)` 逐页展示，失败保留列表并按已加载条数续取；当前集到达后只定位一次，补页不强制复位手动滚动。轨道读取失败显示错误和重试，与成功空结果区分；请求和临时状态按面板、条目及实际 Media3 实例隔离，取消不能转成空结果。`PhonePlayerPanelTest` 覆盖第80集定位、短页续取、末集可选、失败页续取及旧轨道失败晚到；选集仍交页面回调起播。

- Android seek 的待跳转目标只属于 `PlayerController`，相对输入必须走 `seekBy(delta, actual, duration)`，不能以重组前捕获的 position 计算绝对目标。页面以 UNDISTPATCHED 提交目标；原生命令串行，排队仅最新请求能提交。提交返回不是 seek 完成；非缓冲实际位置在1秒容差内才释放，15秒超时/失败/取消清目标，250ms页面检查兜底。上报、弹幕、续播/回退快照始终取真实位置。MPV核心没有 seek 完成闩或 seeking 字段，不得据此虚构完成事件。
- 停止立即失效待跳转及排队请求，并等已发seek提交收尾；通知seek在提交锁内复查服务绑定代数，通知stop登记现有跨页面收尾屏障，旧停止完成不能关闭新绑定。Slider拖动值独立于轮询，按媒体与时长隔离，原生取消清值；横拖起点固定，TV预览从待跳转目标起步但不写真实 ui.position。回归覆盖 PlayerControllerTest、PhonePlayerOsdTest、PhoneEnginePlaybackTest、PlaybackServiceTest、TvRefreshRatePlaybackTest；精确落点和后台/锁屏派发仍需真机。

- 跨内核字幕身份除title/language外保留可空forced/bitmap；Media3仅FORCED位存在时确认true，缺位unknown。PGS/VOBSUB/DVBSUBS MIME是位图，SSA/SubRip/VTT是文本，其它unknown；MPV只对已核实codec hdmv_pgs_subtitle/dvd_subtitle/dvb_subtitle及ass/subrip/webvtt分类。不能使用MPV image（单张图片视频）或Media3 isText（包含位图）推断字幕类型，不能按标题或caption角色猜SDH。源属性已知时目标须已知且相等，未知不是false；仍需有效title/language和唯一候选，歧义保留手选。缺语义旧身份沿原唯一标签规则；回归见PlayerControllerTest。

- 跨集轨道记忆仅在本次Android播放页内有效，不写磁盘；scope为当前服务器/用户/真实Episode所属series_id，详情id必须等于当前item。换账号、换剧、电影/本地/未知上下文不沿用，离页释放。每页只保留当前剧一组成功手选；默认、详情自动初选、fallback恢复不反写。Media3采样点击Format，MPV用当前面板/轮询轨表采样点击ID，不用selected的晚到回调作为手选身份。
- 新集先恢复全局字幕开关，再应用同剧记忆；手选更新页面subOff，关闭是独立意图。恢复仍按严格title/language及known forced/bitmap唯一匹配，标题变化/缺轨/歧义维持默认；用户当前手选、TV详情显式预选、同片fallback优先。恢复通过已有transport屏障且每类只提交一次，手选/离页/换代拒绝旧排队任务。TV起播清旧ui.tracks，普通记忆只在新轨表稳定后恢复；无表仅可恢复off。详情请求前同步clearSeriesContext，取消请求不能撤销解绑；换页面memory也失效旧在途手选的记忆回写。SessionTrackMemoryTest及手机/TV真实面板换集回归覆盖本契约；TV真实设备暂缓。

- 手机横竖屏滑杆按已播放、浅色已缓冲、暗色未缓冲绘制，保留原生Slider拖动、取消与语义。Media3沿现有250ms采样读bufferedPosition；MPV仅控件实际可见且RESUMED时每秒查询player.status.buffered（demuxer-cache-time绝对前沿，不能再加播放位置）。隐藏/后台取消查询，重开清旧值，切媒体/版本与迟到响应按controller隔离。缺值、非正或非有限值不画缓冲段，超时长钳位；未知时长禁用滑杆。这是单个缓冲前沿，不是离散磁盘缓存范围或全片下载进度。PhoneEnginePlaybackTest和PhonePlayerOsdTest覆盖接线、生命周期、迟到响应、三段像素及拖动；真机/RTL手势/TalkBack另验。

## Android播放缓冲目标

- 核心prefs.buffer_target_bytes=0自动，自定义64～512MiB；与落盘prefetch_cache_bytes独立。容量只针对压缩媒体缓冲，解码/纹理/字幕等另占内存，不能承诺总内存硬上限。
- 手机/TV播放器设置沿既有控件，失败回滚，同字段保存串行；旧配置缺字段默认自动，命令拒绝非法类型、范围和小数字节。
- Media3 1.11.0构造时配置DefaultLoadControl目标容量与size优先，默认启动时间不改。生产页复用prefs.getPrefs，等偏好就绪才创建内核/起播；当前实例不为设置变化中断。自动保持原默认策略。
- Android MPV通过loadfile本片demuxer-max-bytes，自动不发送该覆盖选项，由内核结束恢复原mpv.conf有效值；本地播放同样走共用commandLoad。容量与续播start共用options槽位，旧/新语法兼容，桌面不应用该字段。
- 参数接受/设置回显不等于真实内存和网络收益；LoadControl实际加载判据、页面等待/接线、手机/TV设置回归与Linux MPV恢复探针是自动验证，Android真实媒体/低内存/TV真机另验。

## 手机缓存与Expressive外观

- UiPrefs.ui_font仅空/sans/serif，内置常规静态字体，旧文件路径回系统默认，不影响字幕；资源许可与SHA见docs/go-migration/BUNDLED-FONTS.md。手机壁纸入口与根绘制移除，TV/插件契约保留。详情背景通过有界Coil转换处理，不修改共享原图。
- Material3 1.4.0只使用公开API，形状token8/12/18/28dp和适度回弹保持影院配色；本批导航/图片/OSD/按压动画用lpTween/lpSpring，系统倍率变化由主题观察，0必须立即到位。
- PassiveProgress是底部3dp只读Canvas：实际position、有限正duration、RTL、safeDrawing底部，OSD/面板/PiP/退场/转屏等待隐藏，锁屏可见；不可接入seek或MPV buffered轮询。
- 手机存储页通过get/setPrefetchSettings.media_cache_bytes读取/保存全局媒体容量，省略其它字段不清空旧设置，保存失败回滚，加载失败显示重试。清理显示核心实际bytes结果。arm64手机字体资源预算20MiB、release上限80MiB，其它ABI仍60MiB。

- 手机按压弹簧不得只用pressed布尔状态证明短Tap可见：滚动容器可能在同帧发出Press/Release。共用pressFeedback消费交互事件，短Tap补可见缩放，Cancel仅复位，onClick不等待动画；海报长按和底栏selectable复用同源反馈且保留长按/Role.Tab/selected。验收至少包括滚动容器快速Tap的真实渲染、点击即时分派和连续点击，不能只测按住再松手。归一化缩放弹簧使用0.001可见阈值，避免小幅回弹过早结束；系统动画倍率0立即到终态。

- 详情图片预览以来源NavBackStackEntry与卡片独立token匹配目标entry；重复itemId不能作为唯一关联key。关联只驻内存，pop动画可见entry保留，出栈或切账号清理，不将图片授权URL写进路由或存储。非首页整卡入场rememberSaveable记忆，标题/角标与图片共用缩放/位移；`NetImage(onLoadResult)`通知成功/失败，MediaCard按item/URL隔离结果，占位始终可见，成功且15%可见才以0.94→1/20dp启动lpSpring（阈值0.001）；失败/无URL及零倍率正常几何。首批150ms内最多9张0/25/50ms短错峰；根NestedScrollConnection观察两轴consumed含fling，不消费滚动，快滚取消/跳过形变，静止150ms恢复。NetImage按URL隔离painter，普通图片含内存命中均按T8线性淡入，成功淡入期间以静态底色/来源预览托底，错误静态占位；同URL普通重组不重播。仅零倍率和实际点击关联的来源卡片以reveal=false直接显示，避免返回来源海报重新透明。验收需有来源图片中间帧像素、交叉淡入混色、Lazy销毁后回滚像素及零倍率，而非只断言最终可见。须覆盖真实MediaCard冷图等待超过原动画周期后才开始、spring过冲、换URL隔离、占位错误/无图、部分裁剪可见面积和真实Lazy fling；不能仅用就绪色块代替异步图片整卡联动。

- 当前普通详情不调用`sharedPoster`，仅保留来源图片预览；列表只有实际links指向的源卡片登记既有共享节点，空闲列表返回原Modifier。共享工具测试不能代替当前生产详情转场验收，也不能用常驻所有节点换取匹配。普通搜索使用三卡片懒行，含稳定行序号的key隔离重复ID；60结果首屏仅组合附近行，并验证末尾可滚动/点击、无额外搜索请求。

- 原生首页 `LpRow(homeAccount)` 对每张真实卡片应用 `homePosterEntrance(itemId,index,account,row)`，标准插件items也接同一modifier；媒体库入口和custom接管不套。按账号与卡片身份rememberSaveable保存seen，以裁剪后可见面积达到15%触发，snapshotFlow只观察阈值布尔。卡片从0.90缩放、向下22dp、0.86透明度，以lpSpring(.001f)上浮展开；缩放原点底边中点，相邻0/25/50ms短错峰；动画帧仅在graphicsLayer消费，不横移轨道、不等停滑或图片解码，已触发不因快滑/可见性变化停住。Lazy/导航返回不重播，换账号重置，零倍率立即正常；骨架轨道稳定，真实卡片到达后才入场。其它页面保留原posterEntrance规则。回归必须包括真实LpRow中相邻卡不同相位/向上扩大/水平中心不偏移、标题稳定、帧间不重组栏目、横纵向进入、冷图不阻塞/不重启、Lazy/导航返回与账号隔离、真实fling持续fast时完成、零倍率与点击/长按。参照风格判断须有连续帧或源码支持，不能把测试和出包当作真机流畅证据。
- 详情头部可以从导航内存PosterLink取来源Item做纯展示预览，但仅在真实detail为空时取标题/剧名/集号；不可把预览写入Block.Ok、交给插件或用预览seriesId生成可点击链接。无图来源也能预览标题，但不注册共享图节点；账号切换清空预览，重复itemId按目标entry及来源token隔离。NetImage背景模式400ms线性渐显，包括内存命中；普通列表热图按T8逐张渐显，不等待整批图片。来源预览直接复用原图，不为预览重复模糊；完成图仍按背景模式模糊，无预览的背景用静态底色。渐显连续数值只在绘制层读取，占位是否保留用derivedStateOf。验收挂起真实详情请求、无图来源、分集不可点击剧名、服务端字段覆盖预览、账号/重复ID隔离、冷热背景中间混色和零倍率。

- 详情仅白名单展示资料进入DetailCache，不存用户进度/收藏/已看/播放版本/凭据；嵌套people/studios也逐字段筛选。Application拥有唯一实例，AppState接入，磁盘IO在IO dispatcher且串行；清理时推进generation，旧请求不能写回。按server/userId/itemId散列隔离、最多1000条/每条128KiB，读写刷新LRU；坏文件回源。页面按entry和账号重建、网络回写前检查取消；内存初始化、磁盘与网络并行，后到旧磁盘不能覆盖实时详情。网络失败保留缓存可重试；实际错误码E_AUTH/E_NOTFOUND删除，不能猜成E_NOT_FOUND。收藏/已看只在live就绪后可操作，插件不读取缓存资料。
- 首页首次单卡入场使用lpTween(T9, CubicBezier(0,0,.2,1))，中心scale .5→1、alpha .4→1、translationX本卡宽度→0；至少15%可见触发，不等图像或停滑。首批错峰仅横轨静止且index0/offset<30px/index<12，min(index,4)×80ms；已横滑的新卡不排队。账号隔离与Lazy/导航返回不重播保留。持续横滑形变从各LazyRow.layoutInfo读取卡片裁切比例，只在graphicsLayer消费，不用每帧Compose状态驱动整排。首次seen只约束一次入场，不阻止已加载卡片随位置形变；零倍率同时关闭两者。回归先断言真实图片已显示，再检查同位置往返、完整离屏返回和零倍率，避免把图片加载或裁切宽度误认成缩放。

- 手机详情普通进入/返回退出使用T6从右侧整页滑入/向右滑出，来源页面在其下保持可见；所有详情统一页面滑动，头图保留来源预览和独立渐显，不另做小海报飞入。其它页面保留T8/T5/T6线性透明度、二级页10%位移和Tab轻缩放。参数为依据Yamby隔离运行观察的独立近似，未还原native普通回调，不与已确认的手势分支混用。FloatingTabTest在真实PhoneRoot中验证中间位置、来源图片可见像素、电影和剧集→分集返回几何与零倍率，不用测试NavHost替代生产接线。
- 手机详情评分、标签与标语只显示来源/缓存/真实资料的已知值，缺失不预留待收起的空行；真正变化仍以400ms线性淡入、300ms高度过渡加入，相同资料刷新不重播。冷正文等真实详情和关键区首次结果协调呈现：电影/单集为itemMedia及轨道/线路呈现基线，剧/季为季请求和首批分集，失败也放行对应错误；来源标题/图片和已知评分先展示，不等图片、相似推荐或季后页。首次正文以最终高度出现，不先画空播放区再逐栏挤开。插件、权限和起播真源仍只认live；未知网络等待不加人为延迟。PhoneDetailCacheTest覆盖分阶段释放请求、冷热、空值、错误、像素及零倍率，不代表真机性能验收。

- 手机详情itemMedia用Block<List<Version>>区分等待、失败和完成，不补造默认版本或空轨道。冷打开仅一处轻量LoadingState，已有本次运行展示快照时保留媒体选项/季/首批分集和统计，后台刷新不退回各区加载态。DetailPage借既有PageCache留最多16条账号/条目散列键的展示快照，每条最多200集，不落盘；账号切换/缓存generation/鉴权及不存在使其失效。新分集请求从offset0开始，不能用旧显示列表冒充已完成分页；真实首批替换，后页仍追加，失败保留已显示数据及独立重试。热刷新分页未完成时保留旧统计基线，不切回加载进度文案；已选季仍存在时保留，服务端移除后回落到真实首季。选择器/起播目标/版本ID和宽高比只用live结果，不能提交旧快照版本；真实变化、失败以及明确空结果仍呈现。媒体信息按实际版本过渡。PhoneDetailCacheTest覆盖热刷新不闪加载、首批从零、失败保留、清理、账号及离页迟到响应。

- 普通NetImage解码请求不等可见性，淡入则按裁剪后的boundsInWindow面积至少15%触发；完全离屏才复位，0–15%之间保留已触发状态，避免边缘往返闪图。可见标记以布局位置保留、不以URL重建，URL更换只隔离painter/fade；背景不门控，实际共享来源即使reveal=false仍观察几何，结束共享后不从未初始化的可见标记重播。零倍率忽略门控直接显示。回归须先在屏外完成解码，再滚入采样混色，并让同一已解码图完全离屏后重新进入；滚动后先完成实际draw再推进手动动画时钟，不能只推进Compose时钟却没有绘制隐藏帧。
- 手机媒体库每批30条，以累计实际条目数量作offset；不把短首批当完整库。冷加载先显示顶栏/筛选结构与正中所选加载动画，不铺整屏虚构海报/片名骨架；资料到达立即显示真实标题/年份/角标，单图静态底色渐显。已有同筛选keepState缓存按原条件复用，换筛选清旧数据，网络回写前检查取消。不混入旧筛选条目；首页和库首批的磁盘快照按本节浏览缓存契约复用。

- 搜索临时数据由NavBackStackEntry拥有的SearchPageState保留，按会话/来源隔离，出栈释放；成功/失败完成标记避免详情返回重搜，rememberLazyListState在条件分支外保存位置。取消回写前ensureActive，聚合partial归请求子作用域，换词重置完成代数。搜索海报复用菜单，跨服已看/收藏必须server_id，跨服下载不提供，插件使用source.setFavorite。所有手机/TV新增条目屏蔽菜单移除，库屏蔽与历史解除保留。海报Popup140dp，按左右/上下空间避让当前海报，lpSpring(.001f)居中回弹；极窄窗口无完整空位只能钳位，不能保证绝不遮挡。

- 整剧/季冷打开不先露出空选集标题和假播放目标，首批分集后一次呈现正文；单集媒体首屏不等待所属季/分集请求，已显示的选集区保留episodeStripHeight，图片独立渐显。切季仅清当前季数据，取消旧请求；加载/partial失败保留已到达卡片与局部重试，无季/空集明确提示。季标题只写“第X季/自定义名”，仅多季可点并显示箭头；缓存季关系未确认前不可操作。普通与1.3字号分阶段释放请求检查初次成形及完成后几何。插件、超长元数据及真实错误允许改变高度。

- 手机浏览快照由Application拥有唯一BrowseCache，按server/user/query散列隔离；白名单保留库名和卡片展示字段，不缓存地址/凭据/播放版本，最多1000条、每条512KiB，LRU/原子写与IO锁/generation清理。进度与已看字段仅是上次服务端显示快照，不能驱动播放续播、跨服合并或自动回写，实时请求仍是业务真源。读盘和网络并发，网络完成/切身份后拒绝旧缓存回调；离页取消请求，已完成响应的有界缓存写入归应用所有，不阻塞展示。设置清理同时清详情/浏览/页面留存。
- 首页请求前保留低对比静态封面并叠所选加载动画，无片名骨架或闪烁条；等待区沿用真实横轨高度，不能因缩成图标而提前触发屏外栏目请求。顶栏56dp、服名18sp、设置24dp且48dp命中区。本机外观hideHomeLibraries默认false，开启隐藏首行媒体库入口与继续观看标题，仍展示续播卡片与最新栏目。
- 库数量显示已累计条目数：未结束加“+ 部”，只有服务端总数已满足或空页才结束并去加号，短页不代表结束。后台刷新保留旧数据，按30条重拉此前已加载范围再整体替换，避免新首批拼接旧排序尾页；重试/条件变化取消旧分页。失败保留已显示条目和重试，鉴权/不存在错误清空展示。
- 悬浮栏首页/聚合/收藏无论当前页均回一级根页，不restore二级栈；搜索为全局一级搜索，普通返回仍回原页面。搜索不自动聚焦；电影/剧集/人物单选参与查询签名，人物点击用personItems作品弹窗，不能进入播放详情。空输入显示本机最近10词，去重按最近顺序，明确提交或打开结果时记录，不按防抖输入记录。
- 电影/剧集详情标题占整行，移除标题前独立小海报，仍用背景/标题预览与局部资料过渡；顶栏线性图标统一24dp、48dp点击范围。没有共享海报目标时使用普通详情转场，不能声称仍有海报飞入。
- 手机各详情头部最小高度为1.08×宽度，大字按内容增高；has_backdrop=false或Backdrop加载失败时用Primary海报，has_primary=false时不重复请求缺图；字段缺失兼容旧响应，真实资料前使用来源预览。PhoneDetailCacheTest验证明确无背景与实际背景失败的请求分支。
- 版本选项为整行浮动标题描边框，显示版本名及容器/大小；标题通过描边缺口融入取色背景，不铺不匹配色块。独立视频标签显示分辨率/编码，音轨按钮显示主信息/语言副行；字幕/线路独立按钮，仅可操作项带箭头。显示与起播版本规则、轨道偏好和居中选择弹窗保持既有契约。
- 手机播放规格不能只对齐框架而删掉字段：视频保留video_range_type/range及真实Dolby profile/codec识别，不从文件名猜HDR；音轨/字幕主行优先display_title规格、副行保留title真名，缺名称才拼已有语言/编码/声道/码率，fmtRate仍按现有契约使用。Stream.label与选择弹窗保持真名优先，展示不改变轨道选择。关闭字幕不保留旧副标题，default标记不重复追加；未标注/und/空值不冒充实际名称。正文常规字重、次行13sp，全文换行，不用maxLines截掉参数；PhoneDetailOptionsTest须覆盖完整DV/音轨/字幕、缺标题回退、关闭后文案消失及200%字体hasVisualOverflow=false。
- 首位后台刷新若新列表首ID变化，起点且未滚动时requestScrollToItem(0)，避免Compose稳定key让新项藏在旧首项上方；中段保留key锚定，相同首项刷新不归零。收藏按过滤/本地排序后的展示首ID判断，不能按原始响应无条件归零。PhonePosterMotionTest/PhoneLibraryUiTest/PhoneBrowseUiTest须断言海报起点坐标及中段/相同资料位置，不仅assertIsDisplayed。

## 浏览响应快照契约

1. **范围**：Android手机首页views/resume/latest/collections与媒体库首批；新增存储仅用于先展示、后台刷新。
2. **签名**：`AppState.browseBlock(command, args, onCached): Block<JsonElement>`；`BrowseCache.key(server,userId,query)`、`load/put/remove(key,epoch)`；不改变核心命令或服务端字段。
3. **契约**：键含完整查询，文件名SHA256；响应只取Item/View展示字段和page.items/total，剥除地址/凭据/版本；1000条×512KiB预算。读盘和网络并行，当前身份一致且网络尚未完成才允许缓存回调；写入不阻塞首屏，清理generation与内存值检查拒绝旧磁盘写入。
4. **错误**：坏文件/超限→丢弃回源；写盘失败→保留内存与实时显示；普通网络失败→保留已显示快照及库页重试；E_AUTH/E_NOTFOUND→删除对应缓存并清空受影响展示；取消/切会话→不回写UI。
5. **用例**：正常重启先显示同账号同筛选快照；首次无缓存先显示结构与刷新；切筛选不显示旧结果，短页继续；错误示例是磁盘晚到覆盖新响应或按已加载页数假称总数。
6. **回归**：BrowseCacheTest验证白名单、重启、身份/查询隔离、有界淘汰、坏文件和generation；PhoneLibraryUiTest验证挂起网络期间已显示磁盘数据、失败保留海报、每批30/累计offset/短页/结束数量；禁用缓存展示回调时此用例必须红。PhoneHomeRefreshTest验证可见栏目和隐藏开关；PhoneBrowseUiTest验证焦点/类型/历史/人物作品；FloatingTabTest验证一级落点。
7. **正反例**：错误为`items = freshFirst + oldTail`或用缓存resume向服务端回写；正确为保留旧快照，30条逐页刷新原加载范围再整体替换，播放续播仍向核心/服务端实时获取。

- 手机原生无定量等待统一使用外观所选动画，入口为LpLoading：页面、列表/搜索、下拉刷新、按钮、面板/弹窗、启动和播放器打开/缓冲共用样式。外观提供Material、Style1–5、Style7七款预览单选，ID为0/1/2/3/4/5/7，默认Material，loading_style本机持久化、即时生效，非法旧值回落0。Material/Style4跟随主题色，其余保留原色；动画本地解析并缓存，至少STARTED且等待仍存在才播放，后台/离组合停止，恢复前台仍等待才继续，系统零倍率保留有效静态图形。按钮保留尺寸并禁用重复点击，BlockBox默认120dp反馈区；已有内容、状态文字、失败及重试保留。真实百分比、图片占位、网页内部、TV和插件主动绘制保持各自语义；启动静态轮廓只叠一份所选动画。
- Material LoadingIndicator的原生窗口可见性回调也会启动动画；AndroidView必须随宿主STARTED控制可见性，attach回调完成后按最新播放条件同步drawable，detach/释放停止。回归分别覆盖Material与Lottie的真实帧推进、CREATED首次挂载、后台停止/前台恢复、系统零倍率和离组合；不能由测试手动启动drawable冒充生产接线，Material测试见MaterialLoadingLifecycleTest。

- 手机文字由 `LpText` / 完整M3 Typography定义字号、行高、字重、字族和零中文字符间距，规格见 UI_MOBILE.md §1.2。显式TextStyle必须取当前主题字族，不依赖LocalTextStyle隐式合并。顶栏heightIn允许双行200%增高；详情背景与正文布局分开，分集头图比例只作最小占位。数字tnum须在三字族实际测宽，不能只断言属性；宋体实测00:00与11:11差4px，动态数字统一采用系统Monospace。封面10/13sp时长与排名装饰数字保留专用规格。

- 整剧入口布局分支只认Series：真实评分/年份与成功季请求的季数，宽播放键和独立实际待播集说明；简介置于选集前，扩大分集卡但不影响单集/Season卡片。季海报选当前季不重复请求，明确无Primary不取图，等待/真实轨道保留同高；复用现有换季和分页取消契约，不新增逐集详情请求。共用头图64%开始渐隐，标题底用主题bg衔接取色背景；保留预览、模糊、渐显及图片失败回退。

- 版本正文和选择弹窗允许完整名称换行，不用两行省略；Version.displayName只用于展示，真实Name优先，空名或明确泛称才回退安全路径文件名，地址凭据/查询/片段不可展示；无名称和文件名则明确未提供，不影响preferred或提交的版本ID。OptRow仅版本菜单放开labelMaxLines，其他调用保持默认。

- 首页Hero按UI_MOBILE.md当前电影Hero规格，候选来自当前账号首页及既有浏览结果的展示池，图片仅走核心缓存专用通道，Backdrop优先/Primary与Logo失败回退。触摸/横滑重置5秒计时，400ms过渡；纵滚/后台/离屏/零动画倍率暂停。首次头插仅起点复位，账号隔离与取消保留。所有手机标准海报复用MediaCard的精简片名/年份（单集保留SxEy）、EpisodeStatusBadge与MediaRatingBadge，数据源共用PosterCaption和CardMenu；普通评分读取rating、豆瓣只认DoubanRating（沿用服务端/核心同名字段，浏览缓存白名单保留）；两种评分分别以★/豆标识，右下纵排迷你胶囊，缺失或无效分别隐藏，普通0分作为无评分隐藏，Series完成只认有效统计+零未看+Played，电影无数字。Dock仅四图标，键盘隐藏、系统导航区上12dp、200ms动画与24dp滚动阈值，搜索独立选中。HomeCinemaTest/HomeHeroTest/FloatingTabTest覆盖数据与真实组件；设备安全区/播放仍需真机。

- Hero背景可以越过其原始占位延伸到继续观看图片中点，按媒体库入口实测高度与共用栏目/图片刻度计算；显示媒体库时保留原几何；隐藏媒体库时将入口和续播标题高度并入Hero，标题、播放键和指示线下移，续播卡片及背景下沿保持原Y。背景不可拦截后续卡片点击。无续播则不延伸。PhoneColorSchemeTest/HomeHeroTest验证真实渐变溢出像素、控件几何、主题切换/持久化及API28回退；系统壁纸动态更新和设备体验仍需真机。

- 首页Hero候选按会话使用既有页面留存，返回前后不删除再插入首项；有效候选保留顺序，页码用导航保存状态并在候选缩减时校正（rememberSaveable恢复不会验证inputs）。媒体库与续播标题按实际测量高度计算背景延伸，默认隐藏时标题高度由同一TextStyle的TextMeasurer计算；首页采用A方案的全背景连续底部渐变，末端融入主题底色，不再仅最后15%渐隐或给标题区铺独立黑底。标题/说明及未滚动顶栏用白色前景与文字阴影，顶栏保护单独保留。候选必须实际调用cachedHomeImages，主图/回退/Logo仅/img-cache，未命中不回源；随机新增候选由首页安排，工具保留传入顺序。PhoneHomeRefreshTest验证实际中点/开关联动、部分滚动返回及缓存未命中不展示Hero；HomeHeroTest验证中段亮度与保存后缩减候选不会越界，工具单测不能替代真实页面接线。
- 详情首帧可从PosterLink的Item补评分/年份/类型/已知时长等展示字段，不含进度/收藏或可执行所属剧ID，仍优先live与DetailCache；鉴权/不存在错误拒绝此预览。Series路由ID已知时季请求独立并行，Season/Episode仍以真实详情所属关系为准。明确无Primary时可保留来源已显示海报；模糊目标与未模糊预览即使URL相同也不是同一图片模型，不能误去重。回归需验证挂起详情期间展示字段、一次季请求和实际海报像素，取色请求不能作为头图可见证据。

- Hero禁止在标题Column范围内单独铺深色遮罩，以免形成中部暗带。深色主题的全背景底部渐变需检查从35%到末端亮度不回升，且保留上方图像亮度；浅色主题末端仍衔接浅色页面底色，不套用深色单调变暗断言。渲染验收同时检查亮图长标题可读性、两侧连续性、继续观看位置和底部融入。

## 首页轮播候选与倒计时契约

1. **范围**：手机轮播纯展示候选；不修改核心进度、播放解析或服务器接口。旧孤立图片哈希无法恢复作品归属，只有已取得资料且实际命中缓存的作品可用。
2. **签名**：`BrowseCache.heroItems(server,userId,epoch)`、`recordHeroItems(server,userId,value,epoch)`、`heroRevision`；`AppState.cachedHomeImages(items,onBatch)`。池键为既有身份散列加`home.heroPool`。
3. **契约**：AppState仅登记当前账号已完成的浏览/搜索/详情/分集结果及setPlayed确认；同账号和generation校验，UNDISPATCHED先取得heroLock，合并/白名单/原子写在IO。池与其它浏览缓存共用1000条预算，池本身最多512KiB，按字节去掉最旧资料，不存URL/凭据/版本。稀疏已看更新保留未返回的资料。
4. **边界**：清理推进generation并通知轮播，旧写回拒绝；缺资料/被淘汰图片跳过，不回源。只对127.0.0.1的/img-cache发HEAD且禁止重定向；4并发、首批4项，其后每批24项，首批先展示，不全池预解码。单项IO中断跳过；本地服务拒绝连接停止探测，下次进入/刷新可重试。
5. **正常/基本/错误**：已缓存且未看作品正常进入；单项隐藏指示线；played或resume_secs>0同时排除条目及所属剧，已知Series优先于同剧分集、按作品去重。错误为同账号旧展示进度参与起播或跨账号复用候选。过滤仅认最近已取得的服务端快照，不额外联网查询全库状态。
6. **回归**：BrowseCacheTest验证多页合并、重启/账号隔离、白名单、字节预算、稀疏状态更新和清理屏障；CachedHomeImagesTest验证35候选、背景优先、排重/观看过滤、单项中断及只读路由；HomeHeroTest验证两轮无重复/相邻无重复、最多6条可见指示线、5秒填充及生命周期；PhoneHomeRefreshTest验证生产页池接线、初始隐藏及切换几何。真机观感/大池扫描耗时独立验收。
7. **正确方式**：保留返回时有效候选与当前ID，轮播整轮结束再洗牌且避开上一张；当前指示线按物理帧时钟5秒左到右填满，与切换共用时钟，绘制层读取进度。追加候选不重置计时；触摸/横滑后重计，后台/纵滚/离屏/零倍率暂停。错误方式为每次重组随机换首图、将每帧进度提升到整页状态读取或为所有候选绘制一条指示线。

## 全端展示快照契约

遵守[共享展示快照规则](../backend/quality-guidelines.md#全端展示快照契约)：共享核心无TTL、1000条预算，展示显式接收partial，最终result实时；详情旧资料只读预览，主播放目标不取缓存。各端展示入口按身份/生命周期交付缓存并替换实时结果，分页按范围替换，失败保留展示但不伪装鉴权成功。

- 手机方向锁由PlayerPage页面持有，同页自动/手动切集及换版本不临时请求UNSPECIFIED；新目标未知尺寸保持现有方向，成功起播后只用实际版本尺寸直接改向。手动方向不被元数据覆盖，比例仍可更新；取消、旧controller/目标和leaving阻止迟到写回，真正离页释放。PhoneEnginePlaybackTest覆盖两内核与旧/新目标迟到响应，真实转屏与首帧另验。
