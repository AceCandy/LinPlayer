# 状态归属

| 状态 | 拥有者 / 例子 |
|---|---|
| 账号、播放、下载、历史等业务状态 | Go 核心与命令结果，不在每端重复实现规则 |
| 安卓会话与页面请求入口 | AppState / LocalApp，页面用 app.call |
| 安卓设备呈现偏好 | UiPrefs，例如主题、色系和本机播放内核偏好 |
| 手机临时交互 | Compose 页面状态与导航路由 |
| TV 返回栈 / 焦点记忆 | TvNav 与既有 tv/kit 状态机制 |
| 桌面页面会话与返回栈 | Nav.Session 与 Nav |

UiPrefs 是纯本机呈现偏好的有边界例外，不把核心业务配置移进 SharedPreferences。
主题选择不通过核心 prefs 命令虚构字段；配置未支持的字段不能“返回成功”后假装落库。
缓存绑定路由 / 会话，换账号时不能继续展示其它账号的响应。

依据：[AppState](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/data/AppState.kt)、[UiPrefs](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/data/UiPrefs.kt)、[桌面导航](../../../apps/windows/LinPlayer.Desktop/Views/Nav.cs)。

## 季内连播

- 手机和桌面正常完播自动播放当前季下一集；TV 保留下一集卡、5 秒倒计时和本机自动播放开关。季末退出，不跨季。
- 分集由播放页加载：优先 `season_id`，缺失时仅在已知 `season_no` 下查 `series_id` 并过滤当前季；分页仍用 `emby.seasonEpisodes`。Android 共用 `playbackSeason(app, detail, itemId)`，当前集不在返回表中视为加载错误。
- 加载中、失败、成功但没有下一集是三种状态；完播等待在途请求，失败保留页面与重试入口，不能以空初始列表退出。旧目标或离页请求不得写回。
- MPV 完播需已推进、已知时长且位置在片尾 5 秒内；桌面保留连续两拍 EOF 判据。中途 EOF 不触发换集，带 `item_id` 的旧 Emby 状态不得污染新集。
- 自动与手动换集复用停止屏障、当前服务器和已有续播/轨道记忆；播放页替换目标，不累积返回栈。每集重建连播目标，禁止仅由详情入口传单个下一集。
- 手机“接下来”由当前季成功队列、当前目标实际position/duration推导；提示窗口为总时长10%，最少10秒、最多30秒，剩余时间>0且≤窗口时显示，卡片进度复用同一窗口。暂停和OSD隐藏保留，退回撤掉，未知/非法时长、未就绪/失败或季末没有卡片。提示不触发自动换集，点击、上下集和选集共用switchTarget及停止屏障；切集时禁用重复提交、清旧比例提示。回归包含35秒短片在25秒出现、200秒在180秒出现及长片最多提前30秒。
- 回归：`PhoneEnginePlaybackTest` 验证连续三集、Media3 完播、慢请求及中途断流；`TvFocusTest` 验证倒计时、开关、季末、加载等待和失败重试；`tools/seekcheck/SeasonPlaybackCheck.cs` 通过真实页面/核心 HTTP 解析验证桌面季内队列与服务器保留。上述回归不替代真实设备播放与上报联调。

## 手机播放返回详情

- 正常退出由 `PlayerPage` 先登记一次 `stopIn` 收尾，再经 `PlayerActivity` 的 `player.playedItem` 返回当前 Emby 条目 ID；本地来源不回传。不能用最初的播放路由猜连播末集，也不能只依赖 Activity 销毁才登记停止。
- `PlayerLaunchPage` 将末集和递增的 `player.returnRevision` 写入原浏览 entry 的 SavedStateHandle；同集重复退出也刷新。详情立即恢复，详情/分集查询异步等待 `PlayerController.awaitPendingStop()`；分集刷新从首批重取，不能把旧 confirmedEpisodes 当作续页起点。
- 单集详情使用末集作为当前条目；整剧/季保留详情入口，以末集优先确定续播目标和分集滚动位置，不依赖前几集已看标记。账号、条目变化仍取消旧请求。`PlaybackReturnTest` 覆盖慢停止、3→5返回、同集重复刷新和整剧续播目标；`PhoneEnginePlaybackTest` / `PlayerActivityTest` 覆盖实际切集回传与一次停止，不能代替真机上报联调。

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
