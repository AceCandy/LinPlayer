# Hills 弹幕能力核查

## 样本与证据范围
用户提供 `docs/cankao/Hills-v1.10.0-arm64-v8a-release.apk`，包名 `com.mountains.hills`，versionName 1.10.0，versionCode 4134。本次读取 APK 原始资源、protobuf 与 arm64 编译产物可读字符串，并在已有 API30 Google APIs x86_64 镜像安装启动。

## APK 可直接核实的声明
- `lib/arm64-v8a/libapp.so`：`SettingsDanmuScreen` / `SettingsDanmuUrlsScreen`，有 Add / Edit / Delete Danmaku API URL；`This Danmaku API URL already exists` 和 `danmuBaseUrlFormatError` 对应重复与格式校验文案。
- API 管理说明原文：`Manage Danmaku API URL, supports long press to adjust priority`。这明确表达优先级意图，但本轮没有观察运行时请求顺序和失败切换。
- `Remember Manually Selected Danmaku` 的说明为 `It will prioritize loading the danmaku that was manually selected last time when enabled`。
- `Danmaku Match Mode`、`Danmaku Implementation Mode`、`Danmaku Style`、`Danmaku Simplified/Traditional Conversion`、自定义 TTF/OTF 字体、透明度、速度、行距、滚动/顶部/底部行数、同屏上限、防重叠、重复合并、屏蔽词和隐藏热度图均有设置字段或文案。
- 存在 `Unlock Pro to support more danmaku features` 文案，不能据此确定各项实际付费边界。
- APK 根 `last_danmu.proto`：`LastDanmu` 存 `type` 与可选 `source`；`DanmuType` 枚举为 DANDAN=0、EMBY=1、OFF=2。表明数据模型区分弹弹Play、Emby、关闭，不足以证明 Emby 弹幕来自哪种插件或接口。
- APK 根 `remember_danmu.proto`：`DanmuRemember` 含 bangumiId、animeId、episodeId、itemId、seasonId、episodeIndex、url；`DanmuRememberList` 为 repeated items。表明有条目/季集到弹幕标识及来源的记忆模型，未验证其具体落盘生命周期。

## 实际运行与限制
安装成功。正常 MainActivity 启动、带软件渲染参数重试，以及已由 manifest 核实的 ExoVideoIntentActivity 外部视频入口启动，均出现 SIGABRT，报 `Expected an exception after ExceptionCheck`，调用栈包含 `libndk_translation.so`，退回模拟器桌面。
因此未能完成设置页实际操作或弹幕播放验证；崩溃根因未定位，不能把模拟器上的失败说成真实 ARM 设备也不能使用。也未修改原 APK 来绕过检查。
外部播放器测试使用本地合成 H.264/AAC 视频及隔离容器内 HTTP 服务，无真实账号、媒体服务器或弹幕上游。没有访问现有 Redroid 容器。

## 与已有实现对照
- Yamby：上一轮已运行验证多源新增校验、拖动排序、重启持久化、删除；请求优先级/回退尚未验证。
- Hills：静态文案比 Yamby 已观察界面更明确地表达“优先级”和“优先使用上次手动选择”，还包含编辑 API 与多种弹幕显示控制。不能将编译产物字符串视为全部可达功能。
- LinPlayer：官方弹幕、自定义多源及屏蔽/去重保持不变。`core/danmaku/match.go:260` 的 MatchAll 并行查询所有传入源，最终按 Score 降序；配置排序不等同于严格逐源优先尝试。`core/danmaku/relay.go:77` 记住源和分集标识用于内存内接力，不能直接等同于 Hills 文案中的持久手动选择记忆。

## 本轮范围
仅核查、对照与记录，没有修改产品代码、自动扩展功能、出包或提交。临时模拟器、HTTP服务、AVD、日志、合成视频和ADB状态结束后清理，原始 APK 与既有 SDK 镜像保留。
