# 命令与 JSON 边界

## 调用契约

Android 页面统一通过 `AppState.call(command, args, onPartial)`，自动合并 server / token / user_id / device_id；不直接绕到 app.core.callJson。调用方明确参数按现有合并规则覆盖默认值。
桌面使用 `CoreClient.CallAsync(command, args, ct)`；命令名称、参数与返回形状核对命令表及 Go handler。

## 解析与验证

- 绑定 Commands.g.cs / Commands.g.kt 是生成文件，参数仍为弱类型 JSON，不能依赖编译器抓字符串命令与字段名错误。
- 解析复用 data/Models.kt 等既有 obj / str / arr 入口；区别字段缺失、null、合法空集合与能力不支持。
- 分页总数、版本 / 媒体流字段和鉴权值依据服务端真实返回，不补造假字段。
- 同一 payload 的公共解析约束放在拥有者，页面只做呈现，不各自推测类型。
- `download.list` 返回任务数组，读取 `status / received_bytes / total_bytes / progress / error`，不可读 `state / bytes / speed`。核心当前不发送 `download.progress`，手机在页面 STARTED 时每两秒采样，失败保留旧任务并清掉速度样本；首个成功样本不显示速度。`download.setThreads` 无参数才是并发数回读。`PhoneRecordsUiTest` 覆盖字段、刷新、失败恢复及离页停止。

## 正反例与回归

正确：核对 handler 的参数名、通过会话入口调用并检查错误码。错误：拼错字段仍因 JSON 弱类型而编译通过，或将不支持错误转成空列表。
运行绑定门禁；Android 参数变更运行 `python3 scripts/check-android-args.py`，回归断言实际参数与空值 / 错误行为。

依据：[命令表](../../../docs/go-migration/COMMANDS.md)、[AppState.call](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/data/AppState.kt)、[JSON 读取](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/data/Models.kt)、[CoreClient](../../../apps/windows/LinPlayer.Desktop/Core/CoreClient.cs)。

## 收藏库归属扩展

1. 范围：MediaStationGo 的现有收藏和 Views 响应，经核心 `rawItem` / `Item` 透传到手机。
2. 签名：`LibraryIds → library_ids: string[]`；`LibraryType → library_type: string?`。不改变命令参数或绑定。
3. 契约：核心可选字段使用 `omitempty`，保持旧服务端差分形状；Android 数组缺失/null/空均可解析。手机以 Views 库 ID 与收藏归属匹配按实际库名分组；LibraryType 仍作为可选字段透传。
4. 边界：库列表失败或全部库 ID 未知的条目保留原类型，不使收藏失败；库分类不要求 LibraryType。排序不重查网络。
5. 用例：新字段多库命中在各所属库展示；同名库以 ID 区分；旧接口仍按媒体类型显示；库名或条目 ID 前缀不能代替归属字段。
6. 回归：`Test收藏库归属与旧接口兼容` 走 HTTP 解析和核心 JSON；`FavoriteLocalSortTest` / `PhoneBrowseUiTest` 覆盖缺失、NULL、空、未知、失败与可点击分类及本地排序。
7. 正反例：错误是按 `hg-` 前缀或 `ParentId` 猜库；正确是按显式库归属与库 ID 匹配。跨仓字段判断先追到最终 payload，再核查客户端解析，不能把内部结构存在字段等同于接口已返回。

## 详情首播/上映日期

1. 范围：服务端详情 `PremiereDate` 经 Go `ItemDetail` 到手机标题下的日期，不新增网络请求。
2. 签名：`emby.itemDetail` 参数和绑定不变；可选 `premiere_date: string`。
3. 契约：`jstrPtrNonEmpty` 映射，`omitempty` 保持缺失/null/空串时的旧响应形状。客户端只格式化日期部分，不按设备时区转换，日期不可用时回落已给出的年份。
4. 边界：日期缺失不导致详情失败；不存在年份也不造月日。安卓 minSdk 24，日期格式化不能引入仅 API 26 可用的依赖。
5. 用例：完整 ISO 日期显示 yyyy/MM/dd；旧服只给年份仍显示年份；缺日期/空值省略可选字段。
6. 回归：`TestItemDetail上映日期透传且缺失不扩展旧响应` 检查 HTTP→JSON 真值及省略；`PhoneDetailOptionsTest` 检查日期/秒级时长只显示一次，无字幕与单线路隐藏，保留版本/轨道选择。
7. 正反例：错误是在 UI 读取从未返回的字段，或把单集时长在头图、统计与按钮旁重复显示；正确是先确认真实 payload 映射，再集中呈现日期与时长。服务端基准 MediaStationGo d291efd，核查最终详情响应的 ReleaseDate→PremiereDate。

## 媒体信息可选字段

1. 范围：PlaybackInfo 中选中 MediaSource 的文件信息和 MediaStreams 探测参数，经核心 `emby.itemMedia` 透传到手机，不增加请求或修改服务端。
2. 签名：版本新增 `path / date_created: string?`；轨道新增 `bit_depth: int?`、`color_space / pixel_format: string?`、`is_forced: bool?`，命令参数与绑定不变。
3. 契约：新增字段使用指针与 `omitempty`，缺失/null/空字符串省略，`is_forced=false` 必须保留。客户端未知值隐藏，不能以零值补造探测结果。源 Path 仅供展示，不作播放 URL。
4. 边界：旧服务端无新字段仍正常显示；远程地址（包括无 scheme 的 authority）剥离账号、主机、查询和片段，仅显示路径，本地路径保持；添加时间按设备时区格式化，无效时间隐藏，不能混用首播日期。
5. 用例：完整视频显示位深/色域/像素格式，显式强制 false 显示 false；普通旧响应不新增字段；远程 URL 的凭据不能出现在媒体摘要。
6. 回归：`Test媒体信息文件与轨道扩展字段透传` 验证 HTTP→核心 JSON、false 与旧形状；`PhoneDetailOptionsTest` 验证参数呈现、路径脱敏、正文展开、横滑、大字号与深浅主题。
7. 正反例：错误是将源 Path 当作可请求地址，或在 UI 读取核心尚未透传的字段；正确是先核查最终 PlaybackInfo，透传可选值后按选中版本呈现。服务端基准 MediaStationGo d291efd 的 `baseMediaSource / mapProbeStream`。

## 手机详情从头起播

1. 范围：详情页从头菜单到 `Route.Player` 再到已有 `player.play`；只影响本次操作，不修改全局设置或观看历史。
2. 签名：路由新增 `fromStart: Boolean = false`；核心命令仍用已有 `from_start: bool`，不改绑定。
3. 契约：普通播放、长按换内核保持 false 和旧 payload；仅 true 时发送 `from_start=true`。版本、目标和宽高比沿用原详情选择规则。
4. 边界：核心 `resumeArg` 将其转换成从头哨兵并绕过服务端/本地续播，mpv 与 Exo 共用处理；不得以 `resume_secs=0` 代替（零值会回读历史）。不强制指定第一版本。
5. 用例：续播按钮显示剩余秒级时长和比例进度；从头菜单仍播放当前目标和版本；切季保留原请求取消、同季重试和插件插入位。
6. 回归：`PhoneDetailOptionsTest` 断言浮出菜单无居中 Dialog、preferred 版本及 fromStart 标记、剩余时间与进度、季菜单请求；`SeasonSelectionTest` 保持手机/TV 换季迟到响应与同季重试验证。真机首帧位置需设备验证。
7. 正反例：错误是把从头操作仅映射成零秒或只改按钮文案；正确是发送核心已有从头标记，同时保持默认内核和所选版本。

## 手机播放偏好

- 默认速度来自已有 `prefs.getPrefs.default_speed`，读取完成再起播；Exo 在加载前设速，mpv 沿用核心起播默认。手机显式调速调用 `player.setSpeed` / Exo 控制，并另用已有 `player.setPlaybackPrefs(default_speed)` 保存全局；保存按点击顺序串行，使用应用作用域避免离页取消。核心临时 `player.setSpeed` 语义保持，不能让TV临时加速落库。
- 字幕选择顺序：用户正则 → 用户语言 → 两者皆空时明确简体标记 → 既有默认回落。关闭字幕优先；泛化中文不猜简繁。详情预览和Exo复用 `preferredTrackIndex`，mpv同规则；`TrackPrefsTest` / `PhoneDetailOptionsTest` 验证显式设置、简繁选择及预览一致。
- 音轨语言为空/und及整个标题等于「未标注」时隐藏占位值，回落真实编码/轨道标签；不能全局改 `langCn` 影响字幕。
