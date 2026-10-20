# 状态归属

| 状态 | 拥有者 / 例子 |
|---|---|
| 账号、播放、下载、历史等业务状态 | Go 核心与命令结果，不在每端重复实现规则 |
| 安卓会话与页面请求入口 | AppState / LocalApp，页面用 app.call |
| 安卓设备呈现偏好 | UiPrefs，例如主题、色系和本机播放内核偏好 |
| 手机临时交互 | Compose 页面状态与导航路由 |
| TV 浏览返回栈 / 焦点记忆 | 共用 Navigation Compose；entry SavedStateHandle保存焦点标记，Popup/Dialog不覆盖页面标记 |
| TV 播放内部状态 | TvNav 与既有 tv/kit 状态机制 |
| 桌面页面会话与返回栈 | Nav.Session 与 Nav |

UiPrefs 是纯本机呈现偏好的有边界例外，不把核心业务配置移进 SharedPreferences。
主题选择不通过核心 prefs 命令虚构字段；配置未支持的字段不能“返回成功”后假装落库。
缓存绑定路由 / 会话，换账号时不能继续展示其它账号的响应。

依据：[AppState](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/data/AppState.kt)、[UiPrefs](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/data/UiPrefs.kt)、[桌面导航](../../../apps/windows/LinPlayer.Desktop/Views/Nav.cs)。

## 季内连播

- 手机和桌面正常完播自动播放当前季下一集；TV 保留下一集卡、5 秒倒计时和本机自动播放开关。季末退出，不跨季。
- 分集由三端播放页调用 `emby.playbackSeason(item_id)`：Go读取实时详情，优先 `season_id`，缺失时仅在已知 `season_no` 下查 `series_id`；已知季号时排除明确属于其它季的条目。核心聚合分页、按集号排序并验证当前集存在；重复ID、无效ID、已知总数未达却空页均报错，未知总数持续到空页。详情渐进分页继续用 `emby.seasonEpisodes`，不等待全季。
- 加载中、失败、成功但没有下一集是三种状态；完播等待在途请求，失败保留页面与重试入口，不能以空初始列表退出。旧目标或离页请求不得写回。
- MPV 完播需已推进、已知时长且位置在片尾 5 秒内；桌面保留连续两拍 EOF 判据。中途 EOF 不触发换集，带 `item_id` 的旧 Emby 状态不得污染新集。
- 自动与手动换集复用停止屏障、当前服务器和已有续播/轨道记忆；播放页替换目标，不累积返回栈。每集重建连播目标，禁止仅由详情入口传单个下一集。
- 手机“接下来”由当前季成功队列、当前目标实际position/duration推导；提示窗口为总时长10%，最少10秒、最多30秒，剩余时间>0且≤窗口时显示，卡片进度复用同一窗口。暂停和OSD隐藏保留，退回撤掉，未知/非法时长、未就绪/失败或季末没有卡片。提示不触发自动换集，点击、上下集和选集共用switchTarget及停止屏障；切集时禁用重复提交、清旧比例提示。回归包含35秒短片在25秒出现、200秒在180秒出现及长片最多提前30秒。
- 回归：`PhoneEnginePlaybackTest` 验证连续三集、Media3 完播、慢请求及中途断流；`TvFocusTest` 验证倒计时、开关、季末、加载等待和失败重试；`tools/seekcheck/SeasonPlaybackCheck.cs` 通过真实页面/核心 HTTP 解析验证桌面季内队列与服务器保留。上述回归不替代真实设备播放与上报联调。

## 手机播放返回详情

- 手机手动标已看/未看成功后，详情必须重取 `played` 和 `resume_secs`，剧/季同步从首批重取分集，不能只改顶部勾标。Go 对携带当前 `request_scope` 的成功写入发 `data.userState`，Android 接收后转为既有失效通知：`emby.setPlayed → all`、`emby.setFavorite → library`、`emby.hideResume → resume`；失败及旧会话结果不通知。首页、收藏、媒体库和搜索按通知重读，详情标记在途禁重复提交。`PhoneDetailOptionsTest`、`PhoneHomeRefreshTest`、`PhoneBrowseUiTest`、`UserStateInvalidationTest` 覆盖续播文案、列表成员移除、失败保留及空间隔离；真实服务器仍需联调。

- 正常退出由 `PlayerPage` 先登记一次 `stopIn` 收尾，再经 `PlayerActivity` 的 `player.playedItem` 返回当前 Emby 条目 ID；本地来源不回传。不能用最初的播放路由猜连播末集，也不能只依赖 Activity 销毁才登记停止。
- `PlayerLaunchPage` 将末集和递增的 `player.returnRevision` 写入原浏览 entry 的 SavedStateHandle；同集重复退出也刷新。详情立即恢复，详情/分集查询异步等待 `PlayerController.awaitPendingStop()`；分集刷新从首批重取，不能把旧 confirmedEpisodes 当作续页起点。
- 单集详情使用末集作为当前条目；整剧/季保留详情入口，以末集优先确定续播目标和分集滚动位置，不依赖前几集已看标记。账号、条目变化仍取消旧请求。`PlaybackReturnTest` 覆盖慢停止、3→5返回、同集重复刷新和整剧续播目标；`PhoneEnginePlaybackTest` / `PlayerActivityTest` 覆盖实际切集回传与一次停止，不能代替真机上报联调。

## 桌面与 TV 用户状态刷新

- 触发：`emby.setFavorite`、`emby.setPlayed`、`emby.hideResume` 成功后，当前页面及返回的首页、收藏、媒体库、搜索、详情必须重验；共享失效事件存在不代表所有页面已消费。
- 入口：Android `AppState.userStateRevision: StateFlow<Long>` 保留失效代次，TV 页面以它作为请求 key；桌面 `CoreClient.UserStateRevision(server, user, token)` 与 `UserStateChanged` 经 `PageBase.WatchUserState(core, refresh)` 在挂载时补验。生成绑定和核心命令签名不变。
- 契约：Android 将核心 `data.userState.scope` 转为 `invalidate`，事件id须仍属于当前会话和空间；桌面代次按服务器、用户、凭据分组。TV 仅组合栈顶页，非重放广播不能承担返回刷新；桌面保留页面实例，不能靠整页重建丢弃筛选、分页与滚动。
- 错误矩阵：写入失败不推进代次；旧会话结果不刷新新会话页面；普通刷新失败保留旧内容并提示错误；榜单鉴权、权限、不支持错误不可用缓存伪装成功。多页列表先收齐原已加载范围，再一次提交，不能后页失败却留下半份新列表。
- 场景：详情取消收藏后返回原收藏页重读；分集标已看后整剧从首批重取季集并重算播放目标；同服切用户时仅清 `PageCache` 不会清现有 `remember`，`TvShell` 必须按服务器/用户重建页面组合。
- 回归：`UserStateInvalidationTest` 断言成功/失败/旧空间代次；`TvFocusTest` 断言当前首页、离页返回整剧目标、同服切用户、多页失败原子保留及从零重试；`tools/seekcheck/UserStateCheck.cs` 经真实页面和 HTTP/FFI 验证详情操作、返回原收藏实例、失败及会话隔离。
- 错误做法：只订阅 `invalidate`，或仅在请求成功处检查 generation。正确做法：保留可在返回时读取的代次，并在成功、异常、缓存回调及排队后的 UI 更新处拒绝旧代结果。

## 发现页分页与公共日历

- 桌面/TV保留按钮分页；请求页与已显示页分开，成功才提交显示页。普通失败保留已显示内容及页码，重试指向失败目标；切分类/账号清除上一范围。榜单请求使用 `retainOnFailure = false`，不能把旧缓存当作本次网络成功。
- 日历只在 `timezone == "Asia/Tokyo"` 时显示 `broadcast_time`；缺失时间保持未知，不从首播日期编造时刻。星期优先有效 `weekday`（1～7），缺失时 ISO 时间按 JST 换算，不能依设备时区分组。
- `TvFocusTest` 与 `tools/seekcheck/DiscoveryCheck.cs` 覆盖第2页失败保留第1页、失败目标重试、空页和JST跨日；自动回归不替代真实服务器及设备联调。

## 手机下一集准备

- 当前集就绪、未卡顿且距片尾不超过60秒时准备同季下一集；手动切集也先发起准备，与停止收尾并行。尊重核心预加载开关；停止本地输出后仅接管已就绪结果，未就绪或失败时按请求ID取消准备并直接走正常起播，不等待准备任务完成。迟到返回不得影响新目标。
- `player.prepareNext` 使用独立代理，不改当前目标或上报开始；准备结果按请求ID、账号凭据、条目、版本和选版规则一次消费，2分钟过期。离页或账号变化取消pending及已选未消费结果，迟到结果不得写回。
- 信息准备只解析播放信息和探测代理，不自动读取视频。`player.warmPrepared(request_id, enabled)`串行启停后台读取，暂停保留准备槽及代理缓存；缓冲余量按倍速换算，30秒启动、低于10秒或卡顿暂停，已缓冲至片尾（1秒容差）可提前放行。mpv在准备窗口内持续采样缓冲前沿，不依赖OSD显隐。Exo暂停移除预加载源，恢复时重建并复用核心字节缓存；已发出的固定缓存块允许收尾。
- Media3使用同一DefaultPreloadManager/ExoPlayer加载配置，提前加载前5秒样本，正式起播接管同一MediaSource；字幕在接管前隔离，重置当前字幕前停用旧回调。mpv复用核心地址与字节准备，临近片尾追加原生playlist并临时启用prefetch-playlist。
- 切集`handoff=true`只将网络停止报告后台化，本地输出和历史先收尾；旧Stopped先于新Playing，普通退出继续等待停止上报。快速连续切集最多保留一份后台停止任务，必要时等待上一份完成。

- 手机当前集已缓冲到尾部且剩余不超过10秒时，`player.queuePrepared` 将准备槽转为内核队列槽；排队不改变当前集身份。Media3实际media transition后调用`player.advanceQueued`，MPV按START_FILE的playlist entry ID接管，并发出`player.queueAdvanced`；接管不能重复stop/load或请求播放信息。Media3下一集存在非零续播时仍走常规切换。
- 接管使用停止屏障，退出位置必须在接管完成后读取；切核前取消排队。排队取消不得关闭已播放的代理，MPV状态采样需先同步实际playlist条目；进度报告携带item_id拒绝跨集迟到报告。内核队列不保证视频首帧无间隙，需真实设备验证。

- `player.stopPlayback` 可携带位置所属的 `item_id`；原生内核已跨集时，核心使用当前内核位置收尾，不把旧位置记给新集。Media3接管完成时同步旧控制器的条目身份，保证等待接管的退出任务使用同一集的身份和位置。
- MPV队列衔接继承内核实际倍速；`player.danmakuBegin`在新弹幕会话首次排版前同步持久化样式，不依赖MPV起播或打开设置面板。

## 跨端隐私空间

AppState在手机与TV启用请求分类（普通false、隐私true），未启用的宿主保持无筛选。空间代数由PhoneRoot/TvRoot重建导航，Go displaycache键包含空间；displayBlock及普通call均拒绝旧空间返回。私密搜索不写UiPrefs普通历史。独立PlayerActivity携带空间标记，失去进程解锁状态时拒绝旧Intent，运行期空间变化退出窗口；不能由恢复路由反向解锁。

隐私空间的媒体请求固定当前会话；禁止聚合、本地watchHistory、source/plugin/download命令、本机播放及覆盖server/server_id/user/token的请求。手机底栏保留聚合视界但仅展示流影，桌面与TV发现页同样只保留流影，不读取普通聚合快照；搜索与详情隐藏跨服入口；插件接管/导航不进入隐私空间，TV首页不沿用activeSource。服务端缺少完整历史接口时只展示续播，不能用本地记录代替。

桌面由BrowseSpace保存不可变身份与空间代次，CoreClient对最终响应、partial、缓存及UI交付复核；页面自己的排队回调仍须检查SpaceCurrent。Go displaycache统一持久化普通与私密展示资料，按身份和空间隔离；页面状态仍只驻内存。切空间清导航，切账号退出；入口使用未筛选的完整媒体库探测，旧探测不能打开新空间。图片缓存沿用现有核心行为，此规则不承诺私密图片不落盘。

私密主题只临时覆盖资源，退出恢复普通偏好；流光共用相位，后台/减少动效时停止，播放页隐藏。流影双网页按需创建，切换保留实例，离页销毁，返回先网页历史；Android共享WebView，Windows使用WebView2，Linux使用WebKitGTK 4.1临时上下文（2.40+，X11/XWayland）。GTK原生焦点须纳入前台判断，窗口隐藏/最小化仍停用；新窗口同时处理create和导航策略，限定HTTP(S)。运行库加载后不卸载探测句柄，避免原生线程访问已释放代码。Windows原生媒体与profile隔离仍需实测。

私密空间禁止的数据源功能还须在页面入口跳过请求，不能仅依赖CoreClient拒绝后显示错误；收藏页只加载当前服Emby收藏。PrivacyCheck验证允许内容加载成功且无数据源收藏板块或错误。

## 统一请求与选择契约

- 三端请求适配层附加可选 `request_scope: {id, private, filter, server, user_id, token}`。id是不透明会话/空间代次；Go bus在原handler前验证私密身份与命令策略、注入分类，并移除该元数据。它不落缓存、不成为进程全局开关；不带scope的旧调用仍兼容。宿主取消与迟到响应屏障继续保留。
- 成功且未取消的 `emby.setPlayed/setBlocked` 发 `data.userState {id, scope: "all"}`，`setFavorite` 为library，`hideResume` 为resume。失败、取消与显式其它身份不通知当前scope；事件不携带身份凭据。宿主只按id接收，不在命令返回时再重复通知。Android在对象创建时立即订阅；桌面返回页面通过PageBase.WatchUserState补验代次。
- `emby.heroCandidates(items)`纯计算候选ID，统一排除已看/续播及所属剧、优先整剧并按作品去重。首页合并当前已加载栏目及 `emby.cachedItems`，不扫描全库；图片缓存探测、顺序保留、轮播计时及焦点暂停归宿主。
- `player.selectTrackPreferences(tracks, prefs?)`复用mpv的选轨偏好决策，返回sub/audio；空串保持现状，sub=no关闭，其余为输入轨ID。Media3/Aether负责标准化可用轨表并实际切轨；异步结果须验证当前目标、轨表和手选优先，不能覆盖后来的手选。
- tvOS通用播放偏好通过player.getPlaybackPrefs/setPlaybackPrefs保存，音轨/字幕/版本正则通过player.setTrackRegexes统一校验。片头片尾使用player.chapterInfo，失败可独立重试；自动跳过仅前台播放时执行，手动跳过沿用seek屏障。真实焦点、滚动和媒体选轨仍需设备验收。
