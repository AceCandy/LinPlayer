# 验证记录

## 已通过

- Android Kotlin编译；51项相关Robolectric/Compose回归，8个suite：OSD6、两内核播放14、API24/36字体和模糊4、主题8、详情12、缓存设置1、运行期关闭动画1、插件导航5。XML失败/错误均0。
- UI忠实故障注入：禁用3dp细条、恢复黑底、模糊返回原图均被捕获，已恢复并最终绿；截图主线程查看播放器横屏/外观/缓存设置。
- Go真实HTTP缓存跨代理重建、授权更新、弱/缺失validator不复用、等长版本替换、错误Range/200/版本先于feed拒绝、损坏/半写/索引恢复、跨子进程重启、TTL/LRU、全局/单片容量、并发弱流预算、活动清理及Stop取消。跨子进程连续20次无重复下载。
- prefetch/player/preload race检查；确定性worker认领测试与并发/跨进程测试追加race检查通过。prefs部分保存、越界/小数拒绝、磁盘预算执行失败不保存、配置Save失败回滚。
- cache故障注入：条件Range/版本校验、块哈希、TTL、预算删除接线被禁用时回归均红，已恢复。
- scripts/check-core.sh完整门禁（最终代码重新执行）通过；scripts/check-bindings.sh四方对账与双端绑定编译通过；生成产物最新。
- Android参数门禁256处通过，工作流门禁及git diff --check通过；红线门禁无新增真实地址/凭据。字体资源合计19,957,136字节（<20MiB），官方固定提交与SHA/两份OFL许可证记录匹配。
- UI与缓存独立只读复核完成，主线程按出处点验，结论见research/{ui-review,cache-review}.md。

## 未验证与风险

- 没有手机/TV/Windows真机：实际两内核切换、杀进程复用、PiP/手势安全区/TalkBack/RTL、API24真实字形和模糊性能、弱网和首帧收益未实测。
- 未改服务端、未做真实账号联调；持久复用仅适用支持可信强ETag与条件Range的直传资源，其它路径会话缓存/直连，不承诺秒开。
- 没有构建本批TV或其它ABI包；字体是共用资源，其它ABI仍60MiB门禁，后续出TV包须核查字体体积影响。旧TV安装包不是本批产物。
- 系统可回收应用缓存；关闭/清理后当前句柄禁写，新播放才重建。原私有导入字体不扩展删除，旧偏好已回系统默认。
- 未提交、推送或归档；前批工作树改动保留。

## 最终安装包

- 最终文件：build/android/app-arm64-v8a-release.apk，80,338,915字节 / 76.617MiB，严格按字节≤80MiB（pack门禁已取消整数向下取整宽限，预算+1字节断言拒绝）。
- SHA256：67309db755a614ca9ea491d4901aa3bffa6e574c3249471e328b7031580fe05c
- apksigner强制min-sdk-version23检验v1，并验证v2/v3均true；默认minSdk24仅使用v2/v3，不能将默认输出的v1 false当缺签。
- 13个SO均ELF64/AArch64且仅arm64-v8a；APK liblpcore与本次native编译文件一致。classes.dex含media_cache_bytes新接线。
- 包内两字体原始字节SHA及OFL许可与仓库资源一致；最终包和assembleRelease中间包SHA一致。详见research/apk-verification.json。

## 验收反馈修正验证
- 轻模糊回归在旧实现失败，85%原图+15%模糊、1280px与alpha加权修正后API24/36通过；原图不变/边缘轻软化/透明红边不变黑。
- 37项Android回归通过：Appearance4、Motion2、DetailOptions12、EnginePlayback14、PluginNav5。按钮测试采用Native真实渲染，按下收缩与释放回弹、运行时关闭动画有效。详情深浅截图已查看；渲染使用测试素材，未证明真实网络图片或手机帧率。
- prefetch测试、prefetch竞态、player测试/vet、完整check-core、Android参数与隐私门禁通过；git diff --check通过。独立审查后修正透明RGB与viewport反复变动问题，并再次只读复核。
- 本机恢复基线：BenchmarkPersistentRestoreFullWindow，5次，153124480 ns/op，821.74 MB/s（当前槽映射存30块、120MiB）。这是Linux/AMD6800H本机热文件系统数据，不是手机数据；不据此声称秒开，也未削弱恢复/读取SHA校验。
- 新增core本地日志阶段startup_resolve/proxy/history_wait/primary_progress/engine_setup，cache_probe/restore/first_supply；Android现有StartupTiming保留request/load/真实Media3 first_frame。日志仅白名单/数字/布尔。首次供给每代理一次，详情预热也可触发，不能视作首帧，两个内核engine_setup语义不同。
- 图片每次composition生命周期只进入一次，Lazy销毁再创建仍会轻入场；不保存无界跨页面URL集合。
- APK重新构建，80,338,920字节（76.617MiB），SHA256见research/feedback-apk-verification.json；apksigner v1/v2/v3通过，13个SO为ELF64 AArch64并比对实际strip输出，DEX与核心确含本轮模糊键和日志。首次比对未strip源码库失败，改为比对打包strip结果后通过。
- 未验证：用户资源的缓存持久资格/实际命中与手机起播瓶颈、真机快速滚动/导航观感/性能、TV设备。缓存实际慢因待新包复现日志，不宣称此项已解决；未提交/推送/归档。

## 第二轮动效反馈核验
- 旧版本滚动容器短Tap回归真实失败：初态红按钮正常，点击立即调用一次，但32ms后边缘仍红（没有收缩）。改为Press/Release事件消费后同一测试通过；回弹归位和连续两次点击亦验证通过。
- 最终52项Android回归（Browse25、Detail12、OSD6、Motion4、PluginNav5）通过；运动测试包括长按缩放/回弹、快速Tap/点击不延迟/连续Tap、实际小幅spring越过目标及运行期零动画。
- 按钮/海报/底栏反馈复用Base.pressFeedback；Tab保留selectable/Role.Tab/selected，选中弹簧遵循倍率。页面位移1/3宽、Tab初态0.94，图片40dp/0.90。spring阻尼0.65/刚度320，Float缩放阈值0.001。
- 独立只读审查未发现点击/长按/取消接线错误。审查关于WallpaperLayer和backgroundBlur的scope提示是拿HEAD整体diff混入前批改动，主线程核实均属已授权前批功能，没有本轮新增或回退。
- 未验证手机真机帧率/用户设备安装版本与动画倍率；不能以JVM通过保证用户主观观感。服务端/缓存/两内核不改。
- 第二轮新APK：80,338,920字节，SHA256 ce5e646b699822f94139350ed03abb9d413630dc894ac6224e7e3c09e7e0530e；v1/v2/v3验签通过。13个SO为arm64 ELF64并与strip输出一致；R8 mapping中pressFeedback协程方法已水平合并到目标类，目标descriptor确在DEX（不能只按原匿名类名核对）。详细 evidence 在research/motion-apk-verification.json。
- 未修改Go，未重复运行前批已通过的完整核心门禁；本轮隐私与Android参数门禁、diff检查均通过。打包临时日志已删除。

## 第五轮海报就绪与弹簧验收

- 真实MediaCard的冷图gate等待1000ms原实现断言失败（加载期间卡片已完成入场）；修复后等图、80ms中间几何、约272ms轻过冲及终态通过。NetImage独立交叉淡入与内存直显未退化，热缓存卡仍执行一次整卡入场。
- 最新Gradle相关回归92/92，0失败/错误：PhonePosterMotionTest16、PhoneMotionTest4、PhoneBrowseUiTest26、PhoneDetailOptionsTest12、PluginNavTest5、PhoneHomeRefreshTest17、PhoneLibraryUiTest6、PhonePlayerOsdTest6。新增真实Lazy fling、两轴速度/静止判断、动画取消/不重播、首批窗口/数量上限、裁剪可见、失败/无图和换URL/零倍率。
- default只读审查后补齐裁剪与fling，最后小改（observer成为不消费的NestedScrollConnection并由MainShell原连接调用）由主线程逐行复核；现有共享海报中间几何、重复item来源及实际详情元数据挂起场景均通过。
- 未验证手机帧率和用户实际设备观感、TV、缓存重开耗时；不把渲染测试当真机验收。未提交/推送/归档。

- 第五轮最终手机APK：80,371,690 bytes（76.648MiB），SHA256 `6e3e7b65387e43df07df2c9f9494494e641fbfeca82bd4bc4edf5c1d693db968`；v1/v2/v3验签通过，唯一arm64-v8a、13个ELF64/AArch64播放库、≤80MiB及最终/assemble包一致性通过。最终DEX核验首批错峰、快滚观察和弹簧入场协程owner，证据见research/poster-spring-apk-verification.json。没有提供旧TV包作为本轮产物。

## 第六轮：首页整排滚动揭示

- 原实现真实LpRow底部局部可见、等待1000ms后海报无右偏移，新断言红；整排接线后绿。102项回归全部通过：PhonePosterMotionTest19、PhoneHomeRefreshTest17、ResumeCardTest7、PhoneBrowseUiTest26、PhoneDetailOptionsTest12、PhoneLibraryUiTest6、PhoneMotionTest4、PluginNavTest5、PhonePlayerOsdTest6。
- 新增3个实际渲染场景：首页两卡同步/标题水平稳定/首个测量帧/前回滑/组合次数、点击长按横滑；同排一张冷图门控与已加载图尺寸同步，释放请求后不追加单卡入场，图片淡入保持；快速滚动强度减少与恢复有中间帧、倍率0正常几何。已有共享海报中间帧和详情请求挂起回归保留。
- default只读独立复核后主线程核查所有首页key与骨架/插件items/默认参数传播。无确认阻断缺陷；首次layoutInfo缺项是未证实候选，测试验证首个可测量帧已采用揭示。
- 未覆盖：真实手机帧率和主观观感、TV设备；媒体库入口条和custom接管不改变。仅记录，不提交归档。

- 第六轮最终手机包：80,371,688 bytes（76.648MiB），SHA256 `49b9e18cafda17966de4c1b8fbc5836c98dc66b6900012750791c9263ad9f54d`；v1/v2/v3强制min-sdk21验证通过，arm64唯一ABI、13个ELF64/AArch64 SO、≤80MiB与最终/assemble包一致性通过。最终DEX含homeRowReveal及homeRowStrength，证据research/home-row-apk-verification.json；旧TV包不作为本轮产物。

## 第八轮：首页整排时间弹簧

- 原实现两条真实LpRow回归红：停住列表仍无时间驱动位移、快滚压缩初态。修复后99项相关回归通过（PosterMotion22/HomeRefresh17/ResumeCard7/Browse26/DetailOptions12/Library6/Motion4/PluginNav5）。
- 手动时钟验证阈值前保持、停滑时水平中间帧、约450ms主要位移收住、两卡同步、标题水平稳定、动画帧不重组栏目；真实图片冷加载与骨架→真卡不重启、点击/长按/横滑保留。
- 真实Lazy fling观察fast且不消费滚动，待归位排掠过后Lazy返回直接正常；单独覆盖同组合未销毁退回阈值以下后再进入不补播。导航返回、换账号与运行期零倍率/重新开启正常。
- default只读独立复核，主线程核验局部等待变量作用域并增加边界测试。规范/正本同步；参数256处、全工作树隐私及diff门禁通过。
- 最终APK核验详见research/home-timed-motion-apk-verification.json：80,371,687 bytes，SHA256 `40e0a1cf2216021b1adda7d21f72cdb9c0161ed4d17462ac98d257d025c5c08c`，v1/v2/v3/min-sdk21、唯一arm64/13 SO、80MiB门禁及assemble一致通过，新时间协程实际在最终DEX中，旧homeRowStrength已移除。
- 未验证：手机真机帧率/主观动作强度与参照一致性、TV设备。可用ADB设备0，本次服务已关闭；没有把单测、构建或录屏推断当性能实测。仅记录，不提交/推送/归档。
