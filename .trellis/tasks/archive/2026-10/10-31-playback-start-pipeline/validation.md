# 交付与验证

## 已实现

- c1beeb08：持久缓存恢复只读索引、块头和长度；首次供给仍验正文哈希，损坏回源；只读端点及缓存带不把未验证候选当成可用数据。开放Range最多预验证一个候选块。
- bfac4af5：普通停止通过partial区分本地与最终收尾，宿主分别维护起播/完整屏障；旧完整任务不能被快速结束的新任务覆盖。停止期间保存最终续播位置，按完整Session、条目、分类三态隔离；明确位置、从头播放和版本/时长不匹配不接管。主服模式保留等待，旧Stopped始终先于新Playing。
- e740f809：正式新建代理复用首块探测响应，只等响应头。准备及受限读取仍0-0，返回前取消桥接、返回后代理拥有响应；缓存候选、非零块首读、范围不匹配和Close释放未用body。首块内偏移按原固定块策略处理；并发连接保留既有独立读取行为。
- 内核策略：Media3流媒体首播500ms，恢复2000ms，本地自动/自定义容量对照旧策略。MPV受共享链路优化影响，增加准确的同步提交阶段日志；实际打包版本cache-pause-initial默认已关闭，不重复设置，无可靠entry归属的事件不作为首帧证明。

## 验证结果

- Android定向139项测试通过：PlaybackBufferTest 3、PlayerControllerTest 40、PlaybackReturnTest 2、PhoneEnginePlaybackTest 67、StartupTimingTest 6、TvTrackTimingTest 6、TvRefreshRatePlaybackTest 15。
- player、progress、net/prefetch包全量race通过；bus的新Deferred及命令接纳race通过。覆盖迟到上报、调用方取消、主服等待、私密/普通/未分类边界、恢复坏块、并发首块、短媒体、延迟正文、错误Range、忽略Range、no-store、重定向、截短重试、版本变化、清理代数、未消费/失败响应关闭。
- 核心门禁中的go vet、全部Go单测、核心库构建、FFI头契约、C#核心契约、差分及其余步骤通过；完整脚本最终非零仅因TV插件入口对账缺口。
- check-bindings全部通过；333条命令与C#/Kotlin/Swift/Go对账通过。check-android-args检查196处调用通过；分项暂存diff和隐私检查通过。
- 使用修改前HEAD的隔离源码副本复现TV缺失plugin.anchors/homeItems/homeSections/searchActions/settingsSections/sidebar；用HEAD bus.go的Go overlay复现旧queue_test修改stall参数与watchStall之间的数据竞态。二者未纳入本次修复，不能把完整门禁或全bus race写成通过。
- 同一缓存恢复基准的本机Linux/SSD样本：原版约136.7ms、300139432 B/op；按需校验约0.466ms、65106 B/op。仅代表恢复阶段，不是设备端端到端首帧数据。

## 独立审查与修正

分别对停止/总线、代理/缓存、Android进行只读独立复核。已修正：快照在锁内发布；TV迟到清理覆盖新标题；停止等待占满命令池；取消遗留屏障；尾lambda被新增回调参数误绑定；完整屏障覆盖；分类三态隔离。

新增失效停止回归最初错误复用了仍持有transportMutex的控制器，导致夹具等待；修正为跨页面的不同控制器并在finally释放门闩。首块内偏移测试按固定块/非固定块分别断言，未为测试改变已有取块策略。

## 未验证与限制

- 未生成安装包、未推送；未做Android手机/TV真机、桌面真实播放或同网络同影片的Hills对照测试，不保证达到Hills的可见首帧时间。
- 初始请求最多覆盖一个4MiB首块；热缓存命中立即关闭未用响应，但无法撤回上游在响应头到达前后抢发的字节。
- 主进度服模式仍保留同步所需等待；MPV提交耗时不是解码完成或屏幕首帧。回归均使用本地HTTP夹具，真实服务端与弱网效果仍需设备验收。
