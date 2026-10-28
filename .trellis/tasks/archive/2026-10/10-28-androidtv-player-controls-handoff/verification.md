# 验证记录

日期：2026-10-28。实现和验证完成，用户已授权审查通过后提交归档；实际提交与归档以 Git 历史和任务元数据为准。未出包/安装/推送。

## 变更
- 生产代码仅 TvPlayerPage.kt、TvPlayerParts.kt；不改 Go、绑定、手机生产代码或通用浏览按钮。
- 底部纯图标 OSD、电视剧下一集、更多内上一集/插件、直接倍率；实际进度宽度，倍率文字自然伸展；保留可访问名称与遥控行为。
- TV 接入既有 MPV/Media3 队列接续。保留准备/预热规则和常规回退；接管旧/新控制器身份、退出屏障及迟到回执隔离。

## 红例及中间反馈
- 新布局与 MPV 连续队列测试在原实现上先失败：仍有字幕文字按钮，未发送 queuePrepared。
- 首轮实现批次 43 项中 9 项失败：新焦点用例没有提供时长，旧 TV fixture 未提供 stopPlayback 成功结果，以及精简时移除了实际版本规格。补齐正确状态样本/命令替身，并在底部保留紧凑真实规格，不删除版本身份断言。
- 初次编译发现嵌套布局隐式 maxWidth 接收者冲突，已用外层不可变宽度解决；不绕过类型检查。

## 上一版最终回归（紧凑布局前）

项目 JDK 21 / Android SDK / Gradle 缓存，执行 :app:testDebugUnitTest，Roborazzi record 模式；不是 APK 构建。

上一版同批 **221 项通过，0 失败、0 错误、0 跳过**，耗时 2 分 2 秒：

| 测试类 | 项数 |
|---|---:|
| TvFocusTest | 21 |
| TvPlayerLayoutTest | 4 |
| TvPhoneParityTest | 15 |
| TvTrackTimingTest | 5 |
| TvRefreshRatePlaybackTest | 9 |
| TvLiuyingTest | 4 |
| PhoneMedia3RecoveryTest | 3 |
| PhoneEnginePlaybackTest | 65 |
| ExoPreloadRecoveryTest | 3 |
| PlayerControllerTest | 37 |
| PlayerEngineLifecycleTest | 2 |
| PlayerActivityTest | 23 |
| PlaybackServiceTest | 11 |
| PlaybackReturnTest | 2 |
| PluginPlayerKeysTest | 7 |
| VisualAlignmentTest | 10 |

- TV MPV 真实页面：连续接管、EOF 不抢跑、重复事件、排队失败/取消、手动切集/快退/换版本/切核、已就绪准备消费、慢准备不阻塞、慢回执离页。
- TV Media3 真实播放器 + 本地合成 WAV/HTTP：自然接入后断流恢复，继续多集，同播放器/预载所有者，实际 AUTO transition，play 一次、stop 零次；慢 advanceQueued 时退出，等待后 stop 的 item_id 为新集、位置小于 10 秒，并释放播放器。同夹具保留手机原测试。
- UI：纯方向键遍历与进度提交、收起/唤醒、倍率面板焦点恢复、上一集、插件挂载/返回更多/卸载/恢复更多按钮。
- 参数门禁 192 处通过；git diff --check 通过。隐私检查不改 allowlist/baseline。

## 独立复核与视觉
- 分开完成队列与 UI 的只读审查，主线程核对原文及测试证据。审查提出的“插件返回会退出面板”不成立：实际传入 level 清空回调，新增逐级返回测试通过；“退出拿旧集位置”未证实，真实 Media3 挂起接管退出测试已核对新身份与新位置。
- 主线程另核对接管前后 controller、准备槽、异步取消和实际版本规格。发现离页可能跳过新控制器起播 effect，构造时即继承接管身份；倍率长文字使用自然宽度，避免大字号截断。
- 目视电影/剧集常态与焦点、字幕面板、隐藏、浅色主题、640×360/1.4 字号与 3.75×。控件收起像素断言证明底部渐变移除；隐藏图无字幕是夹具未渲染实际解码字幕，不代表关闭该能力。
- 实际 Compose/Robolectric 页面截图、合成背景，非真机解码：build/visual-alignment/tv/player-movie.png、player-series.png、player-hidden.png、player-series-sub-focus.png、player-subtitles.png、player-series-compact-large-text.png、player-movie-light.png。均处于忽略目录。

## 未验与风险
- 未执行全量 Android 套件。上轮 MaterialLoadingLifecycleTest 的全量组合失败原因仍未确认；本轮未修改或宣称修复它。
- MPV 核心事件由替身注入，未运行实体 MPV/Emby 视频会话；Media3 使用合成音频，不能证明真实视频首帧零间隙。
- 未实测实体遥控长按/重复事件、过扫描、HDMI 切屏、帧耗时、真服务器停止上报与所有字幕格式。
- 无新增调试服务；测试 HTTP 服务由清理钩子关闭，临时日志结束时删除；截图保留本地并忽略。

## 本轮紧凑布局与遥控修订（2026-10-28）

- 用户确认左侧季集/片名、右侧下一集/倍率/弹幕/音轨/字幕/更多，共处进度条上方，两端时间在下。删除常驻播放/暂停，真实版本规格移入既有媒体信息，身份与迟到请求回归随入口迁移。
- 隐藏确认暂停/继续，方向仅唤醒；进度条无预览确认暂停、有预览确认提交。未知时长回到画面仍可暂停/进工具，时长晚到不抢现有工具焦点。下一集/跳过须显式聚焦，提示消失后回进度；倍率、更多和插件面板返回保持原入口。
- 根节点必须消费画面聚焦时的确认 KeyDown，否则平台会把未处理的确认移交首个工具；换集时进度条失去时长不能继续持有焦点，须归还画面。两条边界已复现并验证。
- 长按恢复后的按钮按压像素曾残留：根消费释放导致 Surface 收不到配对事件。仅在播放页使用 playerConfirm 消费确认按下、执行短按释放，长按由根消费；保留按钮原触摸/无障碍回调和 disabled 门控。真实 repeatCount 序列、松手像素恢复及随后短按通过。
- 明亮帧副标题对比度先红；渐变在文字起点达到稳定遮罩，预览区变高不稀释文字遮罩。全白帧上副标题合成对比度 >=4.5，通过真实渲染像素检查。

### 验证过程

- 初始 3 项红绿批次：旧按钮仍存在、隐藏确认不暂停两项失败，原隐藏长按用例通过。
- 首轮 44 项有 4 失败：换集/未知时长焦点两项生产边界；接下来测试 tag 与新 memo 冲突、seek 断言误用了 position 而命令实际为 pos 两项夹具问题。按根因修复，25 项焦点回归通过。
- 第一批 130 项中 128 项通过；两个 VisualAlignment 用例仍查旧合并时间码。按新布局分别断言当前时间与总时长。
- 额外像素检查发现上述长按按压态及明亮帧对比度问题；两项均在修复前失败，修复后通过。

### 当前代码最终结果

同批 **131 项通过，0 失败、0 错误、0 跳过**，1 分 48 秒。使用项目工具链执行 :app:testDebugUnitTest 与 Roborazzi record；没有出 APK。

| 测试类 | 项数 |
|---|---:|
| PhoneMedia3RecoveryTest | 3 |
| PlaybackReturnTest | 2 |
| PlayerControllerTest | 37 |
| PluginPlayerKeysTest | 7 |
| VisualAlignmentTest | 10 |
| TvFocusTest | 28 |
| TvLiuyingTest | 4 |
| TvPageShots | 6 |
| TvPhoneParityTest | 15 |
| TvPlayerLayoutTest | 5 |
| TvRefreshRatePlaybackTest | 9 |
| TvTrackTimingTest | 5 |

- 参数门禁 192 处、20 个工作树文件隐私检查、git diff --check 通过；任务上下文 validate 通过，但 android.md 大于注入限额，采用相关段落精确读取。
- 两轮只读独立复核后主线程核对证据；首次审查称长按恢复没有 return，与实际 `return true` 不符，不采纳该误报。最终针对确认修饰器、禁用、重复事件及插件门控的复核未发现明确缺陷；实际运行由上述回归补充。
- 实际页面截图已目视：电影、剧集下一集/字幕焦点、隐藏、浅色、640×360/1.4 字号/3.75×；新增 player-series-bright-frame.png 为全白帧对比度夹具。均为 Compose/Robolectric 与合成素材，不是真机解码画面，保留于忽略目录。
- 本轮没有修改播放接续、核心、绑定、手机生产代码或全局 TV 控件。未跑全量 Android，未验实体遥控、真实 Emby/MPV 视频、HDMI 和性能；上一版未验与风险仍有效。
- 未提交、归档、出包、安装或推送。无新增调试服务；临时日志已清理。

## 最后清理与提交前审查

- 用户确认删除进度上方的片头区间短黄线；仅停止向 TV 播放页 ProgressBar 传入 intro 绘制范围，不删章节数据、跳过提示或自动/手动跳过逻辑，不改共享 ProgressBar 或手机。
- 当前代码 TvFocusTest 28 项 + TvPlayerLayoutTest 5 项同批全通过（33 项，0 失败/错误/跳过，17 秒）；涵盖实际跳过命令、焦点恢复、遥控、接续边界及新页面截图。此前紧凑布局最终同批 131 项通过仍见上节；未冒称本轮执行全量。
- 已目视最新 player-series.png，片头短线消失，实际播放进度、缓冲和章节刻度保留。
- 分别独立审查当前待提交的 UI/遥控和队列接续代码，均未发现确定缺陷。关于外部直接移除页面的 stopPlayback 并发风险，主线程追查 PlayerController.stopIn：它等待 pendingStop，而 handoffIn 会登记该屏障，故“没有显式 join 就并发 stop”的推断不成立；外部强制移除时的实际视频层释放未新增专项验证，不能称已真机验收。
- 没有新增调试服务；本轮临时测试日志已清理。用户授权将本任务生产代码/测试/规范提交后归档，并记录会话；不出包、不安装、不推送。
