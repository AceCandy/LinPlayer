# 实施验证记录

日期：2026-10-22。基线：58f2494d；本任务仍为in_progress，未提交、归档或打包。

## 1. 已实施边界

- 手机入口转到PhoneLiuyingFeed，独立LiuyingPlaybackSession及Media3 1.11.0媒体槽；不读取普通内核偏好，不回退MPV，不启动普通后台播放服务。
- VerticalPager承载整页画面及条目控件，顶部导航固定；三页候选、三项核心准备（包含在途）、最多两个Media3实例。准备深度为约5/3/1秒，当前缓冲不足、后台或资源压力时收缩。
- 核心新增七条流影命令，正式起播/上报复用原实现；owner、generation、request及独立playback身份保护迟到结果和收尾。一个在途正式提交加最新目标，不为每次手势积压起播。
- 磁盘复用原媒体块缓存及既有总预算、版本验证、TTL/LRU；退出保留有效持久块，不新增SimpleCache、独立store或容量配置。投机读取共享四块额度，清缓存epoch阻止旧任务迟到创建/回填。
- 保留独立倍率、静音、收藏、筛选、循环及解放图标短按/长按行为。
- 生产diff未修改PlayerPage、PlayerController、PlaybackService、ExoEngine、ExoPreload及另一活跃任务；TV原分支保留。生成的三端命令绑定属于必要契约同步，不代表新增TV/桌面流影入口。

## 2. 最终本地门禁

| 检查 | 结果 |
|---|---|
| `bash scripts/check-core.sh` | 最终源码重跑通过：vet、无缓存核心测试、出库、FFI、C#宿主契约、差分及插件检查 |
| `bash scripts/check-bindings.sh` | 通过：335条契约命令，生成产物最新，C#/Kotlin编译、COMMANDS/Go/C#/Kotlin/Swift对账一致；Go另含两条调试命令 |
| `go test -race -count=1 ./player ./emby ./net/prefetch ./net/preload` | 最终源码重跑通过，包含新增在途块清理测试 |
| `go test -race -count=1 ./bus -run 'Scope\|LiuYing'` | 通过，仅scope及流影专项，不是整个bus包race通过 |
| `python3 scripts/check-android-args.py` | 269处调用通过 |
| Android定向矩阵 | 审查修复后重跑203条全部通过，零失败、错误或跳过；仅JVM/Robolectric，不是设备解码验收 |

Android矩阵明细：

| 测试类 | 数量 |
|---|---:|
| PhoneLiuyingTest | 44 |
| PhoneLiuyingChromeTest | 3 |
| LiuyingMediaSlotsTest | 6 |
| PhoneEnginePlaybackTest | 65 |
| PlayerControllerTest | 36 |
| PlaybackServiceTest | 11 |
| tv.TvLiuyingTest | 2 |
| tv.TvFocusTest | 36 |

最终结构/隐私检查结果见本文件末节。中间故障日志不入仓，测试报告位于已忽略的Android构建目录。

### 环境与复现

- Go使用本地1.27.2及项目env，不能用默认1.26.4替代；race与回环HTTP需可用的本地监听权限。
- Android使用本地JDK21、SDK及既有Gradle缓存，`--offline --console=plain`。只执行测试编译，没有运行APK打包命令。
- 核心门禁的C#环境使用本地dotnet及测试用libmpv；缺少`python`别名时在当前bash会话转发至python3，不修改门禁判据或go.mod。

```bash
# 先按shared/build-release配置项目本地工具链环境。
bash scripts/check-core.sh
bash scripts/check-bindings.sh
python3 scripts/check-android-args.py
# core目录：
go test -race -count=1 ./player ./emby ./net/prefetch ./net/preload
go test -race -count=1 ./bus -run 'Scope|LiuYing'
# apps/android目录：
./gradlew :app:testDebugUnitTest \
  --tests 'xyz.linplayer.app.PhoneLiuyingTest' \
  --tests 'xyz.linplayer.app.PhoneLiuyingChromeTest' \
  --tests 'xyz.linplayer.app.LiuyingMediaSlotsTest' \
  --tests 'xyz.linplayer.app.PhoneEnginePlaybackTest' \
  --tests 'xyz.linplayer.app.PlayerControllerTest' \
  --tests 'xyz.linplayer.app.PlaybackServiceTest' \
  --tests 'xyz.linplayer.app.tv.TvLiuyingTest' \
  --tests 'xyz.linplayer.app.tv.TvFocusTest' \
  --offline --console=plain
```

## 3. 故障回归与证据范围

| 故障/风险 | 修复及验证 |
|---|---|
| 关闭owner后更高代请求重新建立context | 在owner查找时无条件拒绝已取消context；`TestLiuyingCancelledResolveCannotPublishInNextGeneration`和`TestLiuyingClosedOwnerRejectsLaterGenerationWithoutResolving`先失败、修复后通过；后者另核验同owner Open拒绝、新owner可建立 |
| 清理返回后旧probe或块写入才完成 | `TestSpeculativeClearRejectsLateProbeAndBothDiskKinds`覆盖旧epoch延迟Start、probe及两种磁盘创建；`TestSpeculativeClearRejectsInFlightBlockWrite`挂起真实HTTP块，清理后放行，断言零重新落盘及新代可缓存 |
| 投机额度满时正式提升仍被阻塞 | Promote唤醒等待者；共享四块额度及暂停/提升测试核验取消收尾前不提前归还token |
| Media3能编译但启动报default方法未实现 | 显式转发LoadControl的Java default方法；真实Media3实例启动回归通过。预载status、invalidate及媒体源移交按固定1.11.0 AAR核验 |
| 旧媒体/Surface回调污染复用槽 | 唯一mediaId/revision及Analytics身份核验；`onlyMatchingMediaAndSurfaceCanPublishFirstFrameOrError`先失败后通过。注入事件不证明实际解码首帧 |
| 拖动时画面移动、条目控件却固定 | 控件移入各Pager页，离屏禁操作/语义；`itemControlsFollowTheDraggedPageButNavigationStaysFixed`先失败后通过，核对实际节点几何 |
| 页预取迟到或取消收藏导致跳错片 | 最新稳定锚点接入；当前取消收藏暂留至离开；对应页面测试通过 |
| 永久失焦后主动下一条仍不能播 | 主动目标变化清除阻断并重新申请焦点；`explicitNextAfterPermanentFocusLossRequestsFocusAgain`先出现请求数0而非1，修复后通过。同目标窗口刷新不覆盖暂停意图 |
| 测试误判页面未重进/广播未处理 | 冻结时钟测试显式提交Snapshot或排空Android主Handler；内核隔离mpv/exo/auto及真实注册广播/焦点监听回归通过。测试fixture修复不当成生产缺陷 |
| 慢Stop、迟到Play及频繁提升 | 50次目标选择只保留最新项，迟到Play先收回执再精确Stop；100次槽提升最多两个实例且备用静音。均为可控JVM/状态测试，不是真机连滑录像 |
| 缓存真正复用而非只存在文件 | `TestLiuyingWarmPromoteAndReentryReuseVerifiedBytes`使用HTTP请求/字节证明接管和重进复用；既有persistent回归覆盖进程重启、授权、版本、坏块、预算、TTL及关闭行为 |

## 4. 首轮独立只读复核（历史）

实施后另行复核Android链路与核心/缓存链路，主线程按具体出处核验：

- Android复核未发现明确可确认缺陷；媒体槽、焦点、退出和快速切换的真机时序仍未验证。
- 核心复核提出“同owner Open会复活已关闭owner”。核对`liuying_prepare.go`的同owner分支，取消状态会在建立新owner前返回E_INVALID；现有关闭owner测试亦明确断言该调用失败。此项与实际代码不符，未采纳，不为其增加无效改动。
- 主线程再检查生产diff边界、正式接管/旧Stop守卫、缓存epoch及最终测试结果；没有将复核者未执行的测试记为已执行。

## 5. 未验证与剩余风险

- **AC9未完成**：没有受支持真实手机、旧包同条件基线、实服起播或可见画面逐帧证据；没有P50/P95、PSS/codec峰值、弱网字节浪费及录屏数据，不能声称抖音级秒切或300ms达标。
- AC1至AC8仅有对应本地代码/契约/渲染/状态证据；实际SurfaceView双槽预渲染、低资源解码降级、系统旋转/锁屏、后台零声音/网络和连续真实100条播放仍待设备验证。
- 本轮未连接MediaStationGo实服，接口依据本机源码；未执行登录到播放、拖动/循环、收藏及Sessions真实上报联调。
- 缓存清理四切点及新代重建已有HTTP/磁盘回归；文件删除失败、磁盘满等真实故障尚未完整注入验证。
- 既有停止上报屏障保留；慢Stopped仍可能推迟新正式播放，页面可滑走且任务有界不等于画面秒切。若其主导真机P95，应返回设计而不是丢上报换速度。
- 全bus race曾发现普通queue测试对全局stall计时变量的存量竞争（queue_test.go与queue.go）；不在本任务扩改。最终普通核心测试及bus scope专项race通过，不宣称全bus race通过。
- task上下文检查提示Android规范超过自动注入上限；相关流影/播放段已按标题补读。没有为了消除提示搬迁规范或改全局注入配置。
- 未构建或安装手机/TV包，未修改依赖版本、普通缓存预算或用户持久数据。

## 6. 最终卫生检查

- `git diff --check`通过；新文件另按未跟踪文件检查空白，避免只检查已跟踪diff。
- 本次40个修改/新增文件隐私扫描通过。包含所有已跟踪及未跟踪文件的全仓扫描仍命中未改动的`journal-2.md:24`历史版本号1.4.363.0；该文件与HEAD逐字一致，不属于本次新增内容。没有修改白名单、基线或该记录，不将全仓扫描报告为通过。
- task上下文校验通过（保留Android规范自动注入超长警告）；其它活跃任务和通用Android播放器文件无diff。
- 已记录测试结果，删除本任务七份临时文本日志与AAR分析jar；不删除现有APK、参考图和其它任务产物。停止Android及绑定工程本轮Gradle daemon，未启动调试服务。
- 保持本任务in_progress，不自动提交、归档、推送或打包。

## 7. 用户审查后修复（2026-10-22）

仅修手机流影三处生产文件及对应测试；通用播放器、TV和Go实现未再修改。

| 问题与根因 | 修复与断言 |
|---|---|
| 自动下一条使用move，而Pager在more=false末尾没有可落定页，手动上滑无法触发新轮次 | 非空末页保留边界页，落定后按原feed/tab/channel/exposure/favorite、新seed和offset=0续轮次；边界无条目key时以新页裁剪三页窗口。循环模式下真实触摸连续跨三轮，断言每次只请求一次、新结果正式播放且可回滑；失败不自动请求，按钮同请求重试，成功空页仍可回滑 |
| 正式actor只在等待倍速保存/旧停止之前核验环境，等待期间后台或锁屏后仍提交Play | 在两次等待结束、Play前重新核验有效性及foreground/unlocked；两条挂起测试分别覆盖倍速保存与Stop，先断言后台期间未新增Play，再更新目标，恢复后只播放最新项。未取消已提交Play或改变回执精确Stop流程 |
| 失败warming被后续暂停标成interrupted，调度重建源时清failed，反复投机重试 | 错误清中断和样本标记，暂停/恢复/调度均排除失败项；正式选中仍可重建。通过Media3真实ListenerSet注入暂停前错误及暂停后迟到错误，反复调度失败状态和mediaId保持不变；正常中断对照仍可恢复同request |

### 先红后绿与关联回归

- 最初新增6条测试：末尾两条、生命周期两条、预载失败和正常中断对照。修复前5条复现失败，正常中断对照通过；修复后6条全部通过。随后补第7条覆盖暂停前/后两种预载错误顺序。
- 修复分页时首次共用了初始占位和末尾占位key，Pager按key自动跟到末尾，已有起播/倍速回归捕获同条重复起播。最终区分initial/loading key；边界接页即使索引未变也显式requestScrollToPage，防止跟随末尾key。新增首屏仅一次候选请求、一次Play断言，不能只断言有新轮次而漏掉额外请求。
- 生命周期fixture显式返回倍速保存成功；环境变更先应用并断言门关闭，再放行等待，避免将测试自身未接线的保存错误当成生产故障。
- 最终8类定向矩阵203条通过：本轮新增7条，普通播放器及TV回归数量不变。269处命令参数检查、空白及任务上下文校验通过。Go/绑定生产源码本轮未改，沿用前轮门禁证据，不声称本轮重跑。

### 独立复核与限制

- 独立只读复核并按当前磁盘抽查三处小diff，最终未发现可确认的新缺陷。前轮复核所称“等待后仍只检查valid”与当前额外前台检查不符；“失败必须再次上滑重试”不属于既定按钮重试契约，未据此扩改。
- 本轮未提交、归档或构建安装包；AC9及第5节实机、实服、真实Surface/解码和性能限制保持未验。测试错误/样本注入只证明状态与调度，不代表真实媒体故障或抖音级首帧。
- 本轮40个修改/新增文件隐私扫描及19个未跟踪文件空白检查通过；已清理5份临时测试日志、AAR分析文件和3份源码对照副本，停止测试启动的Gradle后台进程。

## 8. 提交与手机包授权（2026-10-22）

用户在上述修复与验证完成后明确要求提交、归档并构建手机包。授权仅覆盖本流影任务，不推送远端，不修改版本号，不归档另一活跃任务。按项目手机默认arm64-v8a release流程出包，最终签名、架构、体积和交付路径验证记录于会话交付记录；不以出包通过补勾AC9或其它真机/实服未验项。
