# 执行计划

1. [x] 确认计划后启动任务；补独立倍速与新控件行为回归，在旧行为下确认失败。
2. [x] 接独立速度配置、起播与调速，验证普通倍速双向隔离、换片和失败回滚。
3. [x] 调整手机控件、画面点击与右上短按／长按，接分页收藏面板及点选后收藏范围切片。
4. [x] 更新手机交互与多尺寸渲染测试，核验面板输入隔离和进度像素对齐。
5. [x] 独立只读复核全量diff，解决发现，更新设计正本与规范，报告已验／未验和风险。

## 验证

- 核心配置／播放器相关Go单测，覆盖速度默认、非法值、持久化往返、部分更新、失败回滚和起播作用域。
- 本地JDK21、Android SDK、Gradle离线运行 `:app:testDebugUnitTest` 相关 `PhoneLiuyingTest`、`PhoneLiuyingChromeTest`、`TvLiuyingTest` 与普通播放器回归。
- 需要更新渲染证据时用 `:app:recordRoborazziDebug`，查看真实生成的合成画面并明确非视频解码。
- `python3 scripts/check-android-args.py`、必要的绑定契约检查、隐私门禁及 `git diff --check`。

## 审批与交付

- 用户已确认收藏方案1，并回复“ok”批准最终计划；任务已启动并实施。
- 当前不提交、不归档、不出包，不处理其它任务。

## 验证记录

- 红灯：旧核心运行 `TestLiuYingSpeedPersistsWithoutChangingDefault` 未返回/保存独立字段；旧界面运行短按收藏/长按切模式用例失败。
- 核心：Go 1.27，本地现有libmpv测试运行库，`go test ./config ./player ./prefs -count=1` 与对应 `go vet` 通过。初次未设置运行库搜索路径时两项原生测试因mpv初始化失败中止，配置正确后整包通过。
- `TestLiuYingSpeedPlayCommand` 经真实命令入口、HTTP占位流与本地libmpv读取倍率，验证先流影2倍、再普通1.5倍，且持久值不变。用例仅模拟宿主图形通道就绪，不建立GL上下文、不核验解码或画面。
- Android：离线 `testDebugUnitTest`，PhoneLiuyingTest 27项、PhoneLiuyingChromeTest 3项、PhoneEnginePlaybackTest 65项、PlayerControllerTest 36项、TvLiuyingTest 2项，共133项通过。覆盖MPV请求作用域、真实Media3起播/调速倍率、普通倍率隔离、跨片/重进、延迟保存等待和失败回滚，以及收藏范围/分页/选中/迟到响应和短按/长按。
- 重进用例的手动时钟在界面为空时不自动投递快照通知，显式发送后再推进时钟；未删减重进倍率断言，未为测试改变生产行为。
- `recordRoborazziDebug` 通过；实际查看393×873竖屏、873×393横屏、320×640/1.3字号的合成覆盖层、暂停态、倍率面板、长标签筛选和收藏面板，以及真实页面组合截图。核验右侧仅两项、底栏三项、时间在进度左侧、进度圆点/轨道像素居中。截图保留在已忽略的 `apps/android/app/build/liuying-mobile/`，不代表视频解码帧。
- 两路独立只读审查覆盖倍速/播放生命周期与收藏/输入/布局，未发现确认缺陷；主线程复核最终diff并移除无关格式变动。
- Android参数门禁266处通过，生成绑定328条保持最新；命令表/Go/C#/Kotlin/Swift五方对账通过。
- 本轮变更文件通过隐私红线门禁与 `git diff --check`；没有新增真实地址/凭据。无临时调试服务需要保留。
- 未执行全仓核心出库门禁、跨平台绑定重新编译、安装包构建或实服联调；未连接手机验证视频解码、系统旋转安全区、触控手感与帧性能。这些仍是设备验收缺口。

## 2026-10-22 审查后修复

- 收藏面板固定增加“返回全部视频”，清除收藏、频道和衣着条件，保留面板所选来源/分类并重新加载；直播和卍解不再依赖不存在的筛选入口退出收藏范围。
- 分页成功返回空列表时，只要保留上一页，继续提供手机滑动层供下滑回退；不开放空页向前切片，空画面点击不会暂停，横滑不会定位播放。
- 新增直播/卍解各两项回归，共4项：修复前分别因缺少返回入口或手势层失败，修复后通过。返回入口覆盖320×640小屏、873×393横屏及1.3倍字体；空页覆盖点击、继续上滑无效、下滑恢复原视频且不新增列表请求。
- Android针对性回归137项通过：PhoneLiuyingTest 31、PhoneLiuyingChromeTest 3、PhoneEnginePlaybackTest 65、PlayerControllerTest 36、TvLiuyingTest 2。独立只读复核未发现这两项修复的明确缺陷。
- 两次合成截图任务通过，但实际查看发现只捕获背景，未包含Popup；调整测试时钟仍未解决，故移除无效截图及新增截图调用，不将其作为视觉验收依据。入口可见/可点击以布局测试为证，弹层像素效果与真机体验仍未验证。
- 根因是隐藏筛选入口时未逐模式核对退出路径，空态仅覆盖有筛选的分类；Android规范补充逐模式退出与空态回退检查，手机设计正本同步行为。
- Android参数门禁266处、变更文件隐私检查及diff检查通过。本轮未改Go，未重复Go测试；不提交、不归档、不构建安装包。

## 2026-10-22 提交归档与手机包

- 用户明确授权提交、归档本任务并构建手机包；不处理其它任务，不推送远程。
- 完整绑定门禁通过：328条生成绑定最新，C#/Kotlin编译及五方命令对账通过；Android参数门禁266处通过。
- 补跑完整核心门禁：Go vet通过，config/player/prefs等包通过；account的TestCalendarImageSurvivesAccountAllowlistRefresh及danmaku的TestResolveServerOverlay失败。在临时目录导出提交前HEAD并运行相同两项测试，复现相同失败，确认不是本次流影变更引入；未修改无关模块。其余9关（核心出库、FFI头文件、C#宿主契约、差分、插件等）通过，完整核心门禁仍为未通过。
- 首次门禁缺少python/dotnet命令路径；仅补本地工具路径后重跑，环境错误消除，没有修改门禁判据。
- 标准pack-android.sh生成手机arm64-v8a Release，版本2.0.0，最终文件build/android/app-arm64-v8a-release.apk，29,630,506字节。与Gradle中间产物逐字节一致，apksigner验证v2/v3签名通过，Manifest为手机且extractNativeLibs=true；13个原生库均为arm64 ELF并压缩打包，包含liblpcore/libmpv。
- APK SHA-256：2261ac7012761d4aede43a33b34f3fa9577053ec9893334d60b82eb58f495bc9。
- 本包未配置崩溃上报。未安装真机验证视频解码、旋转安全区、触控和性能；构建/签名成功不能替代设备验收。
