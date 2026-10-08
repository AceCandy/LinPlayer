# 实施计划

1. 产品参数确认后分出独立子任务，先播放器去底/只读细条，再字体/壁纸/背景，再动效，最后共享持久缓存；母任务统一验收。当前仅planning，未start。
2. 实施前完整读即将修改的实际代码、相关注释及HTTP/字体/Coil依赖具体版本；不复用探子不完整切片作为代码修改依据。
3. 先复现OSD显隐和缓存跨重开缺失；有行为分支的回归必须在原故障/忠实断接线下红，再改生产代码绿。
4. Android执行对应PhonePlayerOsdTest/PhoneEnginePlaybackTest/PhoneThemeTest/Settings/Detail/Nav/NetImage相关回归及真实截图；字体加载与模糊至少API24和当前API覆盖。检查PiP完整接线后实现细条抑制。
5. Go缓存用真实HTTP Range上游与跨进程恢复测试；关闭/鉴权隔离/版本替换/ETag弱与缺失/半写入/损坏/LRU/全局预算/在播清理/并发cancel必测，race检查prefetch/player相关包。
6. 核心门禁bash scripts/check-core.sh；配置/命令变化同步COMMANDS、生成绑定并check-bindings；Android参数门禁；独立只读复核，按出处主线程点验。
7. 正本更新与隐私检查；字体原始资源及release增量预算验证，不擅自宽松包体门禁。
8. arm64手机release打包/签名v1/v2/v3/ABI/播放库/DEX/最终包一致性；没有TV真机或性能实测时明确未覆盖。清临时文件和服务，不提交/推送/归档。

## 风险文件
core/net/prefetch/{cache,prefetch,serve,resolve}.go与core/player/{warm,playback}.go负责生命周期；core/config/prefs及core/paths/system负责配置与清理；Android PlayerPage/SettingsPage/ThemePicker/PhoneRoot/UiPrefs/Theme/Motion/Cards/DetailPage及字体res和许可清单。变更范围以每子任务根因和测试收敛，不重排相邻代码。

## 第三轮实施结果（2026-10-08）

- 整卡15%可见触发12dp上浮与淡入，rememberSaveable保留已入场标记；按压收敛为0.96，普通页轻位移、详情页淡入。
- 新PosterMotion共享范围包住手机NavHost，entry+独立卡片token匹配真实海报，重复资源仅选中卡参与，pop期间保留关联，退出栈或切账号清理；无匹配/零倍率正常渲染。
- SeriesHead/EpisodeHead前景共享与来源预览已接线，真实详情请求挂起时亦可转场；既有轻视差保留。
- 图片底层占位保持绘制，上层alpha递增形成交叉淡入，避免两层反向alpha叠加导致中间变暗。热缓存直显，URL切换同时隔离painter及collectAsState，防旧Success短暂污染新请求。新增层保持居中，首页缺封面名字布局回归恢复。
- 独立只读审查后补账号隔离；本批未改变播放器内核/媒体缓存/服务端实现。精确放行官方Android依赖仓库与OFL许可站的公开域名，保留所有凭据检测。
- 82项相关回归0失败：PhonePosterMotionTest 7、PhoneMotionTest 4、PhoneBrowseUiTest 25、PhoneDetailOptionsTest 12、PluginNavTest 5、PhoneHomeRefreshTest 17、PhoneLibraryUiTest 6、PhonePlayerOsdTest 6。新渲染测试采用实际像素及手动时钟；URL切换通过显式快照通知同步重组后量帧，不能用测试调度延迟推断产品残影。
- 忠实故障注入：断开sharedPoster、提前移除占位、把seen从rememberSaveable改remember，各自中间几何/混色/Lazy回滚测试失败（3红）；恢复后相关回归全绿。Android参数检查256处、全工作树含未跟踪文件的隐私检查1635文件及diff空白检查通过。
- 未验证：Android真机帧率/快速手势观感/系统适配、TV真机；缓存秒开耗时仍需此前真机日志，此轮不宣称已解决。不提交/推送/归档。

- 手机最终APK：80,371,684 bytes（76.648MiB），SHA256 `531877e10ce30b327943915ad8b829292859de4f3edc451e92cb88c0f56d67c3`；apksigner强制min-sdk21核验v1/v2/v3均通过，arm64-v8a 13SO、80MiB门禁通过。R8 mapping定位实际/合并代码owner后在最终DEX核验（排除非实体的$$compose行号映射）；最终包包含本轮动效，TV旧包不作为本轮成果。

## 第四轮实施结果（2026-10-08）

- 只改两处生产行为：sharedPoster空闲源卡返回原Modifier，实际链接指向的源/目标才进入共享布局；SearchPage将巨大网格Column改为原LazyColumn中的三卡懒行，保留间距/缺格/点击历史/分集横轨及聚合。行序号+首项身份保证重复ID不撞key。
- 原实现新增两回归均红：首屏60caption全部组合，30空闲卡全部挂共享modifier。修复后同尺寸测试60→12组合、30→0共享modifier；滚动末行/重复ID/点击及无额外查询也验证通过。
- 84项相关回归通过（PosterMotion 8、Browse 26，其余上一轮同组50），原共享中间几何/实际详情挂起/返回/图片与入场记忆保持。Android参数256处、全工作树1636文件隐私扫描及diff检查通过。
- default探子只读独立复核两处；主线程复核并加行序号处理重复结果。对异步导航登记风险核查当前各MediaCard回调为同步nav.navigate，未增加未触发的补登记机制；搜索原有查询/恢复逻辑不扩大修改。
- 项目SDK adb检查无设备，已关闭本轮启动的adb服务；没有真机frame trace，因此以上是结构负担减少，不宣称用户卡顿已消除。图片尺寸/后台blur成本当前仅候选，不以未证实归因更改处理路径。继续等待操作场景/机型与新包安装确认。

- 第四轮最终手机包80,371,692 bytes（76.648MiB），SHA256 `edac8665b000cb58242c1906f16b1b350a9ce43f41d179d78756807c961b09de`；v1/v2/v3、arm64 13SO、包体门禁通过；最终DEX包含poster-row懒行键，14个动效实际/合并owner均已核验。未提交/推送/归档。

## 第五轮实施结果（2026-10-08）

- Cards增加可选加载结果通知，MediaCard按item/URL隔离；PosterMotion等待真实图片与15%可见，同时整卡0.94→1缩放和20dp上浮，复用lpSpring(.001f)轻过冲。占位/标题不隐藏，图片原交叉淡入只执行一次；错误/无图/零倍率正常几何，原Lazy入场记忆保留。
- 每entry首批150ms就绪窗口内最多9张0/25/50ms短错峰；MainShell复用既有嵌套滚动连接观察两轴consumed含fling，快滚取消/跳过，150ms静止恢复，不消费滚动，不改变底栏逻辑。
- 真实冷图等待超过旧动画周期的测试原实现红，修复后绿。92项回归通过（PosterMotion16、Motion4、Browse26、DetailOptions12、PluginNav5、HomeRefresh17、Library6、PlayerOsd6），包括冷/热图、失败/无图、URL切换、zero-motion、Lazy返回、轻过冲、首批上限/窗口、快滚取消、真实Lazy fling及部分裁剪10%→25%。裁剪测试对比连续帧亮区增长，避免要求抗锯齿边界某帧必须达到某个整数像素。
- default只读独立审查未确认阻断缺陷；按建议追加裁剪与真实fling证据，并由主线程复核最后滚动连接改动。更新Android规范、UI正本和时机复盘；仅本批三处生产文件及测试/文档，服务端/内核/媒体缓存保持此前实现。
- Android参数256处、diff空白及含未跟踪文件的隐私扫描通过。未验证手机真机帧率/快速手势主观观感、TV设备或缓存秒开耗时；不以Native像素/结构证据宣称真机丝滑。未提交/推送/归档。

- 第五轮最终手机APK：80,371,690 bytes（76.648MiB），SHA256 `6e3e7b65387e43df07df2c9f9494494e641fbfeca82bd4bc4edf5c1d693db968`；v1/v2/v3验签通过，唯一arm64-v8a、13个ELF64/AArch64播放库、≤80MiB及最终/assemble包一致性通过。最终DEX核验首批错峰、快滚观察和弹簧入场协程owner，证据见research/poster-spring-apk-verification.json。没有提供旧TV包作为本轮产物。

## 第六轮实施结果（2026-10-08）

- 对照用户录屏后只改原生首页整排揭示，Cards/PosterMotion/HomePage三处生产文件：整排右20dp→0、.97→1/.85→1，根据纵向可见位置连续映射，回滑可逆、主要阅读区正常；标题不横移，每帧进度只在graphicsLayer读取，不改布局尺寸或增加逐帧重组。
- 续播、各库最新、合集、标准插件items接线；骨架和真实轨道共用绘制modifier，首页关闭单卡入场，保留图片淡入/按压/点击/长按/共享；默认参数保留其它页面，custom接管与媒体库入口条不套效果。快滚强度平滑减至25%，恢复也平滑，倍率0正常几何。
- 真实LpRow原实现回归红（底部海报完成原动画后没有整排横移），接线后绿：两卡同步、标题水平稳定、首次测量帧、回滑可逆、滚动不重组栏目、冷图不叠加单卡位移、快滚强度平滑/0倍率，横滑/点击/长按菜单保留。
- 102项相关回归0失败/错误：PosterMotion19、HomeRefresh17、ResumeCard7、Browse26、DetailOptions12、Library6、Motion4、PluginNav5、PlayerOsd6。独立default只读复核未确认阻断缺陷；首次layoutInfo候选由首个测量帧检查约束，主线程逐处核查坐标与所有首页key/默认调用传播。
- Android参数256处、diff空白及全工作树含未跟踪文件隐私检查通过。UI正本/Android规范按首页整排与其它页单卡分开记录。未验证真机帧率/主观强度、TV；参数是试验选择，不宣称完全复现参照源码。不提交/推送/归档。

- 第六轮最终手机包：80,371,688 bytes（76.648MiB），SHA256 `49b9e18cafda17966de4c1b8fbc5836c98dc66b6900012750791c9263ad9f54d`；v1/v2/v3强制min-sdk21验证通过，arm64唯一ABI、13个ELF64/AArch64 SO、≤80MiB与最终/assemble包一致性通过。最终DEX含homeRowReveal及homeRowStrength，证据research/home-row-apk-verification.json；旧TV包不作为本轮产物。

## 第八轮实施计划

1. 替换第六轮位置映射断言为真实LpRow时间中间帧/一次入场验收，先在旧代码下红。
2. 只修改homeRowReveal及HomePage账号身份接线，复用现有lpSpring和根滚动观察器。
3. 核验快滑待归位/掠过跳过、骨架到真卡、冷图、Lazy/导航恢复、换账号、零倍率、点击/长按/横滑与共享回归。
4. 独立只读复核后主线程处理可确认缺陷，运行相关手机回归、参数/隐私/diff门禁，出手机arm64包并验签/ABI/体积/DEX及最终包一致性。
5. 更新正本/规范/复盘与本次记录，清理临时日志；不提交、推送或归档。真机帧率和与参照的主观一致性未测则明确报告。

## 第八轮实施结果（2026-10-08）

- 生产变更仅PosterMotion的homeRowReveal与HomePage账号接线：20%轨道可见后时间驱动lpSpring(.001f)，24dp/.97/.85整排归位；不再按滚动位置映射或在快滑时压缩至25%。高度仅尺寸变更记录，snapshotFlow只输出阈值/fast，动画帧只改变绘制层。
- fast中新排先登记已显示并等待当前组合停滑，仍达阈值则完成；掠过/销毁/未销毁但退回阈值以下均直接结束且不补播。已开始动画被快滑/离屏打断直接到位。账号/栏目key隔离saveable已播放标记，导航和Lazy返回不重播；骨架与真卡同一modifier，冷图仅执行原图片淡入，零倍率立即正常。
- 旧生产实现两红：停滑连续帧水平位置不变；快滑新排没有足够待归位位移。修复后99条相关回归0失败/错误：PosterMotion22、HomeRefresh17、ResumeCard7、Browse26、DetailOptions12、Library6、Motion4、PluginNav5。包括约450ms主要位移收住、整排同步/标题静止/帧间无栏目重组、冷图/骨架、真实fling、掠过与未销毁离阈值、Lazy/导航恢复、账号及零倍率、点击/长按/横滑与既有共享中间帧。
- 独立只读复核后主线程点验出处；waitingForSettle候选实际在collectLatest外，不随回调重新初始化，追加未销毁离阈值回归验证。旧规范冲突已同步Android规范与UI正本，复盘见research/home-timed-motion-review.md。
- 参数门禁256处、diff空白及含未跟踪文件隐私扫描通过。ADB可用设备0，已关闭本次启动的服务；没有同设备帧耗时或用户主观验收，不宣称真机流畅或参照精确复现。未提交/推送/归档。
- 手机最终包80,371,687 bytes（76.648MiB），SHA256 `40e0a1cf2216021b1adda7d21f72cdb9c0161ed4d17462ac98d257d025c5c08c`；v1/v2/v3强制min-sdk21验签通过，唯一arm64、13个ELF64/AArch64 SO、≤80MiB、最终/assemble一致。最终DEX核验新waitingForSettle与homeRowReveal时间协程并反汇编collector，旧homeRowStrength不再存在；证据见research/home-timed-motion-apk-verification.json。本轮未出TV包。

## 第十轮实施结果（2026-10-08）

- 首页撤掉homeRowReveal整排横移和fast等待，LpRow(homeAccount)及标准插件SourceCard接homePosterEntrance，15%可见后单卡0.90→1、向上22dp、0.86→1、0/25/50ms短错峰。标题与轨道不横移，动效不依赖停滑或图像成功；图片独立淡入，骨架稳定，Lazy/导航返回不重播、账号隔离、零倍率直显。其它页保留原入场规则。
- PosterLink仅内存保存来源Item，SeriesHead/EpisodeHead在真实detail为空时用来源文字托底，不写入业务Block或插件数据；无图标题、分集剧名与集号均支持，series_id未取得时剧名不可点击。来源原图直接复用，完成背景仍模糊；背景含内存命中独立350ms渐显，列表热图立即显示。连续alpha读取移到graphicsLayer。
- 原代码四项回归红，修复后通过；追加零倍率背景首帧检查发现零时长动画仍要等一帧，改就绪图在零倍率直接alpha1并snap结束。103项关联回归0失败/错误/跳过：PosterMotion26、HomeRefresh17、Resume7、Browse26、DetailOptions12、Library6、Motion4、PluginNav5。覆盖单卡中间几何/短错峰、横纵滑/真实fling、冷图、Lazy和导航恢复/账号切换、点击长按、实际详情挂起/无图/分集/真实字段替换、共享重复ID、缓存背景和零倍率像素。
- 两个default只读审查分别覆盖首页与详情，第三个核验最终零倍率条件；主线程按差分核查。未采纳缺少实际触发证据的“预览被骨架覆盖”推测，不扩大修复既有插件空ID协议问题。过程与防复发规则见research/home-fluid-review.md；Android规范与UI_MOBILE正本同步单卡和详情分阶段呈现。
- 参数门禁、diff空白及全工作树含未跟踪文件隐私门禁通过。只记录、不提交/推送/归档；没有真机帧耗时/最终主观验收，不以渲染与出包结果声称流畅或完全复现参照。此批不改播放内核、媒体缓存、服务端和TV。

- 第十轮最终手机包：80,371,683 bytes（76.648MiB），SHA256 `e4303f3adb8ecbad338302f201b5f8969ceda6cde50c51d05140e898e987d9fd`；强制min-sdk21的v1/v2/v3验签、唯一arm64 ABI、13个ELF64/AArch64 SO、80MiB门禁及最终/assemble一致性通过。最终DEX反汇编确认单卡snapshotFlow/短错峰/animateTo链路，旧homeRowReveal及waitingForSettle不再存在。证据research/home-fluid-apk-verification.json。本轮未出TV包。


## 第十一轮实施结果（2026-10-08）

- DetailCache将展示元数据白名单按server/userId/itemId散列隔离；Application唯一实例，经AppState接入，私有cacheDir落盘。内存先显、磁盘后台读与网络并行，网络优先；64条/每条128KiB、LRU、原子写/预算预留、损坏回源。设置占用/清理联动，清理代数阻止旧请求写回。
- DetailPage按entry/账号重建；live与cached分离，普通失败保留资料可重试、E_AUTH/E_NOTFOUND删除。收藏/已看只在实时状态到达后启用，插件只消费live；版本与续播仍实时。季/集分页保持，网络回写检查取消。
- 每条首页LazyRow显式持有布局状态；homePosterEntrance在graphicsLayer用单卡边缘比例合成缩放与下沉/回升，已加载卡片同页往返持续变化。首次单卡入场继续保留，不横移整排、不重新请求图片，零倍率关闭两种形变；标准插件items接同一规则。
- 旧实现两项缓存UI测试红、一项持续横滑红；复核实际不存在错误码后补一红再修正。119项关联回归全部通过，含API24存储与API36 Native UI、跨实例/隔离/白名单/容量/损坏/清理、迟到请求、失败保留/重试、真实设置清理与已解码图片重复横滑/离屏返回。两名只读探子复核，主线程点验并修正清理串行与唯一实例生命周期；具体证据见research/detail-cache-review.md。
- 正本缓存口径从整份详情不落盘更新为展示白名单可落盘、动态业务状态不落盘。Android参数256处、diff及含未跟踪文件的隐私检查通过。未做真机帧耗时、服务端联调或参考观感验收；首次无缓存及独立媒体/分集请求仍可能等待。只记录不提交/推送/归档。

- 第十一轮最终手机包：80,371,690 bytes（76.648MiB），SHA256 `f865c60cf72ae7af89956f14c184a5b506e9d1a201a359638434d8401a828e84`；v1/v2/v3强制min-sdk21验签、唯一arm64、13个ELF64/AArch64 SO、80MiB门禁与最终/assemble一致性通过。最终DEX已核验DetailCache及带LazyListState的homePosterEntrance，绘制lambda反汇编可见布局读取和缩放/位移接线；见research/detail-cache-apk-verification.json。本轮未出TV包。

## 第十二轮实施计划

1. 旧代码复现热图直接出现、媒体请求尚未返回先画默认版本。
2. NetImage统一逐图渐显并保持零倍率/URL隔离；图标库复用此入口；主导航透明度采用均匀曲线。
3. DetailPage用明确媒体状态驱动选项和媒体信息转场，独立重试；保持元数据、版本、偏好并行请求和播放选择规则。
4. 新增独立图片放行、媒体等待/失败/空结果/中间几何与实际PhoneRoot导航验证；关联回归、只读独立复核及主线程验证。
5. 正本/规范同步，签名/ABI/最终DEX核验手机APK；清临时文件，只记录不提交/推送/归档。

## 第十二轮实施结果（2026-10-08）

- NetImage普通缓存图和冷图统一T5线性渐显，背景保持T7；逐张完成逐张展示，静态底色/来源预览托底，同URL重组不重播，零倍率立即显示。实际共享来源卡片以entry/token匹配并跳过重新淡入，保持返回连续；图标库接同一入口，Fit/点击保持。
- 主NavHost调整进入/退出/返回的透明度曲线，保留既有位移、Tab缩放及共享海报。详情itemMedia分Loading/Ok/Fail，等待不生成默认版本或空轨道，成功真实选项整体淡入/高度过渡，媒体信息按版本切换；失败可只重试媒体，不重复元数据请求。请求并行及取消后检查保持。
- 有效热图红、忠实媒体故障注入红后修复；初次fixture标题重复造成的红不计为根因证据。首批59项通过，扩展139项零失败/零跳过，含真实PhoneRoot中间帧、快慢图独立放行、热图混色、共享返回几何和详情媒体高度中间帧。两个独立只读审查未发现确定问题，主线程点验并运行最终测试；见research/fade-review.md和fade-regression.json。
- Android参数256处、diff及含未跟踪文件的隐私检查通过；同步Android规范和UI正本，删除“普通内存命中直显”的旧口径。未验证真机帧耗时/主观观感、真实服务端或TV；只记录，不提交、推送或归档。

- 第十二轮最终手机包：80,371,687 bytes（76.648MiB），SHA256 `f9f1704d155691efe4bd409b83994d774b4d205c87129094f5cd6744b380cf71`；v1/v2/v3强制min-sdk21验签、唯一arm64、13个ELF64/AArch64 SO、80MiB预算与最终/assemble一致性通过。最终DEX确认NetImage的reveal参数和250/350ms线性fade分支无MEMORY_CACHE直接显示分支，详情媒体状态/动画标记已入包；见research/fade-apk-verification.json。未出TV包。

## 第十三轮实施结果（2026-10-08）

- NetImage普通图以15%可见触发400ms渐显，完全离屏复位，边缘区保持已触发状态；预加载不被可见性阻塞，缓存与冷图相同，背景独立渐显、共享来源与零倍率保持直接显示。共享来源仍观察几何，避免关联清理后误判离屏。等待图改静态底色，不遮住真实标题/角标。
- PhoneRoot透明度改为T8/T5/T6，保留导航结构与交互。LibraryPage首批30、后续120，按实际数量续取；首屏改简洁加载提示，回写前检查取消；同筛选页面缓存、换筛选清数据保持。
- 旧实现3条有效红：屏外就绪滚入全亮、同图回滑全亮、冷态仍为骨架网格。首次修复测试暴露测试手动时钟未同步真实draw，补实际隐藏帧/可见帧绘制后中间混色与边缘滞回验证通过；首批30/offset30/limit120续页测试通过。143项相关回归全部通过，独立只读复核后主线程点验；分页审查提出“30条全可见但不触发”与last>=items.size-6条件矛盾（29>=24），不成立，不为此添加分支。
- 真机观感/帧率及真实服务端速度仍未验；缩小首批是减少接口工作量，不承诺网络秒回。未修改底部导航或新增参照应用功能。仅记录，不提交/推送/归档。

- 第十三轮最终手机包：80,371,688 bytes（76.648MiB），SHA256 `972fa26a843b4870cdd16e3e37a81a10d0b70c3a044456057945438b96286f61`；v1/v2/v3强制min-sdk21验签、唯一arm64、13个ELF64/AArch64 SO、80MiB预算及最终/assemble一致性通过。最终DEX包含NetImage的视野回调、400ms线性分支与媒体库新加载标记；见research/viewport-apk-verification.json。未出TV包，清理本轮调试截图/日志/反汇编。


## 第十四轮：搜索返回与长按菜单

先运行搜索返回/长按故障回归，再实施；新增菜单定位边界与渲染回归，独立只读审查、相关Gradle回归、命令/隐私/diff门禁，出手机arm64包验签；不提交、不归档。

- 第十四轮：旧代码两条故障回归红，修复后147项相关回归全绿；独立审查后修正菜单缩放原点、留4dp绘制余量，最后检索同步删除TV草稿中静态屏蔽菜单。命令参数255处、diff及隐私门禁通过。真机/TV设备未验，跨服Emby详情账号导航与极窄窗口避让是明确边界。

- 最终第十四轮手机APK：80,371,679 bytes；SHA256 `0717a0637c38093bec54b580b6a0e8bad4089d1c5319e5b26b79f819afd3dcb9`。最终/assemble一致，80MiB预算、min-sdk21下v1/v2/v3签名、唯一arm64与13个ELF64/AArch64库通过。最终DEX确认SearchPageState/PosterMenuPositionProvider及三个旧屏蔽菜单标签不存在。首次包遗漏最后静态草稿删除，发现后重新assemble并归档验签，最终证据见research/search-menu-apk-verification.json。TV未出新包。临时Popup依赖jar已删除，本轮未留下新增调试截图/服务。


## 第十五轮：详情首帧占位与选集稳定

先挂起seriesSeasons与seasonEpisodes并测实际详情选集区域/播放目标几何，在旧代码红；实施后分阶段释放检查位置不变，冷图等待时集号/名称立即显示，换季/空/失败与字体缩放回归；独立审查、相关门禁、出手机包验签，记录不提交。

- 第十五轮完成：首帧占位几何旧代码红；修复后90项相关回归通过（详情缓存17、选项12、海报30、浏览31）。独立审查后补季局部重试；请求数回归验证快照放行避免重复请求；1.3字号续播换行与冷图文字首帧像素通过。255处参数、diff与隐私门禁通过。最终手机APK80,371,690 bytes，SHA256 `bf8689e9a8efabfe835479bac4ceaca2ad7b5e87e1a0af73919d6944620f07a9`，min-sdk21 v1/v2/v3、唯一arm64、13个ELF64/AArch64库、80MiB预算、assemble一致与最终DEX占位标记通过，见research/detail-layout-apk-verification.json。录屏拆帧图片已删除，原用户录屏保留；无服务。Session30仅记录，不提交/归档；TV未修改/出包，真机及插件动态高度未验。
