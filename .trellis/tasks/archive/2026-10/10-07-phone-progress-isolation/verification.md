# 核查与验证

## 背景
用户反馈上一轮手机后台/焦点粗测无问题；该反馈不覆盖本轮换目标修补、特定解码回退样本或TV。
MediaStationGo当前提交0c32eee7c2169d4da3b0873a4af5b8e80be41e5b，工作树只有未跟踪core，未读取该产物；未改服务端。

## 静态契约
只读对照PlaybackInfo、流Range/重定向、UserData秒/ticks与Sessions Playing/Progress/Stopped，未发现确定不兼容。两侧各自测试不能代替真实账户联调；本轮没有运行服务或访问账户。

## 回归证据
- 原页面切版本断言停止服务记录失败（期待PlaybackService、实际null）；原服务NonCancellable状态查询停止后返回仍触发上报，断言失败。
- 原旧服务销毁后新Exo暂停无效；同服务重绑后立即采样次数期待1、实际0。修补前9项Service回归有3失败，页面独立回归也红。
- 本轮新增切换前音量恢复回归：真实服务将Exo音量duck为0.3；删除页面恢复行后切换仍为0.3（期待1.0），恢复代码后绿；故障代码未保留。
- 最终 PlaybackServiceTest 10、PhoneEnginePlaybackTest 4、PlayerControllerTest 10、PlayerEngineLifecycleTest 2、PhonePlayerPanelTest 6、PhonePlayerOsdTest 3，共35项通过，无失败/错误。
- 三次独立只读复核：依据复核保留同实例焦点恢复意图，并补充duck切换回归、操作前停止队列为空断言。Robolectric4.17实际getNextStoppedService消费队列，非历史窥探。最终复核通过。

## 范围与限制
- 仅手机同页换目标/服务采样与排队控制隔离，核心命令/绑定不变。
- 已发出的核心命令无法撤回；本轮保证过期状态查询不再产生后续上报、尚未进入FFI的过期控制不再发出，不宣称撤回在途命令。
- 通知停止后页面导航未修改；没有已确认行为契约，不将停播留在页面直接当作新功能需求。
- 人工Robolectric旧服务销毁与新绑定排序只验证共享引用安全，不证明平台必然同时存在两个同组件实例。
- 未验：真机Service重启、来电/焦点、网络首帧/性能数据、具体复杂字幕/解码回退样本及实际MediaStationGo闭环；TV真机继续暂缓。

## 门禁与交付
- 参数门禁255处通过；任务JSONL与git diff --check通过；1474个tracked/untracked文件隐私扫描无新增，baseline411未改。
- 手机包63,098,071字节；SHA256 7c601ee887dcbcb684afb5fa290393ac29340f07a2985da97e40f6f021334439；与本次Gradle产物一致；13个arm64 ELF库包含mpv/核心；apksigner min23下v1/v2/v3有效。
- 最终包build/android/app-arm64-v8a-release.apk，TV旧包未更新。
- 用户已确认本轮具体提交清单，执行本地提交、归档和日志记录，不推送。
