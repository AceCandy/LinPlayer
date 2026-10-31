# Android 手机与 TV

- Media3 起播黑幕在真实 `onRenderedFirstFrame` 后即可撤除，包括暂停且位置未推进的首帧；该状态只用于呈现，不改 `everMoved`、失败或完播判据。按播放器、播放拥有者与重试代次隔离，媒体切换/IDLE清零，离页移除监听并拒绝已失效监听器回调，停止状态（IDLE）的首帧通知也不得撤幕；MPV保留自然推进和既有兜底。`StartupTimingTest`覆盖生命周期，手机/TV页面回归验证首帧即时撤幕与失败仍可见；冻结Compose时钟时，注入原生监听事件后需提交Snapshot通知再推进帧，不能将未刷新的测试界面误判为生产状态丢失。真机首帧耗时另验。

## 入口与主题

- TV 普通播放左上角系统时钟常驻，不能跟随 OSD 隐藏；局部暗底和白色数字确保亮画面对比，不占焦点。右上网速依实际播控可见性（排除菜单/失败/流影）展示，中央缓冲与角落不得重复；片头提示与网速按 Column 排列避让。时钟更新绑定 STARTED 生命周期并随离页取消。`TvPlayerLayoutTest` 覆盖显隐、时间更新、网速位置、片头避让、亮背景大字号与截图；系统时钟不同于进度时间码。

- TV 下一集卡出现时替代外层下一集按钮；后面仍有正片的“跳过片尾”卡不替代。仅被移除按钮的焦点转交卡片，其它控件保持焦点；进度向上必须指向仍挂载的按钮。提示变化不触发通用播控重新聚焦；专用恢复 effect 同时观察菜单开关，覆盖菜单期间跨入/离开提示区间后关闭菜单的焦点恢复。`TvFocusTest` 与 `TvPlayerLayoutTest` 覆盖替代/恢复、半隐确认、菜单往返和其余按钮/进度条位置不变。

- TV 主进度条的章节刻度需合并 `intro.end` / `outro.start` 并去重，不能假设片头片尾总在 `chapters` 内；移除上方区间线不等于移除边界节点或跳过功能。刻度收在轨道裁剪区内，保持1dp宽；普通/聚焦轨道3/5dp、滑块8/14dp，缓冲用中性色层次，已播用主题强调色。沿用未知时长/边缘过滤，`TvPlayerLayoutTest` 用无章节区间及重叠章节检查实际像素、去重和刻度不伸出轨道。

- TV 播控只在播放页使用底部纯图标动作，常态透明、焦点反色微放大；不修改浏览按钮的共享配色。季集/片名居左，下一集与倍率/弹幕/音轨/字幕/更多居右，共处进度条上方；时间分置进度条下，实际版本规格移入媒体信息。取消常驻播放/暂停按钮，画面确认暂停/继续；方向唤醒聚焦进度条，未知时长保留画面焦点；进度预览确认提交不暂停，工具上下显式导航，提示需聚焦确认。下一集不等于选集列表，移除列表入口不能一并删除下一集。上一集与插件面板在更多中，倍率直接入口返回恢复原按钮。进度宽度按实际窗口扣安全区计算，不能沿用固定 864dp。真实页验证见 `TvPlayerLayoutTest`、`TvFocusTest`。

- LinPlayerApp 初始化进程级核心；MainActivity 只按形态选择 PhoneRoot / TvRoot，Activity 重建不能重复初始化核心。TV发行包通过 `-Plp.tv=true` 生成 `BuildConfig.TV_BUILD`，启动优先此标记，再检测系统电视模式/自检入口；不得靠改名普通APK交付TV包。TV构建的核心插件平台为android_tv。Manifest同源TV_BUILD标记用于产物门禁；MainActivityShapeTest须分别用lp.tv=true/false运行，覆盖普通系统模式。
- 手机主题在 ui/theme/Theme.kt，由 LpTheme 提供 Lp.colors；R / Sp / Dim 使用当前源码刻度，不用桌面刻度。
- 手机与TV浏览页面共用 ui/components；TV顶部导航、扫码和播放器沿用 tv/kit。共享点击控件通过 LocalTvMode 提供遥控焦点与菜单键。
- 手机服务器探测结果按 `server` 匹配账号、`ok` 转为卡片状态 `ok` / `down`；渲染端不可再按 `up` 判成功。缺少探测结果表示未检测，与连接失败区分；`PhoneManagementUiTest` 核验成功色及三态文字。
- 手机普通首页的左上角始终是服务器选择入口，Emby 与插件数据源首页复用 `ServerChip` / `ServerMenu`；源内容加载、为空或失败不能阻塞账号菜单。选择其它账号走 `account.setActiveServer(server_id)` 后刷新会话，不压入新首页；切源清空旧搜索。分类/详情保留返回，Emby 隐私长按与退出语义、TV 顶栏不变。`PhoneHomeRefreshTest.sourceServerPicker*` 覆盖上述状态、源间/Emby切换、当前项不重复切服和二级页返回，避免首页替换后遗漏宿主导航。
- 切到插件源后旧 Emby 首页可能尚未离开组合；`homeHeroCandidates` 在无 Emby 会话时返回空列表，不发送需要 `server/user_id` 的候选查询。`CachedHomeImagesTest.switchingAwayFromEmbySkipsStaleHeroCandidateQuery` 覆盖会话清空后的命令与图片请求隔离；实际切服仍需设备验证，不能只断言菜单存在。
- 手机与TV浏览主导航共用 PhoneRoot/MainShell、Navigation Compose / Route。TvRoot 只提供TV宿主配置；TvNav 仅保留于TV播放器内部（换集 / 换版本栈），不再承载浏览入口；旧 TV 浏览页已删除。样式适配不得另写浏览请求与业务状态。
- TV 带导航页面使用72dp顶部栏，首页随滚动离屏；共享页外边距16dp、横轨内边距16dp,内容向下避让(边距由 `posterComposable` 挂在每一页上，不挂外层容器，切页动画里两页各带各的);首页有头图时例外：整页铺到顶栏下面，头图左右铺满到屏幕边缘、下方各栏仍留页边距，未滚动时顶栏透明叠在头图上(同手机)。TV 不挂下拉刷新(`LpPullRefresh`):焦点滚到顶的溢出量会拉出指示器卡住，并吞掉之后的下滚。TV 上 `LpCell` 开关行整行是一个焦点、确认切换；`LpDialog` 遮罩与面板不取焦点，打开时焦点交给面板第一项;最左当前服名替代品牌，主栏目首页/收藏/发现,辅助搜索/设置,原route.rail索引保持，索引2/5/6不展示独立导航项。首页/收藏/发现获焦立即切页，服务器/搜索/设置仍确认，首页确认刷新。服名普通点击服务器管理、长按/菜单键进私密、私密点击退出，移除首页重复服名；首页库标题进入完整库，设置管理媒体库保留解除隐藏入口。TV不提供下载，卡片/详情移除下载操作，插件downloads路由拒绝；旧下载路由回首页，不加载任务。左右选择；栏目获焦切页，动作入口确认；下键恢复内容记忆;当前基础栏目不重建entry,下钻确认回入口。私密空间额外隐藏设置与角标，服名保留。首页、详情、媒体库、搜索、聚合与设置复用手机页面；聚合标签为“聚合视界”。TV详情头图最小高220dp，首页轮播只自动展示、不进入焦点链，并在离屏/后台暂停。SharedTvBrowseTest/TvPrivacyTest覆盖实际入口与焦点；TvPageShots 只出播放页截图，真机手感与帧率另验。
- 默认橙金保留影院底色；本机外观设置可选蓝/绿/紫/Monet，深浅独立。Monet只在API31起使用Material3动态中性色与强调色，旧版本明确回退橙金；mediaAccent/mediaOnAccent为acc/accFg别名，避免选中态出现第二套蓝色。浅色正文、次级与弱提示文字在 bg 和 s1/s2/s3 合成底上至少 4.5:1，由 PhoneThemeTest.lightTextContrast 断言；实际组件与叠图仍需渲染核查。
- 深色 → 浅色 → 深色要检查背景、说明、箭头、数值和选中项；设备强制深色的表现不能只用 JVM 截图下结论。
- PhoneRoot 始终铺当前主题 bg，壁纸在其上绘制；不能因配置了壁纸而让根背景透明。标准 Material 表面色先将 s1/s2/s3 合成到 bg；共用面板与按钮使用合成后的实色主题表面，不加玻璃高光；普通与隐私空间首页服名均无底无框，叠图时白字阴影。
- 浅色文字叠图时必须在文字区铺浅色渐变底，不能沿用固定黑遮罩；浅色主按钮各个渐变停色都要满足文字对比度。系统栏跟应用主题同步，API 24/25 的白色导航图标保留深色底。

## 资源与出包

- Android Emby 播放模式为 `mpv` / `exo` / `auto`，由共用 `PlayerController` 解析实际内核，不能将 `auto` 直接传给核心。存量配置和默认 MPV 保持；显式路由优先，设置变化只影响下次播放。自动先 Media3，仅明确解码/格式不支持时回退 MPV 一次；网络、鉴权、DRM、资源被抢占、损坏内容和未知错误不自动换核。自动模式长按播放明确用 MPV。
- 回退捕获实际版本、续播、暂停、倍率、音量及选轨/字幕关闭，先停输出并等待 `player.stopPlayback` 完成，再切内核；Compose 释放旧 Media3，手机前台服务重绑新内核。零秒续播用已有 `from_start=true`。状态监听只在当前起播成功后消费；跨内核轨道按标题/语言等身份匹配，不复用运行期 ID。非 Emby 来源保持现有 MPV 路径。`PlayerControllerTest`、`PlayerEngineLifecycleTest`、`PhoneEngineSettingsTest` 与 `TvFocusTest` 覆盖这些契约，真机仍需独立验收。
- 跨内核轨道恢复仅接受有效标题/语言的唯一匹配；两个内核的同类序号不能证明身份，重复标签或标题/语言均未知时不提交恢复选轨命令，保留新内核当前选择。歧义不消费 pending，后续唯一匹配仍可恢复；forced/位图按已知语义匹配，SDH不从标题猜测，不能声称完整语义匹配。`PlayerControllerTest` 覆盖歧义拒选、后续唯一恢复、手选音轨及字幕关闭。
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
- 手机首页不展示或请求「接下来看」。每次恢复 RESUMED 更新继续观看；首页采用紧凑服务器栏，媒体库 → 继续观看 → 各库最新 → 合集，电影Hero复用首页已有作品、不调用随机推荐；库入口及最新结果复用页面缓存，重新进入/显式下拉/失效通知时后台重取。媒体库作品网格普通返回同一导航条目时不重取已成功的相同筛选结果，保留分页和滚动；新导航条目、筛选、重试及userStateRevision变化仍刷新原已加载范围。完成标记随导航条目保存且仅成功后提交，不能仅凭PageCache有内容跳过取消或失败请求；分面结果同时留存。PhoneLibraryUiTest覆盖普通/空列表返回零新增请求、状态变更刷新、新进入刷新和初次请求取消后返回重试。刷新主请求组结束、失败或取消后必须收起指示器。
- 手机普通聚合页快捷入口为下载、排行榜、日历；服务器独立 Panel，统计下直接显示无标题续播轨。进入/下拉传 `counts_only=true`，已有核心快照时只更新统计；播放/续播失效通知仍完整刷新。成功覆盖数字（含0），失败逐字段保留，首次未知不伪造0。首次无快照居中大加载，热刷新不插行、不清内容。图片用所属服 `line_url` 经代理；跨服详情确认目标线路/用户激活后导航。回归见 `PhoneAggregateCacheTest`。
- 隐私空间保留聚合 Tab，仅承载当前服流影和黄果 AI、JavRanking 榜单入口；分支在普通快照读取和请求之前返回，不开放跨服聚合。榜单沿用 `RankingPage`，请求 `server_id` 固定当前会话服务器，目录、分页和详情匹配均携带隐私请求空间，只显示成人来源 `huangguoai`、`javranking`。流影使用 `NativeLiuyingContent` 原生单屏连续随机播放，不再加载上游网页。入口只读 `emby.extCapabilities` 缓存，能力未启用、读取失败与空入库列表明确展示，不回退网页。列表/标签分别用 `emby.extLiuYingItems` / `emby.extLiuYingLabels`，支持普通/18+、舞蹈/直播、频道与曝光联合筛选、收藏；切来源清空不适用筛选。手机由`PhoneLiuyingFeed`/`LiuyingPlaybackSession`使用独立Media3 1.11.0链路，不嵌入PlayerPage、不读取普通内核设置或回退MPV，不启动普通后台服务；TV仍传`fromStart=true`复用原播放器。结束或手动换片均遵守既有停止屏障；页面按账号/空间隔离候选，切筛选取消旧请求，离页停播。手机播放候选最多保留三页、TV仍保留两页（每页最多12条），不保存播放地址或续播断点。流影专用覆盖控件不启用普通播放器手势/OSD；手机上/下滑在当前筛选内切片，横滑进度，TV用焦点按钮切换；手机用PhoneLiuyingChrome白色悬浮控件、红色强调及细白进度；点画面暂停/继续，仅暂停时中央显示半透明播放图标；时间在进度左侧。右侧仅无文字收藏心形与倍率，底栏为自动连播/循环播放、仅普通舞蹈显示的筛选、静音；顶部无筛选摘要。解放图标短按打开收藏、长按切换卍解，互不触发。标签/计数/收藏筛选移入可滚动的深色底部面板；TV保留原可收起标签栏。手机面板开关归PhoneLiuyingFeed根层持有，在候选范围key之外挂载可聚焦Popup，开关不重播，换片/筛选不卸载，返回/外点只关面板；面板和滑条输入不可穿透到换片手势。支持循环当前，静音选择跨片保留。手机收藏列表按普通/卍解隔离，普通可切舞蹈/直播，请求固定favorite=true、频道/衣着为空；分页失败原offset重试，取消后拒绝旧响应；收藏使用无可见标题/完成/说明/返回全部入口的底部弹层，复用普通列表posterColumns()和封面/标题卡，普通模式保留舞蹈/直播；无障碍paneTitle及外点/系统返回关闭仍保留。封面走当前会话app.imageUrl(item_id)的Primary通道，失败占位仍可选片；固定深色面板内标题保持白色，不继承浅色正文。点选只交接准确单条，不把其余收藏加入播放窗口，立即切循环当前；清频道/衣着及favorite限定，后续使用新seed、offset=0从当前feed/tab正常随机取候选，手动滑动才切换，循环模式继续保持。分页成功返回空列表且保留上一页时，手机手势层仍允许下滑回退，空画面点击/横滑不控制播放器。删除或隐藏入口时必须逐模式核对退出路径和空态回退，回归覆盖普通舞蹈/直播/卍解，而非只测带筛选的分类。手机流影倍速只读写核心liuying_speed（默认1倍），与普通default_speed双向隔离；手机专用liuyingPlay固定Media3与流影倍率作用域，TV仍显式speed_scope=liuying；加载前应用对应倍率；调速/停止串行，跨片先等待在途保存，失败仅向仍有效的当前视频回滚。首页通过 `home=true` 隐藏流影库/内容，其他库浏览不变。回归见 `PhoneLiuyingTest` / `TvLiuyingTest`，真机焦点与解码仍需独立验证。
- 手机与 TV 榜单/公共日历调用 `emby.extRankings/extRankingItems/extCalendar`，经 `extensionBlock` 消费统一快照；E_UNSUPPORTED 明确展示，不能静默或退回旧快照。手机榜单下拉刷新、触底按页追加并按来源 ID 去重，空页/无新增停止、追加失败保留条目并重试原页；列表逐项懒加载，底部 inset 放进 contentPadding。日历日本时区，周条胶囊只写星期与日期、不写当天部数，卡片经 `openExtension` 实时唯一匹配进详情，无标题搜索。个人同步和提醒保留，不查询赞助；回归见 `PhoneDiscoverUiTest`、`DisplayCacheTest`。

依据：[入口](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/MainActivity.kt)、[手机主题](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/theme/Theme.kt)、[TV 主题](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/tv/kit/TvTheme.kt)、[主题回归](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneThemeTest.kt)、[安卓历史](../../../docs/lessons/android.md)。

- 首页与详情栏目标题不画左侧竖杠；有更多入口时点标题导航，不另画「更多」。首页媒体库首行只展示Fit封面，has_primary=false不请求图片并居中显示库名；有图时加载/失败也显示库名，成功后隐藏；不能恢复封面下重复库名。首页设置无圈，搜索位于悬浮栏聚合左侧，作为普通页面压入原Tab栈。

- 手机登录后浏览页统一显示悬浮导航，播放/流影/登录/添加服务器除外；纵向用户滚动累计24dp切换显隐并滑移淡入淡出，横滑不触发，换页恢复。底部留白固定，当前Tab在二级页点击返回根页。搜索不重复压栈，悬浮搜索始终进入全局搜索根页，二级页不另放导航搜索按钮。库页排序入口在库名同一行最右侧：无底色递减横线图标、当前排序和方向箭头；点击打开排序/升降序及条件面板，默认无筛选行，已选条件才显示清除条。方向按库保留并进入filterKey与请求；切换排序取消旧分页任务。
- 收藏总览无排序；电影/剧分类页共用媒体库的 `posterColumns()`、网格刻度与 `MediaSortControl`，不能另用自适应列数。分类排序仅重排已加载条目，通过 `grid.requestScrollToItem(0)` 在下一次测量回到首项，避免按旧海报 key 恢复锚点；不触发收藏查询、不重置原始分页游标，新页按当前规则合并排序。`Item.from` 保留核心已有 `sort_name` / `date_updated`，缺值在升降序均沉底。手机收藏/历史已看单次请求前150条（`FAV_LIMIT`，核心单页上限200），不分页，不放「加载更多」；TV与其它列表的分页一律在末尾进入视口时自动续页，失败才给「重试加载」；回归见 `FavoriteLocalSortTest` / `PhoneBrowseUiTest`。
- 手机海报刻度统一走 `Cards.kt` 的 `posterLayout`：竖屏手机保持104dp轨道与官方3列；宽屏（≥600dp）首页轨道卡宽与网格单元同宽、上限150/170dp，横屏手机（高<480dp）仍取104dp只增列；插件 `posterColumns` 覆盖优先，TV不参与。封面请求高度用 `posterImageHeight`（实际显示高度×密度，再落固定档），新卡片入口不得写死330/220；`LpRow` 的取图函数接收该高度。回归见 `PosterLayoutTest`。
- `MediaSortControl` 在顶部栏内使用对称垂直留白；不能带入列表的单侧 bottom padding。几何回归比较可见标题与排序文字的纵向中心，而不是只比较外层点击区域。
- 手机收藏总览按 `library_ids` 匹配 Views 库 ID，使用实际库名与 Views 顺序，空库不画；多库收藏在各库分别展示，栏目/缓存 key 使用库 ID。点击标题进入库内收藏网格，仍使用收藏接口与原分页游标、本地排序。未匹配任何已知库的条目保留原类型栏目，库信息失败不能整页失败或丢项；库缓存绑定服务器/用户，旧接口不额外请求 Views，分类不依赖 library_type。

- 手机搜索/收藏总览与分类页使用已有 `PullToRefreshBox` 下拉刷新，空态与失败态提供可滚动容器。收藏刷新成功才替换数据与原始分页游标，失败保留已有内容；取消/失败/成功均结束指示器。搜索保留当前关键词及 parent_id，下拉按当前模式重查。搜索只传 Series/Movie，移除包括集与聚合筛选；搜索框内右侧叠层图标切换聚合，开启时高亮且具有 selected 语义，库内范围隐藏图标。聚合开启时提交已有关键词，后续由键盘搜索键或下拉触发，页面作用域处理 partial 并隔离旧运行。

- 手机搜索无独立标题/返回顶栏，状态栏安全区与底部悬浮栏留白仍保留；使用 LpField.trailingIcon 内置聚合图标、onSearch 提供键盘搜索动作；结果区与输入框相隔 Sp.x16。聚合栏目只显示来源名与横滑海报，不展示标识查询 warning 或数量后缀，真实请求失败仍显示。

- 手机搜索是临时导航动作：`switchTab` 在保存离开Tab栈前弹出当前Search，避免restoreState恢复成搜索页；系统返回仍恢复原浏览页，非搜索的二级页与滚动状态仍保留。导航回归须覆盖“聚合→搜索→其它Tab→聚合”，仅断言Tab选中不能证明显示目标内容。悬浮栏聚合视界和框内聚合开关统一使用LpIcons.layers。

- 手机详情的当前集定位在横向 `LazyListState` 中完成，当前 ID 出现在已加载分集后滚动一次；定位标记与列表状态需可保存，避免补页/纵向离屏返回强行复位，切季按季 ID 隔离。`PhoneDetailOptionsTest` 验证第8集详情打开后第8集和「当前集」可见，不能仅断言选中标记存在。播放选项使用小播放按钮上方的纵向紧凑描边按钮，无字幕隐藏字幕、实际账号线路少于两条隐藏线路；标题下日期与总时长只显示一次，时长保留到秒、无日期回落年份；顶部透明图标保留触摸区域，单集隐藏收藏，右上角直接提供下载。播放按钮集成续播剩余秒级时长与底色进度，单集头图不重复显示进度条；右侧箭头复用 `LpMenu` 浮出仅「从头开始播放」，不能恢复居中弹窗。单集剧名大标题和「SxEy：集名」排列；选集标题「来自第 X 季」点击浮出季菜单，替代季芯片栏，保留原同季重试和迟到响应隔离。

- 手机详情简介直接显示三行正文，点击展开/收起，无重复标题/底卡；媒体信息随选中版本更新，文件摘要位于等高横滑轨道卡上方。使用内容高度和换行适配大字号，不能固定高度截断参数；`PhoneDetailOptionsTest` 覆盖展开、横滑和深浅主题的 1.3 倍字号渲染。可选字段与路径展示契约见 [JSON 边界](type-safety.md#媒体信息可选字段)。

- 手机播放器横竖屏统一使用全屏视频区域；设备方向决定控件排列，片源比例决定画面适配与初始方向，不使用顶部16:9小窗和常驻详情/分集区。竖屏画面原比例居中，顶栏返回/PiP/比例/方向/章节/更多，中央退进/暂停，右侧倍率；底部上下集与标题、时间与进度、锁屏/音轨/字幕依次排列，额外操作横滑。剧集在更多菜单不再提供选集列表，保留上下集和章节，电影隐藏；不改切集停止屏障。NextUp采用单行“接下来 · 第X集 ›”胶囊，无集数回退标题，默认150×36dp视觉、至少48dp触摸高度，2dp主题色底边进度，大字号可增高；独立叠在实测底栏上方并避开倍率，宽度计入末端安全区、窄卡片省略文本并隐藏装饰图标，显隐不改变底栏/滑杆宽度与位置；OSD收起保留已测高度。根容器透明保留SurfaceView透出；全屏错误原因和重试/返回仍可操作。PhoneEnginePlaybackTest覆盖两种片源比例与两内核同页转屏、底栏前后几何、卡片不重叠、菜单切集及电影入口；PhonePlayerOsdTest/PhonePlayerMenuTest覆盖控件与锚定菜单，设备播放/PiP仍另验。

- 手机详情未续播按钮使用紧凑宽度并居中排列图标文字，单集简介位于播放操作之后、选集之前。季号从 `SeasonInfo.index_no` 读取，显示「第X季：自定义名称」，默认季名不重复；分集卡时长/剩余时间只在封面左下展示，封面下不再显示未看/还剩状态行。演员接入详情已有 `people[].role`，缺失/空白角色不画。`PhoneDetailOptionsTest` 覆盖真实季响应字段、默认/自定义季名、简介顺序和角色显示。
- 手机播放器移除选集快捷按钮，横屏右下仅弹幕、音轨、字幕；音轨用音符，扬声器仍用于音量反馈。轨道语言复用 `langCn`，中文名称作为标题回落或副标题，不重复堆叠代码徽标；保留字幕关闭与真实切轨命令。`PhonePlayerPanelTest` 覆盖语言名称、未知音轨隐藏及选中请求。
- 手机播放器不提供选集列表或插件episodes入口；详情页选集、上下集与连播队列保留。轨道读取失败显示错误和重试，与成功空结果区分；请求和临时状态按面板、条目及实际Media3实例隔离，取消不能转成空结果。PhonePlayerPanelTest保留真实选轨、失败重试及迟到响应回归，PhoneEnginePlaybackTest通过上下集验证停止屏障。

- 手机/TV共用`DanmakuLayer`从实际position采样驱动`DmClock`；两次采样之间目标与显示钟均按帧间隔×倍率推进，不能逐帧追同一个过期position。软纠偏按经过时间计算（60Hz每帧8%，其它刷新率等效），偏差超过1秒按既有seek规则硬对；position不能重启帧循环。两端均传入buffering，paused或buffering时停止外推并对齐实际采样。`DanmakuClockTest`覆盖4Hz输入、60/90/120Hz显示、倍速及23.976fps量化；`DanmakuLayerTest`验证真实Canvas移动、暂停seek与缓冲恢复，不代替真机帧耗时验证。

- Android seek 的待跳转目标只属于 `PlayerController`，相对输入必须走 `seekBy(delta, actual, duration)`，不能以重组前捕获的 position 计算绝对目标。页面以 UNDISPATCHED 提交目标；原生命令串行，排队仅最新请求能提交。提交返回不是 seek 完成；非缓冲实际位置在1秒容差内才释放，15秒超时/失败/取消清目标，250ms页面检查兜底。上报、弹幕、续播/回退快照始终取真实位置。MPV核心没有 seek 完成闩或 seeking 字段，不得据此虚构完成事件。
- 停止立即失效待跳转及排队请求，并等已发seek提交收尾；通知seek在提交锁内复查服务绑定代数，通知stop登记现有跨页面收尾屏障，旧停止完成不能关闭新绑定。Slider拖动值独立于轮询，按媒体与时长隔离，原生取消清值；横拖起点固定，TV预览从待跳转目标起步但不写真实 ui.position。回归覆盖 PlayerControllerTest、PhonePlayerOsdTest、PhoneEnginePlaybackTest、PlaybackServiceTest、TvRefreshRatePlaybackTest；精确落点和后台/锁屏派发仍需真机。

- 跨内核字幕身份除title/language外保留可空forced/bitmap；Media3 1.11.0的selectionFlags为整数位掩码，FORCED位存在时true、未设时false，MPV缺失forced仍为unknown。PGS/VOBSUB/DVBSUBS MIME是位图，SSA/SubRip/VTT是文本，其它unknown；MPV只对已核实codec hdmv_pgs_subtitle/dvd_subtitle/dvb_subtitle及ass/subrip/webvtt分类。不能使用MPV image（单张图片视频）或Media3 isText（包含位图）推断字幕类型，不能按标题或caption角色猜SDH。源属性已知时目标须已知且相等，未知不是false；仍需有效title/language和唯一候选，歧义保留手选。缺语义旧身份沿原唯一标签规则；回归见PlayerControllerTest。

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

- Android界面使用系统默认字体，不内置中文字体、不提供字体选择，旧ui_font偏好不再读取，不影响字幕。手机壁纸入口与根绘制移除，TV/插件契约保留。详情背景通过有界Coil转换处理，不修改共享原图。
- Material3 1.4.0只使用公开API，形状token8/12/18/28dp和适度回弹保持影院配色；本批导航/图片/OSD/按压动画用lpTween/lpSpring，系统倍率变化由主题观察，0必须立即到位。
- PassiveProgress是底部3dp只读Canvas：实际position、有限正duration、RTL、safeDrawing底部，OSD/面板/PiP/退场/转屏等待隐藏，锁屏可见；不可接入seek或MPV buffered轮询。
- 手机存储页通过get/setPrefetchSettings.media_cache_bytes读取/保存全局媒体容量，省略其它字段不清空旧设置，保存失败回滚，加载失败显示重试。清理显示核心实际bytes结果。arm64手机release上限64MiB，其它ABI仍60MiB。

- 手机按压弹簧不得只用pressed布尔状态证明短Tap可见：滚动容器可能在同帧发出Press/Release。共用pressFeedback消费交互事件，短Tap补可见缩放，Cancel仅复位，onClick不等待动画；海报长按和底栏selectable复用同源反馈且保留长按/Role.Tab/selected。验收至少包括滚动容器快速Tap的真实渲染、点击即时分派和连续点击，不能只测按住再松手。归一化缩放弹簧使用0.001可见阈值，避免小幅回弹过早结束；系统动画倍率0立即到终态。

- 详情图片预览以来源NavBackStackEntry与卡片独立token匹配目标entry；重复itemId不能作为唯一关联key。关联只驻内存，pop动画可见entry保留，出栈或切账号清理，不将图片授权URL写进路由或存储。非首页整卡入场rememberSaveable记忆，标题/角标与图片共用缩放/位移；`NetImage(onLoadResult)`通知成功/失败，MediaCard按item/URL隔离结果，占位始终可见，成功且15%可见才以0.94→1/20dp启动lpSpring（阈值0.001）；失败/无URL及零倍率正常几何。首批150ms内最多9张0/25/50ms短错峰；根NestedScrollConnection观察两轴consumed含fling，不消费滚动，快滚取消/跳过形变，静止150ms恢复。NetImage按URL隔离painter，普通图片含内存命中均按T8线性淡入，成功淡入期间以静态底色/来源预览托底；文字缺图占位（「暂无封面」等）只在无URL或最终加载失败时出现，加载途中不画，横滑时不能闪出缺图文字；占位本身是有效信息时（媒体库名）用`placeholderWhileLoading=true`保留；同URL普通重组不重播。仅零倍率和实际点击关联的来源卡片以reveal=false直接显示，避免返回来源海报重新透明。验收需有来源图片中间帧像素、交叉淡入混色、Lazy销毁后回滚像素及零倍率，而非只断言最终可见。须覆盖真实MediaCard冷图等待超过原动画周期后才开始、spring过冲、换URL隔离、占位错误/无图、部分裁剪可见面积和真实Lazy fling；不能仅用就绪色块代替异步图片整卡联动。

- 详情放大转场：点带图MediaCard或首页Hero剧照时，来源与详情（手机SeriesHead/EpisodeHead整块头图层、平板WideHead海报）以`sharedPoster`的sharedBounds+RemeasureToBounds连接，T7 emphasizedDecelerate边放大边重裁切，返回缩回改T7 standard（首页重新组合首帧偏长，起步平缓避免掉帧变跳跃；两端按当前页面判断方向）；详情头图T5淡入、来源T2后T4淡出，预览图解码头几帧由来源托底。详情previewUrl必须传来源卡片原地址：NetImage以它作memoryCacheKey直接命中已解码海报，未命中才读`/img-cache`；传改写后的地址会错过内存缓存，放大途中先露底色再跳出海报。侧滑返回须同时配置NavHost的predictivePopEnter/ExitTransition：导航2.10默认把整页缩到0.7，放大进入的详情要与返回键一致只淡出、留海报缩回。首页LpRow经`warmDetail`在卡片停留300ms后以HEAD预热详情Backdrop 720（`warmDetailBackdrop`，最多两路、同址一次、分集与无背景图跳过），首次放大时背景图从本机缓存读出，不在转场后才换图。压在头图上的标题与顶栏用`aboveSharedPoster`画到共享层之上并自行淡入（进入T2后T5、返回T3立即淡出），全部动画不超过T7，避免先被海报盖住再跳出。列表只有实际links指向的源卡片登记既有共享节点，空闲列表返回原Modifier。共享工具测试不能代替当前生产详情转场验收，也不能用常驻所有节点换取匹配。普通搜索使用三卡片懒行，含稳定行序号的key隔离重复ID；60结果首屏仅组合附近行，并验证末尾可滚动/点击、无额外搜索请求。

- 原生首页 `LpRow(homeAccount)` 对每张真实卡片应用 `homePosterEntrance(itemId,index,account,row)`，标准插件items也接同一modifier；媒体库入口和custom接管不套。按账号与卡片身份rememberSaveable保存seen，以裁剪后可见面积达到15%触发，snapshotFlow只观察阈值布尔。卡片从0.90缩放、向下22dp、0.86透明度，以lpSpring(.001f)上浮展开；缩放原点底边中点，相邻0/25/50ms短错峰；动画帧仅在graphicsLayer消费，不横移轨道、不等停滑或图片解码，已触发不因快滑/可见性变化停住。Lazy/导航返回不重播，换账号重置，零倍率立即正常；骨架轨道稳定，真实卡片到达后才入场。其它页面保留原posterEntrance规则。回归必须包括真实LpRow中相邻卡不同相位/向上扩大/水平中心不偏移、标题稳定、帧间不重组栏目、横纵向进入、冷图不阻塞/不重启、Lazy/导航返回与账号隔离、真实fling持续fast时完成、零倍率与点击/长按。参照风格判断须有连续帧或源码支持，不能把测试和出包当作真机流畅证据。
- 详情头部可以从导航内存PosterLink取来源Item做纯展示预览，但仅在真实detail为空时取标题/剧名/集号；不可把预览写入Block.Ok、交给插件或用预览seriesId生成可点击链接。无图来源也能预览标题，但不注册共享图节点；账号切换清空预览，重复itemId按目标entry及来源token隔离。NetImage背景模式400ms线性渐显，包括内存命中；普通列表热图按T8逐张渐显，不等待整批图片。来源预览直接复用原图，不为预览重复模糊；完成图仍按背景模式模糊，无预览的背景用静态底色。渐显连续数值只在绘制层读取，占位是否保留用derivedStateOf。验收挂起真实详情请求、无图来源、分集不可点击剧名、服务端字段覆盖预览、账号/重复ID隔离、冷热背景中间混色和零倍率。

- 详情展示快照统一由 Go displaycache 白名单投影、持久化和清理，Android 通过 displayBlock 消费 partial，不再维护宿主磁盘缓存。普通与私密均持久化，按服务器/账号/空间及查询隔离，共享1000条/每条512KiB预算；不持久化凭据或将旧展示资料作为实时操作依据。页面按账号、空间和条目隔离，取消或身份代次变化拒绝迟到响应；网络失败保留预览并显示错误，鉴权、不存在等失效错误清除预览。收藏/已看、插件及起播决策只使用 live 结果。核心快照直接展示，不重复播放首次内容入场动画。
- 首页首次单卡入场使用lpTween(T9, CubicBezier(0,0,.2,1))，中心scale .5→1、alpha .4→1、translationX本卡宽度→0；至少15%可见触发，不等图像或停滑。首批错峰仅横轨静止且index0/offset<30px/index<12，min(index,4)×80ms；已横滑的新卡不排队。账号隔离与Lazy/导航返回不重播保留。持续横滑形变从各LazyRow.layoutInfo读取卡片裁切比例，只在graphicsLayer消费，不用每帧Compose状态驱动整排。首次seen只约束一次入场，不阻止已加载卡片随位置形变；零倍率同时关闭两者。回归先断言真实图片已显示，再检查同位置往返、完整离屏返回和零倍率，避免把图片加载或裁切宽度误认成缩放。

- 手机详情由带图海报/Hero放大进入时（`PosterMotion.zooms`），页面原地T6线性淡入淡出、不侧滑；其余进入/返回退出使用T6从右侧整页滑入/向右滑出，来源页面在其下保持可见，头图保留来源预览和独立渐显。其它页面保留T8/T5/T6线性透明度、二级页10%位移和Tab轻缩放。参数为依据Yamby隔离运行观察的独立近似，未还原native普通回调，不与已确认的手势分支混用。FloatingTabTest在真实PhoneRoot中验证中间位置、来源图片可见像素、电影和剧集→分集返回几何与零倍率，不用测试NavHost替代生产接线。
- 手机详情评分、标签与标语只显示来源/缓存/真实资料的已知值，缺失不预留待收起的空行；真正变化仍以400ms线性淡入、300ms高度过渡加入，相同资料刷新不重播。冷正文等真实详情和关键区首次结果协调呈现：电影/单集为itemMedia及轨道/线路呈现基线，剧/季为季请求和首批分集，失败也放行对应错误；来源标题/图片和已知评分先展示，不等图片、相似推荐或季后页。首次正文以最终高度出现，不先画空播放区再逐栏挤开。插件、权限和起播真源仍只认live；未知网络等待不加人为延迟。PhoneDetailCacheTest覆盖分阶段释放请求、冷热、空值、错误、像素及零倍率，不代表真机性能验收。

- 手机详情itemMedia用Block<List<Version>>区分等待、失败和完成，不补造默认版本或空轨道。冷打开仅一处LoadingState，作为覆盖层居中于头图下方仍空着的区域（宽屏在右栏），列表内只留同高空位；正文出现后播放选项/季/分集等待只留空位，不在局部另画加载图；已有本次运行展示快照时保留媒体选项/季/首批分集和统计，后台刷新不退回各区加载态。DetailPage借既有PageCache留最多16条账号/空间/条目键的页面状态，每条最多200集，不落盘；账号切换/空间代次/页面缓存清理/鉴权及不存在使其失效。新分集请求从offset0开始，不能用旧显示列表冒充已完成分页；真实首批替换，后页仍追加，失败保留已显示数据及独立重试。热刷新分页未完成时保留旧统计基线，不切回加载进度文案；已选季仍存在时保留，服务端移除后回落到真实首季。选择器/起播目标/版本ID和宽高比只用live结果，不能提交旧快照版本；真实变化、失败以及明确空结果仍呈现。媒体信息按实际版本过渡。PhoneDetailCacheTest覆盖热刷新不闪加载、首批从零、失败保留、清理、账号及离页迟到响应。
- 详情（手机与 TV 共用）发现当前目标的版本都没有视频规格（无视频流，或视频流无分辨率且编码只剩 `unknown` 占位）时，后台调一次 `emby.refreshItem(full=false)` 请服务端补探测，再按 3/5/10/20 秒轮询 `emby.itemMedia`，规格齐了只替换媒体选项；刷新需管理员，失败静默放弃，每个服务器+条目每次运行只请求一次。`unknown` 占位一律按缺失展示，不显示 UNKNOWN。

- 普通NetImage解码请求不等可见性，淡入则按裁剪后的boundsInWindow面积至少15%触发；完全离屏才复位，0–15%之间保留已触发状态，避免边缘往返闪图。可见标记以布局位置保留、不以URL重建，URL更换只隔离painter/fade；背景不门控，实际共享来源即使reveal=false仍观察几何，结束共享后不从未初始化的可见标记重播。零倍率忽略门控直接显示。回归须先在屏外完成解码，再滚入采样混色，并让同一已解码图完全离屏后重新进入；滚动后先完成实际draw再推进手动动画时钟，不能只推进Compose时钟却没有绘制隐藏帧。
- 手机媒体库每批30条，以累计实际条目数量作offset；不把短首批当完整库。冷加载先显示顶栏/筛选结构与正中所选加载动画，不铺整屏虚构海报/片名骨架；资料到达立即显示真实标题/年份/角标，单图静态底色渐显。已有同筛选keepState缓存按原条件复用，换筛选清旧数据，网络回写前检查取消。不混入旧筛选条目；首页和库首批的磁盘快照按本节浏览缓存契约复用。

- 搜索临时数据由NavBackStackEntry拥有的SearchPageState保留，按会话/来源隔离，出栈释放；成功/失败完成标记避免详情返回重搜，rememberLazyListState在条件分支外保存位置。取消回写前ensureActive，聚合partial归请求子作用域，换词重置完成代数。搜索海报复用菜单，跨服已看/收藏必须server_id，跨服下载不提供，插件使用source.setFavorite。所有手机/TV新增条目屏蔽菜单移除，库屏蔽与历史解除保留。海报Popup140dp，按左右/上下空间避让当前海报，lpSpring(.001f)居中回弹；极窄窗口无完整空位只能钳位，不能保证绝不遮挡。

- 整剧/季冷打开不先露出空选集标题和假播放目标，首批分集后一次呈现正文；单集媒体首屏不等待所属季/分集请求，已显示的选集区保留episodeStripHeight，图片独立渐显。切季仅清当前季数据，取消旧请求；加载/partial失败保留已到达卡片与局部重试，无季/空集明确提示。季标题只写“第X季/自定义名”，仅多季可点并显示箭头；缓存季关系未确认前不可操作。普通与1.3字号分阶段释放请求检查初次成形及完成后几何。插件、超长元数据及真实错误允许改变高度。

- 手机/TV浏览快照由 Go displaycache 统一存取，AppState.displayBlock 只消费 partial 并保护会话/空间代次。普通与私密均持久化；进度/已看快照只供展示。设置清理调用 system.clearCache 并清 PageCache；旧 detail-v1/browse-v1 目录在启动后台清理。
- 首页在既无Hero又在等媒体库时整页只画一个居中LoadingState，列表以透明度0照常布局，内容到达后整体淡入；单条栏目等待只保留低对比静态封面占位，不再各自叠加载动画；等待区沿用真实横轨高度，不能因缩成图标而提前触发屏外栏目请求。顶栏56dp、服名18sp、设置24dp且48dp命中区。本机外观hideHomeLibraries默认false，开启隐藏首行媒体库入口与继续观看标题，仍展示续播卡片与最新栏目。
- 库数量显示已累计条目数：未结束加“+ 部”，只有服务端总数已满足或空页才结束并去加号，短页不代表结束。后台刷新保留旧数据，按30条重拉此前已加载范围再整体替换，避免新首批拼接旧排序尾页；重试/条件变化取消旧分页。失败保留已显示条目和重试，鉴权/不存在错误清空展示。
- 悬浮栏首页/聚合/收藏无论当前页均回一级根页，不restore二级栈；搜索为全局一级搜索，普通返回仍回原页面。搜索不自动聚焦；电影/剧集/人物单选参与查询签名，人物点击用personItems作品弹窗，不能进入播放详情。空输入显示本机最近10词，去重按最近顺序，明确提交或打开结果时记录，不按防抖输入记录。
- 电影/剧集详情标题占整行，移除标题前独立小海报，仍用背景/标题预览与局部资料过渡；顶栏线性图标统一24dp、48dp点击范围。放大转场的目标是整块头图背景而非小海报；无图来源或目标条目不一致时回到普通滑入转场。
- 手机各详情头部最小高度为1.08×宽度，大字按内容增高；has_backdrop=false或Backdrop加载失败时用Primary海报，has_primary=false时不重复请求缺图；字段缺失兼容旧响应，真实资料前使用来源预览。PhoneDetailCacheTest验证明确无背景与实际背景失败的请求分支。
- 版本选项为整行浮动标题描边框，显示版本名及容器/大小；标题通过描边缺口融入取色背景，不铺不匹配色块。独立视频标签显示分辨率/编码，音轨按钮显示主信息/语言副行；字幕/线路独立按钮，仅可操作项带箭头。显示与起播版本规则、轨道偏好和居中选择弹窗保持既有契约。
- 手机播放规格不能只对齐框架而删掉字段：视频保留video_range_type/range及真实Dolby profile/codec识别，不从文件名猜HDR；音轨/字幕主行优先display_title规格、副行保留title真名，缺名称才拼已有语言/编码/声道/码率，fmtRate仍按现有契约使用。Stream.label与选择弹窗保持真名优先，展示不改变轨道选择。关闭字幕不保留旧副标题，default标记不重复追加；未标注/und/空值不冒充实际名称。正文常规字重、次行13sp，全文换行，不用maxLines截掉参数；PhoneDetailOptionsTest须覆盖完整DV/音轨/字幕、缺标题回退、关闭后文案消失及200%字体hasVisualOverflow=false。
- 首位后台刷新若新列表首ID变化，起点且未滚动时requestScrollToItem(0)，避免Compose稳定key让新项藏在旧首项上方；中段保留key锚定，相同首项刷新不归零。收藏按过滤/本地排序后的展示首ID判断，不能按原始响应无条件归零。PhonePosterMotionTest/PhoneLibraryUiTest/PhoneBrowseUiTest须断言海报起点坐标及中段/相同资料位置，不仅assertIsDisplayed。

## 浏览响应快照契约

1. **范围**：Android手机首页views/resume/latest/collections与媒体库首批；新增存储仅用于先展示、后台刷新。
2. **签名**：`AppState.displayBlock(command,args,retainOnFailure,onCached)`；持久存取、键、投影、预算与失效统一由 Go 承担。
3. **契约**：遵守核心展示快照契约；宿主只在当前会话/空间接收 partial，实时结果不得被晚到预览覆盖。详情和库刷新使用 retainOnFailure=false，错误与旧展示分别呈现。
4. **错误**：坏文件/超限→丢弃回源；写盘失败→保留内存与实时显示；普通网络失败→保留已显示快照及库页重试；E_AUTH/E_NOTFOUND→删除对应缓存并清空受影响展示；取消/切会话→不回写UI。
5. **用例**：正常重启先显示同账号同筛选快照；首次无缓存先显示结构与刷新；切筛选不显示旧结果，短页继续；错误示例是磁盘晚到覆盖新响应或按已加载页数假称总数。
6. **回归**：core/displaycache 测试验证白名单、重启、身份/查询隔离、有界淘汰、坏文件和generation；PhoneLibraryUiTest验证挂起网络期间已消费核心快照、失败保留海报、每批30/累计offset/短页/结束数量；禁用缓存展示回调时此用例必须红。PhoneHomeRefreshTest验证可见栏目和隐藏开关；PhoneBrowseUiTest验证焦点/类型/历史/人物作品；FloatingTabTest验证一级落点。
7. **正反例**：错误为`items = freshFirst + oldTail`或用缓存resume向服务端回写；正确为保留旧快照，30条逐页刷新原加载范围再整体替换，播放续播仍向核心/服务端实时获取。

- 手机原生无定量等待统一使用外观所选动画，入口为LpLoading：页面、列表/搜索、下拉刷新、按钮、面板/弹窗、启动和播放器打开/缓冲共用样式。外观提供Material、Style1–5、Style7七款预览单选，ID为0/1/2/3/4/5/7，默认Material，loading_style本机持久化、即时生效，非法旧值回落0。Material/Style4跟随主题色，其余保留原色；动画本地解析并缓存，至少STARTED且等待仍存在才播放，后台/离组合停止，恢复前台仍等待才继续，系统零倍率保留有效静态图形。按钮保留尺寸并禁用重复点击，LoadingState/BlockBox默认铺满所在区域并居中，在Lazy项内须显式用fillParentMaxSize，插件面板保留120dp；页面级等待只出现一个居中加载图：无内容的首屏用LoadingState（Material 90dp，其余130dp），已有缓存时后台刷新与自动续页静默进行，不叠加小加载图；下拉刷新指示只在用户手动下拉时出现；无定量等待仅画动画，正在打开/缓冲/加载等说明只作为contentDescription，LoadingLabel沿用原文字作读屏标签；已有内容、业务说明、实际计数、失败及重试保留。真实百分比、图片占位、网页内部、TV和插件主动绘制保持各自语义；启动静态轮廓只叠一份所选动画。
- Material LoadingIndicator的原生窗口可见性回调也会启动动画；AndroidView必须随宿主STARTED控制可见性，attach回调完成后按最新播放条件同步drawable，detach/释放停止。回归分别覆盖Material与Lottie的真实帧推进、CREATED首次挂载、后台停止/前台恢复、系统零倍率和离组合；不能由测试手动启动drawable冒充生产接线，Material测试见MaterialLoadingLifecycleTest。

- 手机文字由 `LpText` / 完整M3 Typography定义字号、行高、字重、字族和零中文字符间距，规格见 UI_MOBILE.md §1.2。显式TextStyle必须取当前主题字族，不依赖LocalTextStyle隐式合并。顶栏heightIn允许双行200%增高；详情背景与正文布局分开，分集头图比例只作最小占位。数字tnum须实际测宽，不能只断言属性；内置字体已移除，动态数字用系统字体+tnum。封面10/13sp时长与排名装饰数字保留专用规格。

- 整剧入口布局分支只认Series：真实评分/年份与成功季请求的季数，宽播放键和独立实际待播集说明；简介置于选集前，扩大分集卡但不影响单集/Season卡片。季海报选当前季不重复请求，明确无Primary不取图，等待/真实轨道保留同高；复用现有换季和分页取消契约，不新增逐集详情请求。共用头图64%开始渐隐，标题底用主题bg衔接取色背景；保留预览、模糊、渐显及图片失败回退。

- 版本正文和选择弹窗允许完整名称换行，不用两行省略；Version.displayName只用于展示，真实Name优先，空名或明确泛称才回退安全路径文件名，地址凭据/查询/片段不可展示；无名称和文件名则明确未提供，不影响preferred或提交的版本ID。OptRow仅版本菜单放开labelMaxLines，其他调用保持默认。

- 首页Hero按UI_MOBILE.md当前电影Hero规格，候选来自当前账号首页及既有浏览结果的展示池，主图缓存先显、当前可见项再按需取原图，Backdrop优先/Primary与Logo失败回退。触摸/横滑重置5秒计时；换图为同时推移：背景整幅T10 standard横移，自动与左滑新图从右侧推入、右滑相反，提前解码下一张避免推入空底；渐变压暗层在图层之外不随换图移动；文字同向只移1/3幅宽并T4淡出、T3后T6淡入，比背景慢一拍；评分行依次★普通分与豆瓣标志+分数（只认DoubanRating，无效隐藏）再接年份/类型；简介晚到用animateContentSize过渡；纵滚/后台/离屏/零动画倍率暂停。首次头插仅起点复位，账号隔离与取消保留。所有手机标准海报复用MediaCard的精简片名/年份（单集保留SxEy）、EpisodeStatusBadge与MediaRatingBadge，数据源共用PosterCaption和CardMenu；普通评分读取rating、豆瓣只认DoubanRating（沿用服务端/核心同名字段，浏览缓存白名单保留）；两种评分分别以★/豆瓣彩色标志（`LpIcons.douban`，须用Image绘制，不经Icon着色）标识，右下纵排迷你胶囊，缺失或无效分别隐藏，普通0分作为无评分隐藏，Series完成只认有效统计+零未看+Played，电影无数字。Dock仅四图标，宽度随图标收拢（每格58dp，不按屏宽比例拉伸），键盘隐藏、系统导航区上12dp、200ms动画与24dp滚动阈值，搜索独立选中。HomeCinemaTest/HomeHeroTest/FloatingTabTest覆盖数据与真实组件；设备安全区/播放仍需真机。

- Hero背景可以越过其原始占位延伸到继续观看图片3/4处，按媒体库入口实测高度与共用栏目/图片刻度计算；显示媒体库时保留原几何；隐藏媒体库时将入口和续播标题高度并入Hero，标题文字组和指示线下移，续播卡片及背景下沿保持原Y。背景不可拦截后续卡片点击。无续播则不延伸。PhoneColorSchemeTest/HomeHeroTest验证真实渐变溢出像素、控件几何、主题切换/持久化及API28回退；系统壁纸动态更新和设备体验仍需真机。

- 首页Hero候选按会话使用既有页面留存，返回前后不删除再插入首项；有效候选保留顺序，页码用导航保存状态并在候选缩减时校正（rememberSaveable恢复不会验证inputs）。媒体库与续播标题按实际测量高度计算背景延伸，默认隐藏时标题高度由同一TextStyle的TextMeasurer计算；首页Hero顶部不铺暗色，只在Hero底部约180dp文字区渐暗并延续到媒体库与续播标题，再从续播图1/4到3/4融入主题底色（无续播时末24dp）；不给标题区铺独立黑底。文字组为标题/Logo、暖金★评分+年份类型、两行简介（`emby.itemDetail`的overview，分集取所属剧，当前与下一张预取、每部一次），不设播放键，点按整张进详情。标题/说明及未滚动顶栏用白色前景与文字阴影。候选必须实际调用cachedHomeImages，仅扫描/img-cache；主图与Primary回退通过imageUrl(original=true)省略h及quality参数，HeroArtwork按布局解码、后台/离屏不启动下载、失败保留预览；Logo仍只读缓存。随机新增候选由首页安排，工具保留传入顺序。PhoneHomeRefreshTest验证实际中点/开关联动、部分滚动返回及缓存未命中仍可展示已知候选；HomeHeroTest验证中段亮度与保存后缩减候选不会越界，工具单测不能替代真实页面接线。
- 详情首帧可从PosterLink的Item补评分/年份/类型/已知时长等展示字段，不含进度/收藏或可执行所属剧ID，仍优先live与核心partial；鉴权/不存在错误拒绝此预览。Series路由ID已知时季请求独立并行，Season/Episode仍以真实详情所属关系为准。明确无Primary时可保留来源已显示海报；模糊目标与未模糊预览即使URL相同也不是同一图片模型，不能误去重。回归需验证挂起详情期间展示字段、一次季请求和实际海报像素，取色请求不能作为头图可见证据。

- Hero禁止在标题Column范围内单独铺深色遮罩，以免形成中部暗带。深色主题的底部渐变需检查从35%到末端亮度不回升、顶部无暗色且保留上方图像亮度；浅色主题末端仍衔接浅色页面底色，不套用深色单调变暗断言。海报/季/分集的文字缺图占位自带 s3 底，媒体库入口占位仍沿用父容器底色。渲染验收同时检查亮图长标题可读性、两侧连续性、继续观看位置和底部融入。

## 首页轮播候选与倒计时契约

1. **范围**：手机轮播纯展示候选；不修改核心进度、播放解析或服务器接口。旧孤立图片哈希无法恢复作品归属，只有已取得资料的作品可用，未命中图片缓存不再排除。
2. **签名**：`emby.cachedItems(is_adult)` 只读核心候选池；`AppState.cachedHomeImages(items,onBatch)` 负责图片探测。首页在数据/用户状态变化时重读候选，不请求全库。
3. **契约**：Go 在成功浏览/搜索/详情/分集结果及 setPlayed 确认后合并候选，身份和空间键隔离、屏蔽版本参与键。池与其它展示缓存共用1000条预算，单池最多512KiB，按字节去掉最旧资料，不存URL/凭据/版本；稀疏已看更新保留未返回资料。
4. **边界**：清理推进generation并通知轮播，旧写回拒绝；缺资料跳过；图片未缓存时保留候选，仅轮到可见项时回源。只对127.0.0.1的/img-cache发HEAD且禁止重定向；4并发、首批4项，其后每批24项，首批先展示，不全池预解码。单项IO中断仍保留候选供可见项尝试原图；本地服务拒绝连接停止探测，下次进入/刷新可重试。
5. **正常/基本/错误**：已知且未看作品正常进入；单项隐藏指示线；played或resume_secs>0同时排除条目及所属剧，已知Series优先于同剧分集、按作品去重。错误为同账号旧展示进度参与起播或跨账号复用候选。过滤仅认最近已取得的服务端快照，不额外联网查询全库状态。
6. **回归**：core/displaycache/candidates_test.go验证多页合并、重启/账号隔离、白名单、字节预算、稀疏状态更新和清理屏障；CachedHomeImagesTest验证35候选、原图缓存优先、旧缩略图回退、原图URL无h/quality、排重/观看过滤、单项中断及扫描只读路由；HeroArtworkTest验证缓存先显、原图替换、失败保留预览与旧结果隔离；HomeHeroTest验证两轮无重复/相邻无重复、最多6条可见指示线、5秒填充及生命周期；PhoneHomeRefreshTest验证生产页池接线、初始隐藏及切换几何。真机观感/大池扫描耗时独立验收。
7. **正确方式**：保留返回时有效候选与当前ID，轮播整轮结束再洗牌且避开上一张；当前指示线按物理帧时钟5秒左到右填满，与切换共用时钟，绘制层读取进度。追加候选不重置计时；触摸/横滑后重计，后台/纵滚/离屏/零倍率暂停。错误方式为每次重组随机换首图、将每帧进度提升到整页状态读取或为所有候选绘制一条指示线。

## 全端展示快照契约

遵守[共享展示快照规则](../backend/quality-guidelines.md#全端展示快照契约)：共享核心无TTL、1000条预算，展示显式接收partial，最终result实时；详情旧资料只读预览，主播放目标不取缓存。各端展示入口按身份/生命周期交付缓存并替换实时结果，分页按范围替换，失败保留展示但不伪装鉴权成功。

- 手机由独立PlayerActivity承载播放，方向锁在该窗口内由PlayerPage页面持有，同页自动/手动切集及换版本不临时请求UNSPECIFIED；新目标未知尺寸保持现有方向，成功起播后只用实际版本尺寸直接改向。手动方向不被元数据覆盖，比例仍可更新；取消、旧controller/目标和leaving阻止迟到写回，退出独立播放器直接finish，不先请求UNSPECIFIED转回竖屏；内嵌测试宿主离页仍释放方向。PhoneEnginePlaybackTest覆盖两内核与旧/新目标迟到响应，真实转屏与首帧另验。


- 手机弹幕使用220dp快捷菜单与280dp样式子页，12dp圆角、深色半透明、无全屏遮罩；子页返回先回一级，外点关闭全部。滑杆松手提交，粗体/合并重复/防止重叠/进度条热力图置于默认折叠的更多设置。DanmakuStyle按核心成功回值更新全局偏好，load/set串行，保存完成revision重置滑杆本地预览（失败及回值不变也要恢复）；未读取成功不可提交。PhoneDanmakuPanelTest验证真实Popup、大字滚动、按键、开关、失败回读和单次提交。

## 手机播放菜单与手动切核

- 宽屏适配：详情头图最小高度取 min(宽×1.08（宽≥600dp时×0.75）, 屏高×62%)，竖屏手机不变，详情简介行长封顶720dp，横屏手机首屏可见片名；平板（宽≥840dp且高≥600dp，非TV）详情改左右分栏：左栏320dp固定海报/标题/评分/标签/播放键（自身可滚，海报高≤min(屏高×54%, 屏高−360dp)，保证600dp高小平板播放键仍在首屏），右栏沿用同一个 `LazyColumn` 承载其余内容，剧照压色铺整屏作背景；评分、标签、播放三块是两种布局共用的局部组件，改其中一处不得只改单栏分支。插件设置单选与设置页同为面板内勾选行。管理类页面（设置主页与二级页、服务器及其线路页、插件各标签页及其设置页、扩展组件、本机文件夹浏览）统一用 `Base.kt` 的 `manageListWidth()`/`manageWidth()` 最宽720dp居中，页内标签行同样收进这条宽度以与内容左对齐；插件自绘页面（`PluginPage`）不限宽。搜索结果海报网格列数走 `posterColumns()`，不得写死3列。媒体库所有分页路径（首屏、续页、整页刷新）都必须按ID去重，分页期间条目增减导致相邻页重叠时重复key会让网格崩溃。首页宽屏（≥600dp，非TV）Hero 高取可用高×50%（260~460dp），标题36sp、Logo 260×80、文字列≤560dp，仍沿用A方案整幅下压暗不另铺标题遮罩；媒体库卡放大到220×124。`PhoneWideLayoutTest` 覆盖横屏873dp、折叠屏673×841、小平板960×600与平板1280dp。列表热路径：传给卡片的菜单列表必须跨重组复用（`rememberCardActions` 已 remember，自行拼接也要 remember），否则可见卡片全部无法跳过重组；页面顶层不得直接读 `firstVisibleItemIndex`/`canScrollBackward`，用 `derivedStateOf` 或放进绘制阶段；插件数据作懒列表 key 必须拼序号兜底，空或重复ID会崩溃。卡片可见性判断依赖 `boundsInWindow` 的父级裁剪语义，不能直接换成只看窗口的 `onVisibilityChanged`。
- 手机播放器所有锚定菜单（更多/音轨/字幕/选集/弹幕等）共用 `PlayerMenu` 默认外观：12dp圆角、`0xEB1D2124` 深色半透明；`PlayerMenuRow` 内容左缘与提示/开关统一16dp，末行传 `divider=false`，面板底部不留分割线。选集时长不足1分钟不显示。播放失败页「返回 / 重试」并排等宽，说明居中。
- 手机播放比例只保留在更多菜单，音轨/弹幕/字幕样式不在更多重复；章节在起播后读取并复用到菜单，空表隐藏入口，失败保留重试。媒体信息从当前内核格式或已有 `player.opts` 白名单取值，未知隐藏，不显示地址/错误原文。
- 手动切核由 `PlayerController.switchEngine` 读取真实状态并共用停止屏障；实际版本和位置、暂停/倍率/音量保留，切核传 `resume_exact=true`（零秒同时传 `from_start`）避免核心续播重算；已提交但时钟未到的seek取目标快照。两向选轨按身份唯一匹配。手选优先于晚到恢复，不写全局内核偏好；当前播放手动切换成功后不再自动回退，失败不消耗回退机会。非 Emby 不提供切核入口，DRM 在停播前拒绝切换并保持原播放。
- 快照读取后与停播回执后核验页面仍有效；停止一旦提交不可用取消提前释放锁，离页收尾/新页起播等待。读取失败保留旧内核，停止失败明确失败页；不能继续显示可操作的假播放状态。
- 顶部时钟按屏幕居中，单侧安全区和低电量图标不改变时钟位置；电量低于20%才显示电池图标内数字。网速在控件展开或缓冲时显示，缓冲时保留实测0 B/s，未知读数不画空胶囊；正常播放仍隐藏零值，低于1KB/s显示B/s。状态和网络采样在STARTED期间运行，不因OSD收起停止；PiP/离页/失败/转屏等待不显示。
- `PlayerControllerTest`、`PhoneEnginePlaybackTest`、`PhonePlayerMenuTest`、`PhonePlayerOsdTest` 与网速格式回归覆盖停止等待、双向切核、轨表晚到、菜单去重、状态显隐和几何；真机解码/切核首帧与系统电量仍需独立验证。

- 外挂ASS/SSA与SRT/VTT统一通过Media3 SubtitleConfiguration进入轨道表，配置ID按媒体实例唯一，不含地址。1.11.0外挂SubtitleExtractor交付无初始化头的完整脚本，LibassParser交给assOpenFile；内封仍走头与样本事件。只激活选中轨；切到头尚未到达的新轨时先关闭旧轨，保留wanted供新头补开。关闭/重置要清掉位图残帧，监听重挂立即同步当前选择。不得恢复首个ASS旁路下载或静默丢弃大脚本。MPV菜单可见时更新轨表，Media3菜单监听onTracksChanged，晚到轨道可选；不支持轨道标明并拒绝假成功。
- 分集单页请求限20秒，超时保留前页并允许重试；重复/空ID页报错，不无限追加。面板支持缺season_id时按series_id+已知season_no查询并过滤，原始列表保留分页游标；已知season_id不因分集缺season_no丢掉当前集。当前集缺失仍报错，不能误判季末。

- Media3 ASS 的 NONE 字体提供者不会解析 Android 泛族名 `sans-serif`，必须传入实际可读的系统字体文件兜底；原样式/附件字体仍优先。附件先到可能已创建 ASS_Library，开轨的字体目录设置不能放在“首次建库”分支内；追加附件重建选择器时保持同一兜底。渲染返回1只说明位图已更新，透明帧不证明字幕可见；回归断言实际非透明像素、移动特效、内封/外挂、结束清屏，设备测试见 AssRenderTest。
- 手机字幕菜单按运行期 codec/MIME 显示 ASS/SSA、SRT、WebVTT、PGS 等及文本/特效/图形类型，Media3 转码轨从 codecs 读取原 MIME；未知格式不猜测，MPV外置标记取external。第一行已显示语言时第二行不重复。时间保持顶部固定，网速位于第一排操作按钮下方，不能放在按钮上面；窄横屏/竖屏大字需断言网速和倍率按钮不重叠。

- Media3 1.11.0 的 `DefaultMediaSourceFactory` 有外挂配置时创建一层 `MergingMediaSource`，全部轨道（含主源内封）ID 被加上子源索引。`syncLibassTrack(player, tracks, subOff)` 仅在当前媒体有外挂配置时去一层前缀，再与解析器注册的原 ID 匹配；如 `0:original:track` → `original:track`，无合并时必须原样保留。合并的空后缀恢复原 null ID 的 `ass` fallback。禁止无条件取最后一段或按语言/名称串轨。`ExoAssMergeTest` 必须用真实工厂/解封装覆盖外挂切换与有无外挂的内封开轨；仅手工拼 ID 的状态机测试不能代替合并边界回归。

## 手机独立播放窗口

- MainActivity保留浏览与TV形态入口；手机所有Route.Player经PlayerLaunchPage启动非导出的独立Activity。已知实际版本宽高比选择sensorLandscape/sensorPortrait入口，未知默认使用sensorLandscape入口，不额外阻塞起播查询；运行中尺寸/手动方向仍由PlayerPage处理。
- 横/竖入口共用PlayerActivity与原播放器页面，Manifest处理orientation/screenSize等配置变化，黑色窗口背景与无Activity切换动画；不能把配置测试等同于系统旋转或首帧实测。
- 完整Route.Player通过内部Intent传递，包括src、fromStart、subtitle与点击计时。交接页通过ActivityResult返回原导航栈，不重复启动；播放器退出直接结束窗口，原浏览页不参与转屏。
- Application持有唯一AppState及进程级事件作用域，浏览/播放共享会话和缓存；新进程恢复先boot再播放。通知只携带现有窗口component与REORDER_TO_FRONT/SINGLE_TOP，不携带旧播放路由重播；失效通知入口没有路由时直接结束。Home及按钮PiP、通知权限归PlayerActivity，后台播放仍沿用PlaybackService。
- 插件壳事件仅由最近恢复的宿主处理，WebView容器归各自Activity；全局插件对话框仅前台宿主呈现。播放器插件导航经Activity结果回到浏览栈执行，不能直接在后台弹出交接页或启动第二套播放。MainActivity深链仍沿用singleTask，打开账号深链会结束其上的播放器。
- PlayerActivityTest / PlayerLaunchPageTest覆盖Manifest、完整参数、一次启动与返回、通知恢复/失效、前台插件确认、退出停播和同一窗口配置切换；PluginShellHostsTest验证两窗口请求仅处理一次及WebView独立清理。真实转屏动画、解码首帧、PiP和进程回收恢复需要设备另验。

- 手机服务器长按设置聚合主服，与主进度服独立。`AppState.aggregatePrimary` 从账号表读取；手机扩展调用显式 `server_id`，切当前服不改变扩展来源，未指定保持当前服。榜单以 `include_disabled=true` 取得完整目录，按来源/榜单两级切换；未开启明确标识。图片三处（领奖台、列表、日历）均经 `proxiedImage`，不能直接请求受鉴权的上游地址。日历当前无精确时刻，显示未提供。`PhoneDiscoverUiTest` / `PhoneManagementUiTest` 验证接线与交互；其它宿主默认参数不变。

- MSG 周历仅显示服务端 `broadcast_time`（HH:mm）且 `timezone=Asia/Tokyo` 的明确时间；保留 `time_source` 来源，未知仍显示“未提供”，不从星期推算播出状态。榜单体系名称包含红果、黄果 AI；是否可见由服务端目录决定。

- 手机PiP直接移除播放器覆盖层（含OSD退场、菜单、解锁、加载与片尾提示），保留视频Surface组合，往返不得重复起播/停播。手动小窗成功后启动系统桌面，避免原浏览窗口的黑色交接页成为背景；Home手势不重复跳转，失败不跳桌面，ActivityResult栈保留。PlayerActivityTest验证桌面Intent/失败及播放不中断，PhoneEnginePlaybackTest验证小窗覆盖层立即消失与往返；系统PiP动画/桌面恢复需真机。播放器加载显示区120dp；片尾卡在控件展开时横屏紧贴底部快捷按钮上沿，与单集标题同行并为标题预留空间；竖屏避开整个底栏。右侧安全区内留12dp，高度不足360dp的横屏在控件展开时留68dp以避开倍率按钮；收起时在底部安全区上方8dp并渐变至50%不透明度，不改变滑杆及按钮几何。

- 手机隐私空间顶部仍显示服名，不展示空间名称或退出提示；点服名/首页返回退出。`LpTheme(privateSpace)`统一应用深浅紫色底、表面及强调色，MainActivity和独立PlayerActivity均接入；普通主题偏好不变，退出恢复原色系。网页内容不强制改色。`PhoneThemeTest`覆盖深浅空间切换、导航入口、无跨服聚合请求和文字对比度，`PhoneHomeRefreshTest`覆盖长按进入与点服名退出。

- 手机私密流光由 `PhoneRoot` 内唯一 `PrivateNeonHost` 提供 `State<Float>` 相位，`LpTheme` 下发空间标志；固定10秒基础时长，由Compose应用一次系统倍率，禁止同时手动乘LocalMotionScale；仅RESUMED且倍率>0推进。绘制层读取相位，不能每帧重组页面；后台/零倍率静态，普通空间相位为空。`privateNeonBorder(corner,inset,enabled)` 放在形状clip之前，青红渐变只旋转着色器，轮廓和多层透明宽线缓存；明确按钮/筛选胶囊/导航外壳接入，纯图标用 `privateNeonIcon`，不能挂通用pressable以免给海报或服名套框。MainShell播放交接页禁用外框，独立PlayerActivity不装宿主。`PrivateNeonTest`断言真实像素移动、无逐帧重组、后台/前台、零倍率、退出和无宿主不发光，以及按钮亮边和图标柔光；`PhoneThemeTest`验证服名底色与深浅切换，`FloatingTabTest`验证私密流影往返。真机GPU/耗电与屏幕安全区另验。

- M3 FilterChip的最小交互布局高于可见表面；荧光不能画在整块最小触摸区上。MediaFilterChip在外层minimumInteractiveComponentSize保留布局空间，仅内层Surface关闭最小布局并按可见轮廓画光，点击仍由原控件接收。PrivateNeonTest同时断言深浅像素贴边和扩展区真实点击；倍率回归同时设置Compose MotionDurationScale与LocalMotionScale，验证0.5/2倍的一圈分别5/20秒，避免仅覆盖默认倍率得到假绿。

- 手机首页滚动后的顶栏保护仍须半透明向下渐隐，禁止切换为纯色硬边；叠图服名与设置图标保持浅色。PhoneHomeRefreshTest用深浅主题实际滚动、上下像素变化及设置点击回归。

## 手机流影媒体槽与回调

- 收藏交接后的首轮随机候选排除刚选中的item_id，不排除其它收藏；后续轮次仍可正常随机到该视频。过滤后分页使用原始nextOffset，不用可见rows.size推进；仅重复项的页有后续时继续读取，无后续时保留空态及回滑，不自动重取新轮次。PhoneLiuyingTest覆盖混合碰撞、过滤空页续取及单项库停止请求。

- `PhoneLiuyingFeed`使用VerticalPager，条目画面、标题与动作随页移动，固定顶部导航；只向当前落定页开放操作/无障碍语义。三页候选与前二/当前/后三媒体窗口分开有界，分页响应在停止拖动后以当时稳定key接入；收藏取消当前项暂留，离开后按当前key修正索引，不按旧数组位置跳片。非空末页保留可滑入的边界页，当前页起播即取后一批，more=false时提前以新seed、offset=0续轮次，已存在后一页不继续拉取，不依赖自动连播开关；初始占位与末尾占位key不同，边界响应以新页保留窗口并显式定位，避免Pager跟随占位key继续跳页。失败由按钮按原请求重试，空页保留回滑。
- `LiuyingPlaybackSession`的正式actor只保留一个在途提交及最新目标；不可撤回的Play必须接收回执并精确Stop，离页本地立即释放、核心收尾登记pendingStop。前后台、屏幕解锁、焦点、用户意图及拖动分别门控，旧Warm回执按revision补撤销；倍率保存完成后才换片；切换先取消并等待在途准备收尾，空目标清理准备窗口。只采样本地进度，不再调用liuyingProgress，Playing/Stopped保留。saveSpeed/stopCurrent可能挂起，返回后须在Play前再次核验foreground/unlocked；后台不提交新Play，保留最新目标待恢复。
- `LiuyingMediaSlots`最多两个Media3实例，当前与备用复用，不按条目重建宿主；备用无声、无焦点、无正式上报。当前READY且按倍速折算缓冲达到3秒（或缓冲到尾）即可warm，低于1秒/卡顿暂停，不等片尾；只有获得warm许可才将未来源交预载管理器；暂停未完成预载后恢复同proxy、重建媒体源revision，失败不无限重试，备用解码失败退单槽。预载失败与主动中断互斥：onError清interrupted/samplesReady，暂停、恢复和未来调度均排除failed；只有正式选中时允许重建失败源。实时/未知时长源不做未来内容预载。有效窗口内Stop返回retained=true才保留同一媒体源和代理；播放器释放period后samplesReady清零，重新准备片头，不把manager引用当作样本仍在。未获许可且没有已完成样本的源使用NOT_PRELOADED，避免选轨阶段读取已退回的代理。
- Media3 1.11.0以实际AAR为准：预载status不能null，NOT_PRELOADED清样本，setCurrentPlayingIndex后显式invalidate。Kotlin的`LoadControl by base`不转发Java default方法，实际生命周期/选轨/缓冲/启动方法须显式覆盖；只编译不能发现起播时的not implemented异常。
- 当前槽与管理器各16MiB目标，备用16MiB，未来样本目标合计32MiB不是进程硬限；codec、复制与正式播放内存另计。每个源的mediaId/revision唯一；实际首帧及播放器错误按Analytics事件所属媒体核验，首帧还须核对Surface。READY不算首帧，旧Surface销毁不清新绑定；仅正式槽拥有全局Libass，未接管/旧会话释放不可清别人的字幕。
- `PhoneLiuyingTest`/`PhoneLiuyingChromeTest`验证真实Compose输入、Popup、短屏大字、倍率/静音、分页锚点、收藏成员、慢Stop下50次选择、迟到Play退出及屏幕广播/焦点接线；`LiuyingMediaSlotsTest`验证100次提升最多两实例、静音/压力释放与错代首帧/错误。JVM中的状态注入不是解码证据；真实双槽、Surface系统时序、后台零网络/声音和同条件P50/P95仍须真机验收。
- 永久失焦/耳机拔出后不因迟到GAIN自动恢复；用户主动选择新目标时清除阻断并在前台解锁条件下重新申请焦点。同目标的候选窗口刷新不能清除手动暂停；`explicitNextAfterPermanentFocusLossRequestsFocusAgain`覆盖主动切片恢复。

## TV共用播放交接

- TV的Route.Player经PlayerLaunchPage启动横屏PlayerActivity，由TvPlaybackHost挂既有TvPlayerPage；传递版本、从头播放、数据源参数及字幕身份，不启用手机PiP。文件浏览显式传localEntry，走source.play，不按Emby条目播放或回传Emby刷新ID。
- 正常退出先登记stopIn，再回传实际末集；伴侣与播放失败线路导航回共用浏览栈，不进入旧TV浏览页。添加服务器保留TV扫码和本地目录选择器。PlayerActivityTest覆盖字幕、切集、末集回传及本地来源。

## 插件失败换源交接

### 1. 范围 / 触发
手机和TV插件起播失败共用候选弹窗；不扩展`officialRoute`或插件公开导航权限，不改核心命令和持久化格式。

### 2. 签名
`SourcePlayFailed(raw, pos, onSwitch: suspend (SourceSwitchTarget) -> Unit, onClose)`分离候选选择与导航。内部密封目标为`Line(Route.Player)`、`Source(Route.SourceDetail, fromServer, fromItem)`、`Emby(serverId, Route.Detail)`。

### 3. 契约
- 同源线路复用当前播放器换目标，先停后播、不新增Activity；手机控制器身份包含`route.src`。起播失败时优先有效正进度，否则保留`src.resume_secs`；跨插件沿原线路单集`index`选择目标集。
- 跨源先等待停止，再由`PlayerActivity.SOURCE_SWITCH`回传序列化目标。`PlayerLaunchPage`校验当前entry与空间代次后执行`source.linkSwitch`或切服/刷新会话，移除失败交接页再导航；不能直接在仅注册Player的播放导航图内打开详情。
- `SourceDetailPage.autoDone`用`rememberSaveable`保存单次自动起播标记，返回源详情不得重播。同源停止若遇取消须继续抛出`CancellationException`，即使底层迟到返回也在更新目标前`ensureActive()`。

### 4. 校验 / 错误
候选层级及必要ID缺失显示错误；候选查询用`displayBlock`消费partial，取消不向已关闭弹窗写入，错误可重试且不伪装为空。私密空间不组合候选、不接受源播放和换源结果；非法JSON、Line结果、非成功结果及失效entry/空间不能触发跨源副作用。关联/切服失败回原浏览页并反馈，不打开错误目标。

### 5. 正常 / 基础 / 错误案例
第4集入口携带125秒而当前进度为0时，换线路仍带125秒；跨插件自动找第4集，返回停留源详情；候选取消、慢停止、切服失败均不启动迟到目标。

### 6. 必要测试
`PlayerActivityTest`验证手机/TV停止先后、续播、专用结果、取消与候选重试，TV按真实方向/确认/返回键关闭重开和选择；`PlayerLaunchPageTest`验证集序、返回不重播、慢停止、错误结果及空间隔离。真实插件取流和实体遥控另验。

### 7. 错误与正确做法
错误：仅复用已有弹窗、在播放Activity内直接跳详情，或用`runCatching`吞取消后继续换线。正确：共用候选UI，线路留在原播放器，跨源经专用内部结果交还浏览栈，并覆盖迟到响应和返回路径。

## TV共用浏览审查回归

- 账号来源类型读取核心account.listAccounts的source_kind；AppState.boot从同一账号快照发布localSource，activeSource仍只表示插件源。TV本机源首页提供打开文件夹入口，不能落入Emby首页请求；切回Emby时清本机状态。手机首页路径不变。
- TV文件浏览仅列is_dir或is_video，不能把任意非目录文件都送入播放器。遥控返回在子目录先退一层，根层再退浏览路由；屏幕返回与遥控返回的层级语义一致。
- TV换条目或版本清除旧详情字幕预选，让同剧手选字幕按新轨身份恢复；同条目同版本重播不因本次修复丢掉有效预选。测试必须实际走详情预选→手选→版本菜单→新轨恢复，不能只断言参数被清空。
- SharedTvBrowseTest覆盖实际TvRoot本机首页、来源切换、目录返回及过滤；TvTrackTimingTest覆盖换版本后手选字幕恢复。旧测试只覆盖Emby首页和无预选切集，无法证明上述路径成立。
- TV切集与换版本沿用本次入口的initialMode（显式engine或进入时偏好），auto仍由PlayerController解析；不能在每个新target重读全局偏好，也不扩大为永久保留手动切核/自动回退后的实际内核。非Emby保持MPV，停止屏障不变。
- TV网格默认列数按LocalWindowInfo容器dp扣除TvPageSide双侧边距后调用posterLayout(widthDp, heightDp)，960x540默认七列；插件明确列数覆盖保留。电影详情TV单列主操作在版本/音轨/字幕参数之前，首屏完整可见；工具栏向下显式链接可见且已就绪的主操作，合集、加载中和未挂载目标不设此边。手机排列不变。VisualAlignmentTest检查片名、年份和播放按钮实际边界，SharedTvBrowseTest验证实际窗口列数/插件覆盖与初始焦点沿方向键起播。
- TV分集标题只随itemId清理，不因同条目attempt/engine重置；规格由当前versions与player.play回传media_source_id实时匹配，未知身份不显示首版本兜底。换集清列表、每次起播清身份，异步写回前检查取消。TvPhoneParityTest覆盖偏好晚到、切核/重试、换集及版本/起播两种返回顺序。
- TV首页通过LocalTvHomeRefresh登记原onRefresh，壳按导航entry持有，DisposableEffect离页仅撤销自己的回调，rememberUpdatedState读取当前刷新闭包。SharedTopNavigation仅在Home显示，TopNavigation不再提供独立刷新动作，首页栏目确认执行回调，获焦仅切页，不改既有栏目索引。刷新中保留首页栏目与焦点、按原refreshing防重；普通刷新能力、私密跳过能力刷新，本机/插件首页不登记。SharedTvBrowseTest/TvPrivacyTest/PhoneHomeRefreshTest覆盖真实按键、挂起防重、离页返回及账号/空间切换。

- 手机榜单用 PageCache 保留当前分类列表、页码、末页和追加错误，仅同返回栈已完成请求可跳过首屏；新进入、失败、取消或主动刷新仍加载。精确匹配触发主服切换时只交接该榜单快照，普通换账号/空间仍清缓存。PhoneDiscoverUiTest 覆盖往返滚动、续页、主服切换和新导航重载。

## 手机榜单关联与积分契约

### 1. 范围 / 触发
隐私聚合快捷入口与手机榜单展示、点击；不改变图片权限、TV 布局或未入库播放能力。

### 2. 签名
`emby.extRankingItems(category_id,page,server_id?)` 返回榜单数组；`emby.extResolve(source,external_id,media_type,server_id?)` 返回唯一 `item_id/item_type`。命令名及 ABI 不变。

### 3. 契约
隐私流影/排行榜入口同一 Row、等宽，仍在普通快照读取前返回。前三名在整卡绑定唯一 `pressable`，海报内部不再嵌套点击。JavRanking 番号取完整字符串 `external_id`，核心以 `*int64` 保留可选 `score`；缺失/null 省略，0 保留。手机前三名及普通行显示番号与“战力 N”，不渲染 JavRanking 的 rating；其它来源评分不变。点击只传来源身份，不传标题或积分；隐私固定当前服，普通空间沿用聚合主服。

### 4. 校验 / 错误
无匹配 `E_NOTFOUND`、多匹配 `E_INVALID`、请求失败均通过原反馈入口显示真实原因，不导航、不清榜单或改用标题搜索。匹配与权限必须实时查询，不能信任展示快照。

### 5. 正常 / 基础 / 错误案例
红果匹配 `hg-group-*` 进入合集，黄果匹配 `hga-work-*` / `hga-group-*`，JavRanking 匹配 `nfo-*`；返回不重取已完成榜单。积分 0 显示“战力 0”，旧快照没有 score 时仅显示番号；歧义不得任取第一项。

### 6. 必要测试
`PhoneAggregateCacheTest.privateShortcutsShareOneRowAndKeepOriginalRoutesWithoutAggregateRequests` 断言窄屏同行、等宽、两路由和零聚合请求。`PhoneDiscoverUiTest` 真实触摸前三名标题/海报，断言单次解析、精确身份、当前服/主服、返回与失败反馈；深浅色窄屏及1.3字号覆盖0、大整数、缺失/null。核心 `TestExtensionRankingScorePresence` 验证 HTTP→JSON 字段存在性，`TestExtensionIndependentCatalogIdentity` 验证来源身份。模拟渲染不代替真实 MSG 和设备验收。

### 7. 错误与正确做法
错误：仅海报可点、把缺积分补0或按评分格式化、用中文标题找近似作品。正确：整卡单入口、可空整数透传、按来源ID及类型实时唯一匹配。

## TV 实拍反馈浏览契约（2026-10-28）

- 首页上返导航只保留一个页面作用域的 Job，重复 Up 不重启，其他按键/内容失焦/切首页归零取消旧请求；普通焦点滚动继续由 Foundation 处理。正式 `TvRoot` 浏览入口统一提供 `TvBringIntoViewSpec` 两轴30%停靠，不依赖 `FEATURE_LEANBACK`；首页不另建Provider，手机与独立播放器不继承，详情首屏仍以 `ProvideEdgeSpecWhen` 覆盖。`SharedTvBrowseTest` 比较等起点、等时长的单按/连按轨迹，覆盖新方向无法移动焦点时也取消旧请求。
- TV媒体轨、详情季/集/演职员、库入口、搜索/分类/榜单/日历选择行、榜单前三名、插件管理标签、设置分段/代理预设及宿主插件语义横轨用 `tvFocusRow` 只拦截整个焦点组的左右退出，保留组内屏外项查找及上下跨行；不能在每张卡片上禁止左右导航。返回焦点区分顶栏与内容，转场中的临时聚焦不得覆盖离页前的导航意图；从卡片进详情仍回原卡片。
- 详情播放/播放选项向上进入工具组时，使用组的Enter语义从行首选择可用项；定向Up直接进入组可能落到行尾换源。覆盖普通/Leanback及首项保存中禁用的情况，仍保留工具向下回播放和参数目标身份验证。
- 详情回首屏播放也复用单个页面 Job，连按不重启，非上键或内容失焦取消迟到聚焦；多个入口调用同一动作。懒列表滚离首屏可回收播放节点，回归应验证无播放焦点及最终完整回顶，不要求离屏节点仍挂载。
- TV单行 `LpField` 的上下键交给焦点搜索，左右仍移动光标；多行保留原生上下编辑，手机键盘行为不改。网格与任意插件自定义布局不套横轨边界。
- 最新视觉契约见 `docs/go-migration/UI_TV.md` 的实拍反馈修订，覆盖旧固定顶栏、内部缩放、片名反色条与五列约定。上述手机入场/形变及图片淡入条款不适用于 TV；TV 直接显示已解码图片、关闭 `homePosterEntrance/posterEntrance`，不修改缓存权限和身份。
- 共享 `tvFocusScale` / `tvPosterFocus` 只放大绘制，不修改布局和焦点搜索边界；海报类关掉 `pressable(tvScale)` 改用 `tvPosterFocus`，支点偏下少压片名；顶栏只能向上进入，内容区左右走到尽头不得误入顶栏；专用 `TvFrame` 控件明确传入 TV 模式，不假定其提供共享 `LocalTvMode`。横轨为外扩预留垂直空间。
- 栏目标题仅在有 `onMore` 时可聚焦；Down 显式滚回横轨零项、等布局后聚焦首卡；请求由标题单个Job管理，重复Down合并，非Down/失焦/焦点组退出取消。父页预览可提前处理Up，不能只靠子标题按键或失焦取消。回归覆盖首卡完全离屏后Down、快速Down→Up/Left及首行上返的中间帧。非操作标题不能为回调添加无意义焦点。
- 首页切栏目回顶与详情退回恢复位置分别处理；`tv.home.top` 仅由切首页发出。根页先检查前一 entry，再 pop，成功退页及 entry 改变清退出计时。
- 详情及首页背景沿用取色缓存，渐变末端主色 8% 叠加主题底色；首页无轮播不显示遗留色调。详情 TV 字阶、操作尺寸及分集宽度独立适配，手机与播放页尺寸不变。

## TV 详情影院布局与媒体选择

- TV 在同一 `TvDetailHero` 内排布标签、操作与 `optionsBlock`，手机仍在原列表位置调用共享媒体选项，不复制请求或选择弹窗。外部参数卡可读取当前值不代表可以操作：`PickRow.onClick` 是焦点/箭头的统一依据。
- Series/Season 参数只使用真实 nextEp 对应且 `mediaItemId == playbackItemId` 的结果；展示快照及 shownNextEp 不得成为选版/字幕起播真源。目标变化清 TV 选择弹窗和版本选择，字幕记忆按目标与版本双重隔离。
- 参数组 Down 不能只依靠横向几何找到紧凑季入口；需要入口实际挂载且可聚焦才重定向。空季/空分集应继续到演员等后续内容，不能吞键形成焦点陷阱。Up 保留完整回顶，插件接管主操作时不能请求不存在的播放按钮。
- TV 简介只在头部调用一次完整 `DETAIL_OVERVIEW` 锚点，不能同时保留摘要和下方正文。按实际文本布局溢出决定全文入口；全文滚动和返回恢复必须实测，不能靠短文也添加空操作。媒体目标切换仅关闭版本/音轨/字幕弹窗，不因媒体刷新打断阅读简介。参数类别保留语义名称但不常驻显示，手机不跟随删改。
- 正式尺寸与配色以 UI_TV 最新详情章节为准；合成预览不能覆盖真实能力、数据缺失与大字号行为。

- 手机PlayerControl无独立圆底，倍率步进器无胶囊底；保留48dp命中、按压反馈和偏移暗影，亮帧需实际像素验证。横竖屏统一上加下减，每次0.1，以百分位整数运算，旧0.25–4.0范围不变；点选列表0.75–3.25、每档0.25，菜单136dp。PhonePlayerOsdTest和PhonePlayerMenuTest验证边界、透明背景、亮帧及锚点。
- 弹幕“区域替代三种行数”不等于删除其它常用设置：播放页保留区域、不透明度、字体大小、速度四项。调整区域一次提交area及三类行数上限20，解除隐藏旧值限制，不迁移或清空其它偏好；读取未完成禁用，保存失败回显原值。手机松手提交，TV左右步进。
- 弹幕绘制用实际Paint测宽并按布局、字号和粗体缓存，不能将核心估宽用于居中或离屏判断。area_height与res_y同单位，三种模式均受其限制；底部固定贴区域下沿。DanmakuLayerTest覆盖中文居中、滚动尾部、区域、同布局窗口高度变化与粗体；界面回归不能只断言删除控件，要保留常用能力的真实提交与回显。
- 四项全局样式只依赖偏好读取成功，不依赖当前语料；TV关闭弹幕仍可预设。输入立即局部回显，相对步进按最新意图累计；保存仍串行，在途期间的待写字段合并为最新值。旧回执不得覆盖新意图，失败仅回滚没有更新意图的字段。PlayerStyleSlider拖动中不接受其它保存回执重置临时值；回归须包含跨项拖动、慢调用连按、关闭面板和失败回滚。

- 弹幕更多设置四个布尔项复用DanmakuStyle.set，按即时意图取反，慢保存连续点击不能丢步。读取成功后总开关关闭仍可预设。手机折叠、TV子页返回聚焦更多入口；热力图请求与绘制共用DanmakuHeatmap，绑定语料/偏移/时长/两开关，先清旧值、取消及拒收迟到结果。PhoneDanmakuPanelTest、TvFocusTest和DanmakuGeometryTest覆盖保存/失败/重开/遥控/实际密度像素。

- 手机/TV隐藏态底线共用PassiveProgress，已播段读当前Lp.colors.acc，深浅及色系即时跟随，仍为3dp只读轨道。
- 弹幕不透明度直接传入绘制，不重取布局/测宽；核心透明度及热力图变更不递增几何revision，在途测量回复采用最新透明度。同语料改几何保留旧画面直到新测量完成，语料代数/窗口变化清旧测量。不得再把所有样式修改视为全量重排；像素及命令计数回归不等于真机帧率验证。
- TV有已加载弹幕时不主动匹配低于50Hz的显示模式，优先可用的高刷新整倍频，否则恢复入播偏好；关闭弹幕恢复原匹配规则。补帧及迟到响应约束保持，回归必须走真实播放页的开关与窗口属性。
- TV普通倍率按源帧率乘内核实际倍率匹配：Media3读playbackParameters.speed，MPV复用player.opts.speed，不读保存中或临时长按时的菜单倍率、不增加查询。长按期间取消在途匹配并保留模式，松手且在途倍率操作完成后再校准；等待期间两核也不能将临时倍率写入菜单恢复值。不支持的目标帧率恢复原偏好。TvRefreshRatePlaybackTest覆盖两核、23.976×1.25、无匹配、弹幕下限、无效倍率、长按迟到响应、慢恢复与重复长按；窗口请求通过不代表电视实际切频或解码吞吐达标。
- TV倍率面板不提前写显示值；页面提交到内核后回显，在途保存后的新选择合并处理。MPV定期状态携带实际speed，TV在非保存/非临时长按时校准。player.setSpeed的内核失败必须返回错误，不得使用吞错误的setProp伪装成功；失败恢复操作前实际倍率；回滚失败不得伪装已恢复，Media3采样同样读取实际倍率。停止失败也必须等待已登记的偏好保存，包括在途写入之后合并的最后选择；离页/停止后只保存已接受的偏好，不向旧内核应用倍率。TvPhoneParityTest覆盖两次选择后立即退出的成功/停止失败路径与保存屏障。
- Android MPV的初始化和Surface重绑共用gpu-next,gpu顺序，优先使用支持杜比重映射的libplacebo渲染，旧GPU初始化失败保留普通视频回退。回退gpu不代表支持杜比；已有DolbyAutoSW与Emby识别规则保持。真实TV色彩、软解性能和驱动兼容必须设备验收。

- 手机/TV共用弹幕首批准备：完整候选先测当前位置加30秒前缀并显示，同轮缓存后台补齐整集；测宽JSON和布局解析在Default线程。前缀保留全部历史，不能独立截取附近候选重排。空窗由位置采样判断休眠/提前唤醒，position不能成为持续帧循环的key；暂停/缓冲仍对齐真实位置。DanmakuGeometryTest覆盖首批绘制、慢速寿命、空窗与迟到响应。

- TV发行包复用宿主platform=android_tv；无有效自定义mpv配置时显式使用fast中的轻量缩放参数并保留fruit抖动。手机/桌面与自定义配置不注入，VO/杜比软解/硬解/字幕/Surface重绑不变。缩放可能更柔和，性能与画质需目标电视验收。
- 手机/TV媒体信息显示内核实际倍率；MPV从既有player.opts读取两类累计丢帧快照，非负整数（含0）才显示，不相加、不新增轮询。Media3不混用MPV计数，未知值和地址/错误原文不显示。TV只读面板可上下键滚动，返回键仍回更多；TvPhoneParityTest验证末尾信息可达和两核隔离。

- TV确认键开始的长按/计时必须在页面按键入口先完成松键清理，后来打开的面板或失败界面不能吞键，也不能将该松键当成菜单点击。自动切核快照保存正常倍率，仍按住时在新核恢复临时倍率；新核起播前菜单正常倍率即取切核快照，不能在慢恢复回执期间短暂退回保存偏好。回归覆盖菜单中松键、切核停止屏障内松键、切核完成后松键及正常倍率与保存偏好不同时的慢恢复。
