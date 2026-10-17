# 跨端同步执行与验证记录

状态：两批代码同步与自动验证完成，Linux内嵌网页已补齐并通过真实X11/WebKit自检。原生/真机和实服验收尚有缺口，作为后续验收限制保留。用户已授权审查修复后提交归档，未生成安装包。

## 已完成
- [x] 核查核心写命令、桌面返回栈/缓存、TV栈顶组合及非重放广播；确认必须同时覆盖当前页和返回页。
- [x] 用户状态成功写入推进宿主代次；桌面/TV首页、收藏、媒体库、搜索、详情及TV分集重验。失败不推进、旧会话不污染新页面。
- [x] 整剧重取分集并重算播放目标；多页收藏/媒体库逐页重读原范围，整批成功提交，失败保留。TV刷新重试从零开始并防止重复在途请求。
- [x] 同服切用户重建TV当前页组合；仅清PageCache不足以清现有remember状态。新用例在修复前失败、修复后通过。
- [x] 榜单请求页/显示页分开，网络失败保留内容与页码，重试失败页；保留现有按钮分页。TV分类刷新失败保留榜单并提示原因。
- [x] 公共日历核对MSG与核心透传字段，显示broadcast_time/timezone，按JST回退星期，缺失时刻不编造。
- [x] 独立桌面/TV复核及最终边界复核；修复旧请求失败/UI Post代次、详情分集回写、刷新预览残留和同服切用户问题。
- [x] 更新frontend/state-management.md中的跨端返回刷新、分页和日历契约。

## 验证结果

1. Android：TvFocusTest 29项、UserStateInvalidationTest 2项、PhoneDiscoverUiTest 11项，42项全部通过。覆盖当前首页、整剧离页返回后目标、同服切用户、多页刷新失败保留/从零重试、榜单翻页失败/分类失败、JST跨日及现有遥控器焦点路径。
2. 桌面：定向编译tools/seekcheck/seekcheck.csproj --no-restore通过，0错误、23条既有依赖/可空性警告。没有执行publish/安装包构建。
3. 桌面--discovery-only通过：真实HTTP/FFI榜单翻页失败、保留内容和正确页码、失败页重试、空页、日历时刻/JST及精确匹配/权限撤销。
4. 桌面--user-state-only通过：真实详情收藏/已看重取、返回原收藏页面重验、多页刷新后页500保留整份旧列表及下次完整重验、失败写入/旧会话隔离。两个入口也通过既有跳转状态检查。
5. python3 scripts/check-android-args.py通过（262处）；check-style.sh七项通过；git diff --check通过。
6. 扩展SeasonSelectionTest发现既有phoneAppendingKeepsScrollPosition失败；已在HEAD原源码复现（Y位置684→664），不属于本批修改。基线同时确认新增TV首页刷新测试在原实现失败。没有将既有失败列为本次通过项。

## 复现命令

```sh
JAVA_HOME="$PWD/.toolchain/jdk-21.0.2" ANDROID_HOME="$PWD/.toolchain/android-sdk" \
GRADLE_USER_HOME="$PWD/.toolchain/gradle" PATH="$PWD/.toolchain/jdk-21.0.2/bin:$PATH" \
apps/android/gradlew -p apps/android :app:testDebugUnitTest \
  --tests 'xyz.linplayer.app.tv.TvFocusTest' \
  --tests 'xyz.linplayer.app.UserStateInvalidationTest' \
  --tests 'xyz.linplayer.app.PhoneDiscoverUiTest' --console=plain

DOTNET_CLI_HOME="$PWD/.toolchain/dotnet-home" NUGET_PACKAGES="$PWD/.toolchain/nuget" \
.toolchain/dotnet/dotnet build tools/seekcheck/seekcheck.csproj --no-restore -v:q
.toolchain/dotnet/dotnet tools/seekcheck/bin/Debug/net10.0/seekcheck.dll \
  "$PWD/.toolchain/tmp/parity-core/liblpcore.so" --discovery-only
.toolchain/dotnet/dotnet tools/seekcheck/bin/Debug/net10.0/seekcheck.dll \
  "$PWD/.toolchain/tmp/parity-core/liblpcore.so" --user-state-only
```

## 未验与风险

- 未在真实TV遥控器、Android真机、Windows原生桌面及真实Emby/MSG服务器联调；焦点、滚动和服务端状态传播仍需实机验收。
- 未执行全仓测试；上述手机滚动偏移基线失败仍存在。自动回归不证明播放性能或真实视频输出。
- 第一批曾用现有核心动态库做HTTP/FFI自检；第二批发现其早于当前分类源码，已用Go 1.27.2从工作树重新生成临时核心并重跑三组自检。本任务未修改Go核心或生成绑定；未自动出包、提交、推送或归档任务。
- 自检HTTP服务均在finally停止，临时数据按现有自检入口清理；本批临时日志和python兼容shim在收尾时删除。
- Linux网页已完成下述原生自检，其它原生平台验收仍未完成，不能仅凭Headless验证宣称全端一致。


## 第二批实施

- TV与手机共用AppState分类和空间代次；首页服名长按/菜单进入，点击/根返回退出；切换重建导航。私密只保留当前服的官方浏览、同服版本与播放，关闭插件接管、普通历史、聚合、下载和本机源。
- 桌面BrowseSpace/CoreClient保护请求、partial、缓存与排队交付；MetaCache私密只在内存，旧代次不可读写；Home/Library/Search/Detail排队回调复核空间。当前服右键进入，点击当前服或首页返回退出，清导航，换账号退出。
- 私密临时紫色主题，退出恢复深浅主题；TV复用共享流光，桌面单窗口共相位，后台/减少动效静止、播放页隐藏，绘制层不接收点击。
- 流影Android共享双WebView并适配遥控器；Windows双WebView2按需创建，独立临时profile/InPrivate，无脚本桥，拒绝下载/权限及非HTTP导航，切换/后台停用、离页关闭，返回先网页历史。Windows生命周期和profile效果尚需原生实测；Linux已补充WebKitGTK后端，验证见下。
- 各端CI接入同名网页地址Secrets；Android注入BuildConfig，桌面注入程序集metadata；workflow门禁检查出包步骤的两项配置，漏配运行时明确反馈，不读取真实地址。

## 第二批验证

1. Android相关11个类共102项，失败/错误/跳过均为0：TvFocusTest 29、TvPrivacyTest 3、TvLiuyingTest 1、PrivateSpaceTest 4、PhoneLiuyingTest 6、PrivateNeonTest 6、PlayerActivityTest 5、PhoneHomeRefreshTest 24、PhoneThemeTest 11、UserStateInvalidationTest 2、PhoneDiscoverUiTest 11。TV遥控器用真实DPAD语义，不能把Material Surface首次聚焦当作点击。
2. 桌面定向编译0错误，23条既有依赖/可空性警告。临时核心使用官方Go 1.27.2工具链从当前工作树生成；旧build/core库缺少本轮基线能力，未再用其验证。
3. --privacy-only：真实HTTP/FFI分类探测、普通false/私密true、命令和身份边界、迟到请求取消、旧探测取消、元数据隔离不落盘、深浅主题真实Border恢复；Headless/Skia位图验证流光实际绘制、相位变化、减少动效与后台静止、点击透传、播放及退出隐藏；真实发现页不发聚合请求、网页漏配反馈与退栈通过。
4. 使用最新临时核心重跑--discovery-only与--user-state-only，均通过，覆盖第一批的发现页及状态刷新回归。
5. Android参数检查262处通过；桌面风格7项通过；workflow 50个shell块、审查门禁和5个构建步骤凭据接线通过；git diff --check通过。环境只有python3，旧脚本调用python时用当前shell临时函数映射，未改门禁判据。
6. 已完成独立TV/桌面隐私与网页复核；收尾另以两个只读复核分别覆盖本任务全部Android和桌面改动及CI/文档，均未发现确定缺陷。复核意见必须沿调用链抽查：PluginShell.BackPressed会调用Nav.Back，故并未绕过网页历史；TV canDownload已在私密模式返回false，详情下载入口不会显示。这两条不作为缺陷修改。

## 仍未完成与限制

- Linux内嵌网页已完成；当前依赖系统WebKitGTK 4.1（最低2.40）和X11/XWayland，未验证原生Wayland或其它发行版。
- 未做Windows原生WebView2实际导航、网页媒体暂停/恢复、profile清理和HWND生命周期验证；未做TV/Android真机、真实服务器联调及播放视频输出验证。
- Headless与Robolectric不证明原生浏览器、真机焦点流畅度或播放性能；系统减少动效的运行期切换没有实测，桌面复用现有缓存式设置读取。
- 图片沿用手机与核心现有共享缓存语义，本批只隔离元数据；不承诺隐私图片不落盘，未进行缓存重构。
- 既有SeasonSelectionTest手机追加滚动偏移失败已在HEAD复现，本轮未扩大修复；未执行全仓测试或自动生成安装包。
- HTTP夹具均在finally停止，数据目录由入口finally删除；临时核心、日志和下载压缩包在本轮收尾清理，工具链保留在忽略目录。上文复现命令的临时核心需先按core/go.mod指定版本重新go build -buildmode=c-shared生成。


## 收尾门禁说明

全仓红线门禁及包含未跟踪文件的扫描均被一条既有误报阻止：`.trellis/workspace/AceCandy/journal-2.md:24`中的VulkanRT四段版本号被识别为IP。已用git show确认该文件与HEAD逐字节相同，并确认该数值不符合IPv4格式；未修改日志、扫描规则、基线或白名单。本次改动与新增文件共72个另行扫描通过；不把全仓门禁列为通过。


## Linux 内嵌网页补齐

- 使用GTK3 popup/XID交给Avalonia嵌入，两站独立ephemeral WebKit上下文，懒创建、切换保留、离页销毁；不新增NuGet或原生消息桥。权限、文件选择、下载拒绝，导航限定HTTP(S)，新窗口接管到当前页。
- GTK原生焦点纳入前台判断；隐藏/最小化停用媒体。Windows补原生Esc/Alt+Left回到既有导航链。两端共用媒体暂停/恢复脚本，失败提示按站点保留。
- 定向编译通过（0错误、23条既有警告）。Debian12、WebKitGTK 2.50.6、GTK3 3.24.38、Xvfb真实渲染下，`seekcheck --liuying-native`全部通过：像素、键盘、原生焦点获得/失去、尺寸变化、双实例与状态保留、合成媒体切换/后台暂停恢复、Esc历史返回、新窗口、权限与协议限制、连接失败及切回提示、离页释放、私密存储重开清空、最后实例销毁停止事件泵。
- 从当前工作树以Go 1.27.2生成临时核心后，`--privacy-only`再次通过；风格7项和diff检查通过。Android本轮未变更，不重复前轮102项。
- 只读独立复核未发现确定缺陷；原生焦点疑点通过新增直接状态断言验证。缩窗测试修正为允许宽度恰好等于目标值；网络故障使用未监听回环端口，不能把HTTP响应Abort假定为连接失败。
- 沙箱保持开启，测试仅访问回环夹具。媒体是合成静音音频，当前环境没有声卡，不代表真实视频、硬件音频或实服播放验收。Windows原生WebView2、Android/TV真机及真实服务仍未验证；未生成安装包。

本轮收尾：包含Linux代码与文档的77个变更/新增文件隐私扫描通过。全仓既有版本号误报仍未修改；本轮测试进程均已退出，临时核心、日志、测试配置和下载包已清理，解压运行库保留在已忽略的.toolchain目录用于后续验证。


## 最终审查与修复

用户授权完整审查，有问题修复，无阻塞后提交归档。三个只读审查分别覆盖Android/TV、桌面状态与隐私、原生网页与构建文档；发现私密收藏页仍无条件调用数据源收藏，底层拦截后产生无意义错误提示，未发现数据泄露。

- FavoritesPage在私密空间跳过数据源收藏，普通模式保持既有行为。新增真实收藏页断言：私密Emby作品加载成功，不出现数据源收藏内容或错误。该测试在修复前失败、修复后通过。
- 最小修复经另一次独立只读复核，无新增问题；桌面定向编译0错误、23条既有警告，隐私自检、风格7项、Android参数262处、工作流50个shell块及凭据接线、diff检查均通过。
- Android和Linux网页代码未再变化，沿用本轮此前102项Android回归和真实X11/WebKit全部通过的证据。未重新进行全仓测试、Windows原生/Android与TV真机/实服验收；未自动出包或推送。
- 根因是只在统一请求层拦截禁用功能，遗漏页面调用入口；私密空间的页面验收需同时验证允许内容成功加载及禁用板块不出现，包括其失败提示。
