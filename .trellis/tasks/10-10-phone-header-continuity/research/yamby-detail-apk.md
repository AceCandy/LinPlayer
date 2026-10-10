# Yamby 详情转场 APK 核查

## 材料与方法

- 用户上传的 `docs/cankao/Yamby-v2.1.0.11-arm64-v8a-release.apk`。
- SHA256：`1c847168c34d2fd1df9ce24d783da3ab297cec2b09389c194020d056fb18af7b`。
- JADX 1.5.3，开启 `--no-res --show-bad-code`；对关键类再用 Android SDK apkanalyzer 查看原始 DEX 指令。静态核查阶段仅解析文件，未安装、执行参考应用或修改其 APK；后续实际运行证据见文末追加观察。
- 下文编号类名和方法名是本次反编译别名，行号属于本次临时输出，不能当成稳定 API。原始标识为不可打印字符时，通过类声明的映射注释和 DEX 核对。仅保留研究结论，不保留第三方反编译源码。

## 详情与导航调用链已经连通

1. 原始路由类为 `com.hush.yamby.cmp.route.RouteDetail`，携带 `itemId`。
2. `a2.C0036` 分支 3，临时输出 265–266 行，将 RouteDetail 注册成 `a2.C0042`（其 toString 为 NavEntry），内容 lambda 为 `fc.C2151` 分支 3。
3. `fc.C2151` 160、213 行读取路由 itemId，调用 `fc.AbstractC2091.m5352`；后者 5673 行起获取详情 ViewModel、收集 UI 状态并在 5998 行调用 `m5347` 渲染。这不是仅从路由字符串推测出的联系。
4. 导航宿主 `fc.AbstractC2091` 6679–6686 行将上述注册函数交给 `x3.AbstractC6395.m11823`。后者明确处理 NavDisplay backstack，并将进入、返回和手势返回回调交给 `m11822 → m11821 → d2.C1426`。
5. `d2.C1426` 从场景 metadata 读取 `transitionSpec`、`popTransitionSpec`、`predictivePopTransitionSpec`，未提供时调用宿主回调。RouteDetail 的 NavEntry 构造使用空 metadata，不是详情条目另配一套独立动画。

## 普通进入与返回：实际回调受 native 保护

- 宿主创建 `ed.C1806(24)`、`ed.C1806(25)` 作为普通进入与返回回调。
- 原始类为 `ed.ۥۛۖۘۦۖۜ`。DEX 再核对确认其 invoke 是 `public final native`，不是 JADX 恢复失败后漏掉普通 Java 方法体。
- 类初始化调用 `com.nmmedit.protect.NativeUtil.classesInit0(39)`；APK 包含 `lib/arm64-v8a/libnmmp.so`。
- 因此本次 DEX/Java 静态核查不能确认这两个回调的 duration、easing、缩放和位移参数。不能把手势返回的参数直接说成普通点击进入或普通返回的参数，也不能由 APK 存在通用淡入函数断言其实际使用方式。

## 手势返回：DEX 可确认的组合

- 宿主传入 `ec.C1710(13, byte)`；原始类为 `ec.ۥۜۙۗ۬`。其 invoke packed-switch 分支 13 为 `:pswitch_46a`，以整数边缘参数选择方向；`d2.C1426` 在 predictive pop 分支实际调用它。
- 回调读取布尔偏好（本次未恢复其用户可见名称）；该偏好初始化默认值为 true。关闭时构造 EnterTransition.None 与 ExitTransition.None。
- 动画开启时，进入页组合淡入、横向滑入和缩放；进入页横向偏移为容器宽度的正或负四分之一，缩放从 1.25 到 1。淡入与位移使用 spring，dampingRatio=1、stiffness=1600。
- 离开页组合反方向的四分之一宽度位移、缩放到 0.5 和淡出。离开页三项使用 tween，默认 duration=300ms、delay=0，easing 为 CubicBezier(0.42, 0, 1, 1)。缩放进入部分沿用库函数默认 spring（dampingRatio=1、stiffness=400），不能把所有动画项概括成同一组 spring 参数。
- 上述分支的 Java 恢复结果遗漏了部分 ContentTransform 组合和返回值；最终以 apkanalyzer 原始指令为准，核对了实际构造和 `return-object`。不能只阅读 Java 片段就认为该回调不返回转场。
- 这证明 Yamby 的手势返回不是单独的整页淡入淡出。未在设备上测量该分支的实际进度、持续时间或是否在用户录屏中触发。

## 详情背景与当前项目的边界

- 作品详情渲染链 `m5352 → m5347 → m5345/m5348` 已确认。头图 `m5348` 按背景、缩略图、海报及所属条目图片分支选图，并调用 `m3.AbstractC3937.m7937` 的异步图片组件；本次未确认其全局图片加载器的 crossfade 参数与内存缓存命中行为。
- 此前探子找到的 Glide 卡片与评分文本 300ms alpha，不能当成 Compose 详情头图渐显证据。
- 本轮修改前，LinPlayer 详情路由进入仅 400ms fadeIn、退出仅 300ms fadeOut；它和上述手势返回组合不同。首页海报动画与详情页面进出必须分开处理，不把首页 450ms 参数移植成详情转场。
- 能独立实现上述已确认的手势组合；普通进入/返回若要尽量一致，还需要参考应用在设备上的动态测量。整页海报飞行、完整资料缓存、首次请求零等待、跨页面像素完全一致均没有由本次核查得到证明。

## 清理与验证范围

静态核查后删除该阶段全部临时反编译源码、DEX 指令导出、日志与工具缓存；该阶段没有启动调试服务、修改产品代码或编译新的 LinPlayer 包。后续参考包运行与产品修改见文末及任务验证记录，LinPlayer 真机转场效果仍未验证。

## 追加隔离Android运行观察

新建独立redroid测试容器，不修改现有容器，ARM64参考包经其native bridge运行成功。720×1280、2核/4GiB、软件图形，连接临时fakeemby副本；补足该参考客户端必需的ServerId、Policy、UserData与MediaSources/MediaStreams字段，不修改原夹具或生产服务端。账号、图片和媒体均为合成数据。

实际进入正常电影详情、普通返回、冷打开另一条目及重复打开同一条目，录屏并抽帧。控制详情HTTP延迟1200ms时，冷打开先是返回按钮和一个居中形变加载图标；响应后标题、评分、音视频选项、播放键与简介直接处在完整布局中，背景稍后渐显，没有逐行撑开播放区。重复打开已经看过的条目时，旧正文随页面滑入，未出现同样的整页加载标记。该现象支持保留展示快照，但不证明具体缓存类、寿命或持久化机制。

普通点击进入可见整页从右侧移入，普通返回整页向右移出；来源页面在过渡期间可见。它与之前只淡入淡出的LinPlayer详情不同，也没有在本轮电影样本中看到来源小海报飞成整个头图。采用300ms全宽滑动作为独立实现的近似；本次没有从native回调还原精确duration/easing，不能将观测近似或手势分支数值称为普通回调源码参数。

软件录屏实际平均约9fps，不能用于同设备帧耗时比较、物理弹簧精确拟合或真实手机流畅结论。只运行参考包；未编译新的LinPlayer APK，当前改动使用真实Compose/PhoneRoot的JVM渲染回归验证。
