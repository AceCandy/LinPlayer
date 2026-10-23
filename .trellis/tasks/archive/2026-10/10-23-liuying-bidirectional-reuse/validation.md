# 验证记录（2026-10-23）

## 改动与边界
- 当前起播即取下一批（含末页提前新seed），三页窗口/稳定锚点/失败原请求重试保留。
- 手机前二当前后三；停止时可保留有效代理，回滑原位接管；Media3源保留但已播period释放后必须重备片头，最多双播放器。
- 手机采样不再调用Progress，Playing/Stopped和通用上报保持。内容预热门从10/3秒改为3/1秒（按倍速），卡顿暂停。
- 核心新增Stop可选retain_request_id/retained；默认调用仍关闭代理，不改变通用player/TV行为。未修改其它Media3网络恢复任务。

## 红绿证据
- 新firstPlaybackFetchesNextBatchWithoutApproachingPageEnd在旧实现失败：首条起播时缺少offset=12请求。
- samplingAndPauseNeverSendLiuyingProgressButStillStop显式推进SystemClock后在旧实现失败；仅推进Compose动画时钟不足以覆盖10秒上报分支，已修正测试。
- TestLiuyingRetainedStopReplaysSameProxyWithoutResolve对HEAD源码overlay失败：停止未移回准备池；不是编译失败作为红灯。初次独立运行暴露fixture未注册命令，已让fixture独立初始化注册，再复现行为失败。
- 修复后核心真实HTTP断言回滑同URL/不同正式playback_id、从零起播、PlaybackInfo/probe/已缓存媒体请求均不增加。

## 已通过
- Android指定8组209条：PhoneLiuyingTest 49、PhoneLiuyingChromeTest 3、LiuyingMediaSlotsTest 7、PhoneEnginePlaybackTest 65、PlayerControllerTest 36、PlaybackServiceTest 11、TvLiuyingTest 2、TvFocusTest 36。
- 新增覆盖：双向五邻项、旧已播request复用、起播取批去重、停止但无Progress、3秒缓冲预热/低缓冲让路、旧Prepare取消回执清理、源保留与样本状态重置。
- Go player/emby/net/prefetch/net/preload完整包race -count=1通过；包括五项+瞬时第六项上限、租期不续、旧计时器身份隔离、禁预载/实时/清缓存/代数/选版失效，以及Promote/Demote并发预算归还。
- check-core.sh完整门禁、check-bindings.sh完整门禁通过。测试初次缺少libmpv动态库路径导致普通mpv用例失败；加载已有.toolchain/mpv-test运行库后完整重跑通过，未放宽测试。
- Android参数检查通过，命令绑定已重新生成（生成文件不入仓）。

## 独立审查
- 核心所有权/锁顺序/租期/容量及Android分页/生命周期分别只读审查；最终切换门、过期清理、NOT_PRELOADED和ReadControl上下文复用再次独立复核，未发现确认缺陷。
- 空页不无限自动重取是既有空态约束，仍可回滑；不是需要修复的循环分页缺陷。
- 筛选变化由根key(identity, space, generation)销毁旧Pager，旧LaunchedEffect取消；接页前ensureActive与等待滚动的可取消协程拒绝迟到响应。
- Media3固定1.11.0以本地AAR字节码核验；manager引用不保证已播period样本保留，PreloadStatus不允许null，停用源用NOT_PRELOADED。

## API基准与未验
- MediaStationGo本地基准4097a299，已有其它工作树改动未动；查阅实际路由与emby_playstate_handlers.go，Playing/Progress/Stopped共用handler，保留开始/停止契约；未启动或修改服务端。
- 未做真实手机安装、解码/Surface可见首帧、弱网连续滑动P50/P95/PSS测量，也未进行真实服务端联调。JVM状态注入和HTTP mock不代表已经达到抖音级丝滑。
- 回滑复用限同身份/范围、最初解析2分钟内有效点播；窗口外、过期、媒体变化或不支持代理的源仍可能需要加载。磁盘完整有效块继续按共享预算保留，不提供离线授权。
- 既有Stopped有序屏障不绕过；极慢停止上报仍可能影响连续切换。实施验证阶段未提交、未归档；现已获用户提交归档授权，本轮不打手机包。

## 收尾
- 最终版本209条Android回归再次通过；diff空白检查、本次21个文件隐私扫描与任务上下文校验通过。
- 本轮Gradle后台进程已停止；本轮临时日志、HEAD对照源码和分析jar已清理，不改既有安装包。
