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
