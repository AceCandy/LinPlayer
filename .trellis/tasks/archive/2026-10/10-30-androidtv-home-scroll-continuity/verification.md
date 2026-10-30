# TV浏览连续性与统一验证

## 最终覆盖范围

| 路径 | 统一方式 |
| --- | --- |
| 正式TV浏览根 | TvRoot统一提供TvBringIntoViewSpec；首页不再重复提供，不依赖Leanback特征 |
| 首页/收藏/搜索/聚合媒体轨 | 共享LpRow继续使用tvFocusRow与海报绘制缓存 |
| 媒体库、收藏分类、人物及数据源网格 | 继承根入口停靠，保持网格上下/跨列和返回行为，不加横轨边界 |
| 详情 | 标签、季、分集、演职员横轨统一边界；首屏仍以ProvideEdgeSpecWhen覆盖；三个上返播放入口共用一个Job |
| 搜索/分类/发现 | 搜索类型、快捷动作、插件结果、库筛选、数据源分类/筛选/线路、聚合快捷、榜单/星期条统一横轨边界 |
| 宿主插件语义组件 | PosterRow、PlugTabs、FilterPanel复用同一横轨规则；任意自定义容器不套规则 |
| 插件管理/设置 | PluginsPage标签、共享SegRow全部调用及ProxyDialog代理预设复用tvFocusRow |
| 榜单前三名 | Podium三卡横排复用tvFocusRow，确认仍走原解析和详情入口 |
| 栏目标题 | Down单Job合并；非Down、失焦及组退出取消，保留首卡滚回与等帧 |
| 输入框 | TV单行上下交给焦点搜索，左右保留光标；多行保留上下光标移动 |

手机不启用TV横轨规则，独立播放器从TvFrame进入，不继承TvRoot停靠。业务请求、播放决策、账号与图片权限未改；无新依赖或按键队列。

## 故障与改进证据

- 首页回顶：同起点655px、同160ms，旧单按剩16px、连按剩329px；单Job后轨迹一致。新方向无可移动目标时仍取消旧请求，保留迟到焦点回归。
- 横轨边界：旧原生TV首页Left可斜跳别行；20项屏外续播、首尾、跨行、重复KeyEvent、反向和返回回归通过。共享导航区分导航与内容意图，转场临时聚焦不能覆盖返回目标。
- 收藏停靠：无Leanback旧x=1649，目标x=588.8；统一后两种特征均一致，并能从详情回原卡原位置。
- 网格停靠：旧y=721，目标y=420.6；统一后纵向停靠、上下/左右及详情返回通过。
- 详情回顶：同96ms旧单按剩95、连按剩342；单Job后轨迹一致。中途Right后保留新演员焦点，不迟到抢回播放。
- 数据源：真实TvRoot进入搜索框后，旧实现连续四次Down仍停在单行输入框。修共享字段后可进入20项分类/筛选并走到两端，下到网格跨行、进详情及返回原卡，再上回筛选。
- 输入框组件测试直接检查TextSelectionRange：单行左右光标从1→0→1，多行上下从5→1→5；上下转移控件另有焦点断言。测试按钮统一宽度，避免方向搜索按几何跳过宽输入框的夹具错误。

## 绘制证据（前轮保留）

- 静态海报改前改后SHA256相同：`9d2be95b7861468a6706e908023b1989b1bb89b21ad4030ed3679b682237b070`。
- 相同测试时钟下，新旧tvPosterFocus的起终态及12个动态采样帧，共14帧完整ARGB哈希一致；布局边界不变，中途可反向。
- 旧动态用例固定绘制对象创建11次，新动态加静态两用例合计2次。计数与帧哈希临时输出已删除，仅证明对象复用，不代表真实设备帧率提升。

## 基线与断言校准

- 原始acc8822d的93项集合有9项失败，前轮扩至110项后102通过/8既存失败，rootBackKeepsHomeAndDetailReturnDoesNotArmExit转绿。
- 本轮扩展覆盖TvPrivacyTest时发现privateDiscoverOpensLiuyingAndRankingAndServerClickExits失败。临时将所有已修改生产Kotlin文件还原为git HEAD原始内容，单独重跑仍在同一nav.server焦点断言失败；之后逐字恢复本轮全部生产文件。故它是新增覆盖发现的既存失败，不计本轮新增回归。
- seriesRemoteUpRestoresWholeHero在统一原生TV停靠后，离屏首屏会被LazyColumn正常回收。中途断言从“播放节点存在且未聚焦”改为“没有播放焦点”，最终播放重新获焦、头图回原坐标的断言完整保留；独立复核认可其保留原契约。
- 前轮保留8项失败作基线；本轮经用户授权，按实际根因修正生产焦点、过时断言及遥控步骤，结果见下。

## 最新回归

用户授权收敛既存失败后，相关整组237项全部通过，0失败、0跳过，Gradle退出码0。新增1项原生TV特征下禁用工具首项的回归；原9条链路均执行到末尾。独立复核后进一步补强剧/季的第二加载阶段，随后两项定向回归也通过；最新测试报告目录对应这次定向运行，下面表格记录此前整组运行结果。

| 集合 | 总数 | 通过 | 失败 |
| --- | ---: | ---: | ---: |
| CastPresentationTest | 3 | 3 | 0 |
| PhoneBrowseUiTest | 42 | 42 | 0 |
| PhoneDiscoverUiTest | 17 | 17 | 0 |
| PhoneEngineSettingsTest | 1 | 1 | 0 |
| PhoneHomeRefreshTest | 33 | 33 | 0 |
| PhoneLibraryUiTest | 18 | 18 | 0 |
| PhoneManagementUiTest | 8 | 8 | 0 |
| PhonePluginUiTest | 8 | 8 | 0 |
| PhoneSourceUiTest | 6 | 6 | 0 |
| PhoneTypographyTest | 3 | 3 | 0 |
| SharedTvBrowseTest | 84 | 84 | 0 |
| TvFocusStyleTest | 10 | 10 | 0 |
| TvPrivacyTest | 4 | 4 | 0 |
| 合计 | 237 | 237 | 0 |

## 原9项失败的处理

| 数量 | 原用例/路径 | 根因与处理 |
| --- | --- | --- |
| 2 | focusedNavigationTabKeepsShortLineWithoutPill 深/浅主题 | 旧透明底断言与已采用胶囊样式冲突，UI_TV.md也未同步。按用户接受的当前胶囊约定统一文档，改为ShowsPillAndKeepsCurrentLine，验证真实按键下胶囊显隐、圆角外区域和失焦后当前页短线保留；短线用RGB色差，避免不同色相亮度近似造成误报 |
| 3 | detailLoadingAndCollectionReturnWithoutOnScreenBack；seriesPlayWaitsForEpisodesAndRemoteOpensNextEpisode；seasonPlayWaitsForEpisodesAndRemoteKeepsRequestedSeason | 加载中的播放行按现行约定存在且禁用，替换旧不存在断言。保留加载返回/迟到响应/合集无播放、就绪后首焦点、分集parent_id、真实播放分集与版本身份检查。剧/季分别在详情未到及详情已到但分集未到时发送Up/Down/Center/Menu，断言无首屏焦点、无起播Activity |
| 2 | detailActionsAndDownwardScrollKeepRemoteFocus；cinemaSeriesRemoteOptionsReachPlayerWithTargetIdentity | 生产缺陷：Up定向进入工具组时落到最右换源。工具组onEnter仅在Up时改用默认Enter请求，以行首为起点选择可用项；上下离组、收藏、版本/音轨/字幕及播放器目标身份原断言全部通过 |
| 1 | singleSeasonHeadingOnlyShowsItsNameWithoutFocus | 实测第一次Down已到首集，第二次Down到季卡。校准为一次Down到首集，再Up回播放；单季标题无点击动作断言保留 |
| 1 | TvPrivacyTest.privateDiscoverOpensLiuyingAndRankingAndServerClickExits | 返回后焦点在流影内容卡；对首页直接RequestFocus会触发导航组进入当前栏目的规则。改为真实Back到聚合导航，再逐键左移收藏→首页→服务器并确认退出；私密请求scope及退出状态断言保留 |

新增detailUpEntersFirstAvailableToolWhilePlayedIsSaving：开启Leanback，挂起已看保存令首项禁用，从播放上返进入收藏；解除挂起后首项恢复可聚焦。禁用项释放焦点后使用RequestFocus建立播放起点，这项专门验证工具组可用项选择，不声称完整忙碌态焦点恢复流程已验。替身成功后仍返回原资料，因此末尾仍是“标已看”。

执行命令：

```sh
JAVA_HOME="$PWD/.toolchain/jdk-21.0.2" ANDROID_HOME="$PWD/.toolchain/android-sdk" GRADLE_USER_HOME="$PWD/.toolchain/gradle" PATH="$PWD/.toolchain/jdk-21.0.2/bin:$PATH" apps/android/gradlew -p apps/android :app:testDebugUnitTest --tests '*CastPresentationTest' --tests '*PhoneBrowseUiTest' --tests '*PhoneHomeRefreshTest' --tests '*PhoneLibraryUiTest' --tests '*PhoneSourceUiTest' --tests '*PhoneTypographyTest' --tests '*SharedTvBrowseTest' --tests '*TvFocusStyleTest' --tests '*TvPrivacyTest' --tests '*PhonePluginUiTest' --tests '*PhoneDiscoverUiTest' --tests '*PhoneManagementUiTest' --tests '*PhoneEngineSettingsTest' --console=plain --no-daemon
```

## 上一轮审查补漏证据

- 四处接线补齐：榜单前三名、插件管理标签、共享设置分段、代理预设。新测试从真实TvRoot导航栈进入，检查左右首尾、上下离组、确认的实际选中/请求结果；代理保存验证最终proxy参数。
- 旧标题实现下，冻结时钟、即时Down再Up/Left后均出现迟到首卡聚焦。先加入非Down/失焦取消后，首行父页面先消费Up的场景仍在下一帧聚焦首卡；加标题焦点组onExit取消后，中间帧无抢焦且最终返回导航。红例已实际运行，不用测试注入参数推断竞态。
- 原标题回归扩为12张卡，先断言末卡聚焦、首卡完全离屏，再从标题同帧连续6次Down，最终仍进入真正首卡；普通单次Down保持通过。
- 代理Dialog具有独立窗口。JVM测试须先waitForIdle，再对实际窗口requestFocusFromTouch并确认已离开触摸模式，按键发给isDialog节点；直接向页面根注入会匹配两个窗口，沿用主窗口模式则无法进入普通可点击预设。修正的是测试输入环境，未新增生产弹窗逻辑。Up到输入框、Down到预设/按钮、选择及保存全路径通过。
- 两份本轮独立只读复核未发现可确认的生产缺陷；按测试复核补强了首卡离屏断言。关于挂起窗口的疑虑由改前红例及首行中间帧失败证据核对，未为测试新增生产钩子。另有独立窗口路径只读核查。

## 独立复核与静态门禁

- 本轮两份只读复核检查工具组进入逻辑及9条测试链路；未发现可确认的新增生产问题。对应Compose UI 1.12.0源码确认requestFocus默认Enter、onEnter重入保护及回调重定向处理。按复核建议补上第二加载阶段的遥控禁用验证并定向通过。
- 本轮参数检查194处有效、git diff --check及修改范围隐私检查通过；临时焦点诊断输出全部删除，未启动服务、未留下源码下载文件。

- 追加两份独立只读审查，分别检查根入口/详情任务/焦点组与输入/插件/测试；未发现可确认的新增生产缺陷。主线程核查实际diff。
- 已按审查补强光标实际位移、数据源网格跨行及详情往返。20项筛选的21次Right已经覆盖末端额外按键，无需重复添加同一检查。
- Android参数检查：194处调用有效；git diff --check通过；本轮文件隐私检查通过。
- 全库隐私检查仍命中未修改的.trellis/workspace/AceCandy/journal-2.md，本轮文件命中0；未输出可疑值，也未修改白名单或基线。
- 临时诊断日志已清理；原始生产代码对照后已恢复全部修改，未留下备份文件；未启动调试服务。

## 未验与风险

本地提交与归档按用户后续授权执行，代码提交见task.json。未出安装包、安装或推送。真机帧耗时、输入延迟、实际遥控器及软键盘仍未验；第三方插件实际页面未逐个跑设备验证。JVM按键/渲染不能证明Apple TV观感。本次相关集合已无失败；全库旧日志告警仍待处理。
