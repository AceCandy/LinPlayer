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
3. 契约：核心可选字段使用 `omitempty`，保持旧服务端差分形状；Android 数组缺失/null/空均可解析。Views 的 `hongguo` 库 ID 与收藏归属相交时仅把 Series 呈现为短剧。
4. 边界：库列表失败、缺少类型、未知 ID 均保持原类型，不使收藏失败；普通 Movie/Episode 不归为短剧。排序不重查网络。
5. 用例：新字段多库命中只显示一份短剧；旧接口仍显示普通剧；库名或条目 ID 带红果字样不能代替字段。
6. 回归：`Test收藏库归属与旧接口兼容` 走 HTTP 解析和核心 JSON；`FavoriteLocalSortTest` / `PhoneBrowseUiTest` 覆盖缺失、NULL、空、未知、失败与可点击分类及本地排序。
7. 正反例：错误是按 `hg-` 前缀或 `ParentId` 猜库；正确是按显式库归属及类型匹配。跨仓字段判断先追到最终 payload，再核查客户端解析，不能把内部结构存在字段等同于接口已返回。
