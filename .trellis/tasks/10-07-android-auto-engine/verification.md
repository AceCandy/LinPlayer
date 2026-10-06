# 验证记录

## 实现与独立复核

- 手机与 TV 新增自动 / Media3 / MPV；保留默认 MPV、旧偏好值与显式手动内核。自动仅用于 Emby，明确兼容错误允许一次 Media3 → MPV 回退。
- 回退先停止旧媒体输出并等待核心会话收尾，保留核心选中版本、当前位置（零秒使用 from_start）、暂停、倍率、音量、轨道身份及字幕关闭。
- 手机已有版本/选集入口统一走页面控制器，版本续当前进度，换集不沿用上一集版本或位置。离页 stop 登记进程屏障，新页起播等待完整收尾；同页切集仅停止一次。
- 已进行错误分类、生命周期/服务切换、轨道恢复、离页屏障的独立只读复核。最后一次复核检查偏好快照门控、手机目标回调与页面存续清理，未发现阻断问题；主线程抽查了相应源码。

## 已通过

- Android 相关回归共 47 项，无失败：PlayerControllerTest（10）、PlayerEngineLifecycleTest（2）、PhoneEngineSettingsTest（1）、PhoneEnginePlaybackTest（1）、ExoPrefsTest（1）、ExoSubtitleTest（1）、TvFocusTest（15）、PhonePlayerPanelTest（1）、PhonePlayerOsdTest（3）、PhoneDetailOptionsTest（12）。
- 手机页面测试补强后单独重跑通过，额外断言零秒换版本使用 resume_secs=0 / from_start=true，换集不携带旧 resume_secs，调用顺序为 play → stop → play → stop → play。
- 故障证据：页面测试在修复前因初始播放请求为两次而失败；切版本前即能复现。根因是 null-key 协程读取可变的新偏好，在重组前后各起播一次。已采用不可变偏好快照。
- 零秒回退断言经反向故障注入确认会失败，注入已恢复。回退停止顺序测试用挂起的 stop 验证等待收尾。
- `python3 scripts/check-android-args.py`：255 处调用通过。
- `git diff --check`：通过。
- 同一隐私扫描器覆盖 tracked + untracked，最终 1444 文件无新增命中，未改既有基线或豁免。
- 任务 implement.jsonl / check.jsonl 验证通过。
- 用户后续反馈：手动测试暂未发现问题。具体设备、媒体资源及覆盖场景未提供，按用户已测范围记录，不扩展为所有兼容场景通过。

## 安装包交付

- 手机 `bash scripts/pack-android.sh`、TV `bash scripts/pack-android.sh tv` 均通过；版本 2.0.0（versionCode 20000），未修改版本号。
- 最终手机包 `build/android/app-arm64-v8a-release.apk`：63,098,075 字节，SHA256 `741573242b7ef124a55cd3cbc7467895fbf3c90164e7f1d78a65e67a55227911`。
- 最终 TV 包 `build/android/app-tv-armeabi-v7a-release.apk`：59,405,043 字节，SHA256 `dce32e73bc15b4e082a701884dd93af4ab46a7de834476eccfdff7d98627a12a`。
- 签名工具验证两端 v1/v2/v3 均有效且证书一致；为检查 v1，额外指定签名检查 minSdk 23（应用实际 minSdk 未变，仍为 24）。未输出签名材料。
- ZIP/ELF 检查手机仅 ARM64、TV 仅 ARM32，均包含匹配 ABI 的 liblpcore.so / libmpv.so；最终包与本次构建产物一致。本环境未安装或启动两端包。
- 最终包、工具链与构建缓存保持忽略；临时诊断输出已从源码移除，已关闭 adb 服务及 Gradle daemon。

## 未验证与限制

- 本环境未连接 Android 手机/TV 设备，未独立安装验收或播放真实媒体流；已有用户手动测试反馈，但设备/场景未记录。Robolectric 和核心替身不能证明实际解码、首帧、服务端会话时序或设备兼容性。
- 未与 MediaStationGo 实际联调；本次复用现有取流、续播与会话命令，未改 Go 或生成绑定。服务端参考基准为 2c7d64e，保留其工作树修改。
- DRM 新接入、音频透传、HDR/Dolby Vision、显示模式/帧率切换不属于本次实施范围。
- 手机换版本页面测试验证零秒位置；非零回退位置由控制器回归验证，真实换版本的首帧位置仍需真机核验。
- 编码/出包阶段未提交；用户随后确认将双内核与 TV 刷新率改动整理成本地基线。提交后归档本任务，TV 真机验收继续在刷新率任务中跟踪，不推送远端。

## 基线收尾

- 两轮改动一起独立核对提交范围，未混入 Go/绑定/依赖变更、安装包或本地调试产物；63 项相关回归包含本任务原有 47 项，全部通过，源码/测试在该次回归后未再修改。
- TV 后续刷新率包已替换最终目录内本任务早先产物；当前 TV 包与未验硬件范围见 .trellis/tasks/10-07-tv-refresh-rate/verification.md。手机最终包仍为本任务产物。
