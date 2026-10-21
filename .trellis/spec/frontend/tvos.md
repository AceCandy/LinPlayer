# tvOS 外壳（开发中）

适用 `apps/tvos/`、`bindings/swift/` 及 tvOS 专用脚本。页面设计正本为 [UI_TVOS](../../../docs/go-migration/UI_TVOS.md)，构建/验证入口为 [开发说明](../../../apps/tvos/README.md)。Android TV 正本与现有行为不改变。

- SwiftUI 管导航/焦点，UIKit `AetherPlayerView` 管视频表面。业务、账号、播放目标/续播、上报归 Go；宿主不能另写 Emby HTTP 业务客户端。
- 生成命令只改 `gen-bindings.py`；Swift DTO 按真实 handler 定义。唯一事件泵与发送队列分离，支持多 seq、取消、partial/result/event/eof；原生字符串复制后用 `lp_free` 释放，解析失败也不漏。
- 普通浏览显式 `is_adult=false`；分页 query 嵌套，游标按原页数量推进，不能按去重后的卡片数推进。空季成功才回退剧 ID；失败不能当空季。
- 页面任务用 `.task(id:)` 绑定路由；账号代次变化拒绝旧结果并清图片缓存。图片使用回环通道的鉴权请求头，token 不进 query。播放重试更换视图身份，旧会话停止后再创建新会话。
- `player.play(engine=exo)` 是现有宿主解析协议；不传未知 aether 值。普通播放让核心决定续播/选版，从头用 `from_start=true`。不从缓存取最终播放目标。
- Aether 固定 7.39.0 默认 traits，`autoplay=false` 加载后再按前台状态播放。实际 clock 上报；停止带 `item_id` 并检查 `reported`。挂起暂停并尽力上报，回前台不自动恢复声音。
- 音轨选择用真实 `TrackInfo.id`，不可切换路线不提供入口，异步确认实际索引；字幕只呈现已支持文本 cue，复杂格式不假成功。
- tvOS Go 构建使用 `ios && tvos` Keychain 后端，命令注册前严格恢复配置/设备标识/屏蔽名单。读写失败不得落回缓存或放宽隐私过滤；其它平台保留文件后端。
- 固定依赖由脚本校验，中央引擎日志关闭后才允许真实账号联调；凭据、媒体 URL、请求头和 Keychain 内容不进日志。HTTP ATS 例外及未验范围见开发说明，不宣称仅局域网放行。

## Quality Check

生成/命令集合对账、Go 核心门禁、真实 tvOS C 头文件检查、整源码 `swiftc -typecheck -warnings-as-errors` 与 Swift 实质回归。检查入口不生成 .app；未经直接出包指令不调用 pack。

类型检查不证明 Keychain 权限、框架嵌入、遥控焦点、实际媒体、音轨切换、进度服务端保存或 tvOS 18 兼容。正式应用已在tvOS27验证本轮更新安装、启动、新进程免登，以及正式设置清缓存后重启免登和首页恢复；清理页显示Zero KB及「已完成」，重启后真实首页资料与图片正常。设备标识连续性等Keychain其余项，以及遥控和播放验收仍开放，前置探针证据不可计为正式客户端通过。最终出包另验 `-load_hidden` 符号、九个动态框架闭包/签名和第三方发行材料。

- 跨主机同步正式包须比对源清单的全部文件内容、权限及链接。本机挂载目录曾在tar -p提取后为普通文件增加执行位；按源清单显式chmod并再次逐项核对，不能直接声称权限一致。
- 真机缓存验收使用正式设置中的system.clearCache。禁止直接用devicectl copy to的remove-existing-content模拟子目录删除：曾在指定cache目标后报7000，原核心数据根随后无法列举、重启才重建，删除边界未能保证；不能把destination当作已经验证的清理边界。此类开发工具行为须先在隔离域验证，不在正式应用数据域试错。

## 模拟器 Keychain 应用内检查

1. 范围：`KeychainAppCheck.m`加载`KeychainCheck.c`，直接使用生产`core/statefile/keychain.h`；不接Go初始化、正式账号或网络，不代替A1正式应用验收。
2. 入口：`bash scripts/check-tvos-keychain.sh <已启动的arm64 tvOS模拟器UUID>`；内部调用`lp_keychain_check(name, phase)`，phase为save/verify/missing/cleanup。
3. 身份契约：脚本为每轮生成独立bundle ID、Keychain访问组及条目名，通过`LP_CHECK_NAME`/`LP_CHECK_PHASE`传合成参数；缓存删除只针对该测试应用容器。结束清条目、卸载宿主并删除临时构建目录；调用者关闭自己启动的模拟器。
4. 校验与错误：首次读取须为`errSecItemNotFound`；写入/覆盖/读回须成功，字节完全一致；删除后须缺失。断言、进程提前退出、超时、清条目或卸载失败均返回非零，不输出条目内容。
5. 场景：正常写入后终止进程，清可重建缓存再启动仍读回；删除后第三个进程确认缺失。仅清缓存不改变应用身份或访问组；损坏、保存失败、退出不复活及正式免登仍由Go回归和正式应用验收负责。
6. 必须验证：故障注入在save后删除条目，verify须因`-25300`而不是预期`0`失败；恢复源码后全流程通过。只看到脚本非零不足以证明故障被正确检出。
7. 避免重复踩坑：当前Xcode模拟器使用链接器`-sectcreate __TEXT __entitlements <plist>`嵌入测试身份，再普通ad-hoc签名；裸进程缺权限会报`-34018`，照搬真机`codesign --entitlements`曾导致模拟器拒绝启动。工具链变化先核对`ENTITLEMENTS_DESTINATION`，不改生产存储绕过权限。

## 空会话恢复契约

1. 适用：首次启动和退出后的 `AppModel.restoreSession`，避免未登录被误报为启动恢复失败。
2. 签名：`emby.currentSession` 无参数；Swift 使用 `MediaSession.restore(_ value: JSONValue) throws -> MediaSession?`。
3. 返回：handler 无账号返回 nil；`bus.dispatch` 将 nil 成功结果转换为 `{}`。Swift 同时接受 `{}` 和 `null` 为无会话；非空会话仍须包含 server/token/user_id/user_name。
4. 错误：空值转登录页；非空但缺字段、数组等形状继续抛 `E_PROTOCOL`，不能静默降级。
5. 用例：完整会话恢复；空对象与 null 显示登录；缺字段对象/数组报错。
6. 回归：`CoreClientCheck.apiContracts` 覆盖上述输入，去掉空对象分支必须失败；正式首次登录页面和跨进程恢复仍需真机确认。
7. 避免：只看 handler 注释构造 `null` fixture。应核查 handler → dispatch → 信封 → DTO 的实际形状，不能全局把空对象改成 null。

## 卡片评分与状态契约

- 列表 DTO 来源 `core/emby/emby.go Item`：普通评分 `rating`，豆瓣评分 **`DoubanRating`**（区分大小写），未看数 `unplayed_item_count`，可信统计 `unplayed_count_known`。Swift 新字段可选，兼容缺失/null；路由占位无评分。
- 普通评分仅有限的 `(0,10]`，豆瓣允许 `[0,10]`；分别显示一位小数。Series/Season 未看统计可信时显示正数，可信零且 played 时才显示完成；Movie/Episode/Season 已看可显示勾。未知整剧统计不显示完成。
- 续播角标仅 Movie/Episode 且有效 runtime；未完成且 `0 < resume < runtime` 用剩余秒数，否则总时长。普通海报不显示时间角标。
- `CoreClientCheck` 覆盖真实大小写、缺省统计、评分边界与剩余时间；将 DoubanRating 错写为 snake_case 的故障注入必须被检出。
- 实拍证明 tvOS 顶栏 `.plain` 配合 `.focusEffectDisabled()` 仍可能出现系统白底；本任务改为自绘 ButtonStyle 后仍需电视确认，不能凭类型检查声称焦点修复。

## 核心展示缓存接入契约

1. 范围：tvOS 首页、详情、库分页/筛选、收藏、搜索/聚合、人物/合集及发现页消费已有 Go displaycache，不新增宿主磁盘缓存、缓存键、TTL或失效规则。
2. 签名：`DisplayCommands.display(command,args) -> AsyncThrowingStream<CoreDisplayUpdate,Error>`；`MediaAPI.request(command,args,snapshot:)`。CoreClient 每个 Ticket 独立持有 partial 回调，普通 call 保持原协议和最终结果。
3. 契约：partial → snapshot，result → 最终结果；每条更新按 seq 顺序消费。MediaAPI 每次回调和最终返回都检查取消及身份 epoch。流终止取消请求；晚到 partial 在 pending 已移除时丢弃。
4. 错误：网络失败保留已显示资料并报告失败；E_AUTH/E_NOTFOUND/E_PERMISSION/E_UNSUPPORTED/E_INVALID 清除不可继续展示的数据，与核心失效规则一致。核心实时错误不能被缓存命中吞掉。展示回调只处理可解码快照。
5. 正常/边界/错误案例：缓存资料先显示再刷新；无缓存等实时；切账号后旧partial不落页面；取消后晚到快照无效。详情缓存刻意不含实时收藏/已看/续播，用独立 DetailPresentation 解码；实时 MediaDetail 到达前不能触发依赖该状态的操作。
6. 测试：CoreClientCheck.displayContracts 验证快照/最终顺序、身份改变、实时失败与取消；移除流内 epoch 校验应被回归检出。真实磁盘命中/断网/跨进程和遥控仍需真机。
7. 正反例：错误是各端各造一套缓存键，或把核心partial强转成完整实时详情；正确是共用核心存取/投影/失效，宿主只映射展示和生命周期。Android 与桌面重复持久缓存已迁入核心；此结论仅指展示缓存层，不代表全部产品与真机验收完成。

## 全页面视觉与图片隔离

- 正式Android TV浏览基准是PhoneRoot(tv=true)接入ui/pages；不以旧tv页面或dp/pt数字直接比较效果。
- 共用TVSectionHeading、TVActionStyle、TVRowStyle、TVChoice和TVTextInput。输入草稿取消不写父绑定，完成才写回；核心保存仍由页面既有操作提交。密码入口固定掩码，关闭清草稿。
- 跨服图片来源取account/aggregate返回的line_url，缓存键必须包含来源服务器。显式空来源不回退当前服。所有图片请求仍走既有核心代理鉴权；宿主不拼其它账号token。
- check-tvos-visual.sh使用正式页面、合成核心/图片/播放表面，生成模拟器测试宿主后自动卸载。覆盖23个页面状态和3个滚动下半页，程序化滚动不算遥控验收。跨服同ID、缓存命中、身份清除和空来源有断言；移除服务器缓存键的故障注入必须失败。

## UI 对齐接线契约（2026-10-21，新增源码待运行验证）

1. 范围：库评分筛选、人物分页、详情音轨预选与播放器进度使用已有核心/引擎契约；不另造业务缓存或猜测轨道、缓存进度。
2. 签名：`emby.listItemsPage({parent_id,query:{rating_min,...}})`；`emby.personItemsPage({person_id,type,start_index,limit})`；`AppModel.openPlayer(...,audio: InitialAudio? = nil)`。业务请求的身份与空间由既有 `MediaAPI.request` 合并 `request_scope` 传入核心。
3. 返回：人物页的 `total` 可空，以 `next_index`/`has_more` 续页；按 ID 去重不能改变游标。音轨预选只在版本一致时通过 `TrackPreference.explicit` 匹配真实引擎 ID。Aether 7.39.0 的 `PlaybackClock.bufferedPosition` 是真实 source 轴连续缓存终点；缩略图只使用核心 `thumbs/chapters` 返回的可用图片。
4. 校验/错误：`has_more=true` 且游标不推进报 `E_PROTOCOL`；取消/身份变化拒绝旧结果。预选音轨不唯一时提示并保留等待迟到轨道，手选优先；不回退猜另一个同语言轨。缓冲仅接收有限非负值、绘制夹在有效时长内。
5. 场景：整页作品被屏蔽仍按核心游标继续；缺 Logo 的缓存被最终有 Logo 资料替换时重新加载。榜单累计分页保留真实跨页 `rank`，只有实际排名 1/2/3 使用领奖台，后续页不得重新标冠军。
6. 验证：`CoreClientCheck` 新增评分 query、空总数/空可见页/重复/未推进游标、Logo/类型字段、流序号不同于引擎 ID、章节字段断言；尚待在 Mac 执行。整源码 `check-tvos-ui.sh` 与同数据配对渲染通过后才记为运行检查通过；真实遥控、字幕、缓冲与选轨另验。
7. 正反例：错误是用可见作品数翻页、流 index 直接选引擎音轨、每页前三标全榜前三或填造缓冲比例；正确是使用核心游标、版本绑定的唯一轨道映射、真实 rank 和引擎时钟。

## 首页剧集与等待样式（2026-10-21）

- 首页剧集主播放调用实时 `emby.seriesPlaybackTarget({series_id})`；Android与tvOS共用Go选集，电影/分集原样进入既有播放命令。请求沿用身份/空间与epoch校验，失败不猜目标；不将展示缓存续播/已看状态作起播依据。
- 外观加载样式本机保存，0为平台系统样式，1/2/3/4/5/7复用Android同源JSON，固定Lottie4.5.1。样式4用公开的`**.Color`通配主题色，不硬编码图层名。资源/许可证/本地SPM由fetch-tvos.py和锁管理；检查宿主独立编译同版本源码。
- 等待统一TVLoading，确定性播放/续播进度条保留；减少动态使用中间帧，离页/后台/隐藏保留栏目停止循环。ProductRoot用tvPageActive传递栏目活动状态，不能仅靠onDisappear判断opacity隐藏页。
- 语言输入完成后即保存；字幕/主进度服/跨服续播/媒体容量即时保存并失败回滚；版本/音轨/字幕正则仍按核心整组校验提交。不要将“即时保存”扩展成拆开核心原子规则。
- MediaVersion.displayPath仅在界面展示；远程URL去用户信息、query和fragment，本地路径保留。CoreClientCheck有本地/Windows/远程/空路径断言；新版Swift回归尚待Mac执行。
- 本批源代码与资源已补；历史23页面图不覆盖新增加载预览，也不证明新布局视觉或真机通过。
