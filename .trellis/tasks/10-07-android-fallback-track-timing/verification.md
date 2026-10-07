# 验证记录

## 改动与根因

- 手机回退和 TV 轨表轮询原先在起播请求返回前就开始计时，慢取流能提前耗尽 16 次 × 700ms 的恢复窗口。现在使用同一份 `controller.ready` 不可变快照作为 effect key 与放行条件，起播成功后才开始轮询。
- 手机恢复仍按轨道标题/语言身份，TV 详情选轨仍按 ff_index；轮询次数、错误分类、默认内核和回退额度保持既有契约。
- 新增手机整页回退/拒绝回退回归以及 TV 慢起播选轨回归，同步 Android 规范。已将用户对上一轮三个手机面板场景的验收反馈补入归档记录，不将其算作本轮修复已获设备验收。

## 故障与回归证据

- 新手机慢起播用例在旧生产代码下失败：MPV play 仍挂起，预期 player.tracks 为 0，实际 16。另两项拒绝回退新用例及原换版本/选集用例通过。
- 手机修复后 4 项整页用例全部通过；自动解码错误经过实际 PlayerPage 的错误轮询、等待 stop、切到 MPV、重新起播与恢复，不直接调用控制器代替页面。
- 手机门闩验证 stop 未完成前只有一次 play；放行后实际引擎为 exo→mpv，核心选中版本 resolved-version 覆盖初始 requested-version，位置42.5秒、暂停、1.5倍速、40%音量和字幕关闭均恢复；起播挂起12.5秒后放行，晚到日语音轨跨 ja/jpn 身份恢复一次，旧 Exo 已释放。
- TV 新用例在逆向恢复旧生产 effect 后失败：起播挂起13秒期间预期轨表读取0，实际16。故障注入已恢复；放行后按详情 ff_index 恢复 audio/sub 两类一次。
- 最终相关回归60项全部通过：ExoPrefsTest 1、ExoSubtitleTest 1、PhoneEnginePlaybackTest 4、PhoneEngineSettingsTest 1、PhonePlayerOsdTest 3、PhonePlayerPanelTest 6、PlayerControllerTest 10、PlayerEngineLifecycleTest 2、TvFocusTest 15、TvRefreshRatePlaybackTest 8、TvRefreshRateTest 8、TvTrackTimingTest 1。
- 故障注入按项目安装的 Media3 1.11.0 AAR 经 javap 核实状态字段和异常工厂；Gread用于核对androidx/media仓库入口，未采用未核实的默认分支API。注入仅在测试内，不增加产品接口或依赖。

## 独立复核与门禁

- 两位独立只读复核分别覆盖生产 ready 门控/生命周期及测试忠实性/时序，没有阻断问题。主线程抽查两页 effect、begin/started 路径及放行后的选轨断言。
- 审查提出没有单独断言 effect 取消重启，但放行后实际选轨成功及仅一次的行为断言已覆盖 false→true 后恢复协程启动；无需为实现细节另造测试。
- Android 参数门禁255处调用、任务上下文清单及 git diff --check 通过。
- 隐私门禁沿用同一扫描器覆盖 tracked + untracked，没有新增真实地址或凭据，不修改欠账基线和豁免。

## 安装包

- 手机最终路径 `build/android/app-arm64-v8a-release.apk`：63098076字节，SHA256 `5401477ff2765ad6f10276ac2c4f64fb3da4b4ab870a24522d4beb9b8d885d84`；与本次Gradle产物一致。13个native库均为ARM64 ELF，包含liblpcore.so和libmpv.so。
- 手机pack入口及apksigner v1/v2/v3验签通过。签名检查minSdk23用于覆盖v1；应用minSdk仍为24。
- TV最终路径 `build/android/app-tv-armeabi-v7a-release.apk`：59405037字节，SHA256 `4331dfca5582a1284899ffcfa79ad47e720b0aff7dee46ad3b643d53879c4d3d`；与本次Gradle产物一致。13个native库均为ARM32 ELF，包含liblpcore.so和libmpv.so；TV pack入口及apksigner v1/v2/v3验签通过。
- 两端版本均为2.0.0/versionCode20000。最终产物保持忽略，不提供中间构建目录作为交付入口。

## 未验证与风险

- 本轮未在手机或TV设备安装、播放真实媒体，也未与MediaStationGo实际联调。页面替身证明调用和协程时序，不代表实际解码、渲染、网络或核心文件加载事件顺序。
- TV真机验收按用户要求暂缓，原刷新率活动任务保留；不把本轮JVM回归或出包记为HDMI/HDR/DV设备验证通过。
- 本次未启动adb或外部调试服务；安装包与测试生成产物保持忽略，Gradle使用单次进程，结束时执行 --stop 确认没有 daemon。

## 提交范围

单一本地工作提交：`fix(android): 起播成功后再恢复播放轨道`，不推送。

- apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/PlayerPage.kt
- apps/android/app/src/main/kotlin/xyz/linplayer/app/tv/TvPlayerPage.kt
- apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneEnginePlaybackTest.kt
- apps/android/app/src/test/kotlin/xyz/linplayer/app/tv/TvTrackTimingTest.kt
- .trellis/spec/frontend/android.md
- .trellis/tasks/archive/2026-10/10-07-phone-player-panel/task.json
- .trellis/tasks/archive/2026-10/10-07-phone-player-panel/verification.md
- .trellis/tasks/10-07-android-fallback-track-timing/prd.md
- .trellis/tasks/10-07-android-fallback-track-timing/task.json
- .trellis/tasks/10-07-android-fallback-track-timing/implement.jsonl
- .trellis/tasks/10-07-android-fallback-track-timing/check.jsonl
- .trellis/tasks/10-07-android-fallback-track-timing/verification.md

用户已确认按本轮清单本地提交，不推送；APK、截图及构建缓存不入仓。
