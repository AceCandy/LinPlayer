# 扩大媒体缓存并排查 TV 高码率退出

## Goal

媒体磁盘默认总预算调整至2GiB、单片512MiB，保留已有设置；调查TV高码率退出并补齐可验证的必要修复或诊断。

## Requirements

- 磁盘媒体缓存默认总预算 2GiB、单片上限 512MiB，Android 设置回退同步；合法已有设置（包括关闭和旧 1GiB）保留。
- 内存缓冲默认自动、详情预热 32MiB+2MiB、预取窗口和线程数保持现值。
- 识别 TV 高码率退出的可能路径及公开问题；只修正有代码证据的缺口，不能把相似 issue 当成用户设备根因。
- 系统退出原因（尤其低内存）与 TV 起播/主动离页日志补齐数值线索，不输出媒体地址、标题或账号；不将正常后台回收变成自动崩溃上报。
- 不出安装包；按用户最新授权审查、修正后提交归档。

## Acceptance Criteria

- [x] 缺字段使用 2GiB，已有合法预算及关闭值不变。
- [x] 超过 512MiB 影片的单片预算、头尾固定槽、续播缓存及索引容量变化回归通过。
- [x] 首次发现系统低内存退出可在日志定位，自动崩溃报告策略不变。
- [x] Go 与 Android 受影响回归通过，独立复核完成。
- [x] 容量调整与诊断范围完成；真实 TV 退出原因确认及修复验证移交 android-playback-device-validation，未标记为故障已解决。

## Notes

- 普通播放错误会显示失败面板；ON_STOP、主动返回或自然完播会离页，系统杀进程/原生崩溃也可能呈现类似现象。
- 持久缓存恢复只读块头，扩大磁盘槽数不会把512MiB正文一次读入内存；容量变化按现有索引兼容逻辑一次性重建旧大文件缓存。
- 公开资料：https://github.com/androidx/media/issues/2860 （1.8.0缓冲过大OOM）；https://github.com/androidx/media/issues/3405 （1.11.0 TV高码率吞吐不足，非崩溃报告）；https://github.com/mpv-android/mpv-android/issues/1208 （特定H264硬解异常）。不能由这些报告推断本机已复现。

## Validation

- 修改前：TestMediaCacheDefaultAndExistingPreferences 因默认仍为1GiB失败；TestPersistentKeepsResumeBlockBehind384MiBForwardBuffer 因续播块淘汰失败；ReportExitTest 首次低内存退出无日志的断言失败。修正后对应回归通过。
- go vet 与 go test -race -count=1：config、prefs、net/prefetch、player、net/preload 五包通过。
- Android 编译及 ReportExitTest、PlaybackBufferTest、PlayerControllerTest、TvPlayerLayoutTest 共60项通过，无失败/跳过。未生成APK。
- 独立只读复核无阻断问题；git diff --check通过。
- adb 无已连接设备，未取得用户TV型号、实际内核、缓冲设置或退出现场，真实崩溃根因与修复仍未验收。下一步需要复现日志；不能将容量提高或诊断日志补齐表述为崩溃已解决。
