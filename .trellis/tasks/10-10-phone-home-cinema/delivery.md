# 手机首页视觉升级交付

已基于现有Go核心与Kotlin/Compose手机界面完成修改，保留既有工作树内容，未提交或推送。手机安装包包含当前工作树，TV包未重建。

## 本次文件清单

| 文件 | 本次改动 |
|---|---|
| [HomePage.kt](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/HomePage.kt) | 全宽响应式Hero、Logo/背景回退、轻量信息/播放、横滑/触摸/生命周期暂停、顶部栏滚动背景；首页卡片展示模式 |
| [Cards.kt](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/components/Cards.kt) | 首页海报单行片名/年份、独立观看状态与豆瓣胶囊、续播橙金进度/SxEy、栏目间距/箭头；其它页面默认样式保留 |
| [Scaffold.kt](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/components/Scaffold.kt) | 78%宽四图标Dock、40dp暖色底座、24dp图标、半透明面板/细边框、顶栏背景槽 |
| [PhoneRoot.kt](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/PhoneRoot.kt) | 搜索选中状态、键盘展开隐藏Dock、200ms隐藏动画；原滚动连接/返回栈保留 |
| [Theme.kt](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/theme/Theme.kt) | 深色背景/文字/橙金强调Token、海报角标墨色、Dock系统导航区上12dp |
| [Models.kt](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/data/Models.kt) | 明确豆瓣字段及有效范围、未看统计有效性、背景/Logo存在性适配 |
| [BrowseCache.kt](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/data/BrowseCache.kt) | 对应展示字段加入原有有界缓存白名单，缓存不参与起播进度决策 |
| [emby.go](../../../core/emby/emby.go) | 非破坏可选展示字段：unplayed_count_known、has_backdrop、has_logo，原字段语义保留 |
| [home_status_test.go](../../../core/emby/home_status_test.go) | 缺失/零/正数/负数统计与真实图片存在性解析回归 |
| [HomeCinemaTest.kt](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/HomeCinemaTest.kt) | 评分来源/更新/隐藏、数字/勾/未知、角标分离、续播时间与点击/菜单 |
| [HomeHeroTest.kt](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/HomeHeroTest.kt) | 单/多轮播、触摸/横滑/后台/离屏/减少动画、长标题/大字、缺背景回退、跨季待播分集 |
| [PhoneHomeRefreshTest.kt](../../../apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneHomeRefreshTest.kt) | 更新简短SxEy预期，定位续播区域避免Hero同名内容误命中 |
| [UI_MOBILE.md](../../../docs/go-migration/UI_MOBILE.md) | 更新首页/Dock视觉与数据契约 |
| [android.md](../../spec/frontend/android.md) | 更新手机首页与验证约束 |
| [quality-guidelines.md](../../spec/backend/quality-guidelines.md) | 保留首页数据可靠性规则 |

另新增本任务prd.md、design.md、implement.md、delivery.md及任务元数据。

## 数据与播放契约

- Hero候选来自当前账号已有首页resume/latest/collections作品，最多6条，不新增随机推荐查询。普通图片通道复用核心与Coil缓存；真实Backdrop/Logo优先，失败回退Primary/文字，不填假片名/年份/图片。
- 继续观看保留现有详情入口、原媒体ID、进度和记录更新逻辑。Hero新播放按钮复用Player路由，不从展示快照提交续播秒数；整剧仅在点击时使用现有seriesSeasons/seasonEpisodes分页解析首个未完整看完分集，切账号/离页取消，空分集明确反馈。
- 剧集角标复用服务端当前用户UserData.UnplayedItemCount。核查现行基准服务端的容器统计及相关测试，统计按当前用户可见媒体分集与完整观看状态汇总，等价于实际可播放集数减去当前用户完整看完集数；客户端不为每张卡串行逐集请求。核心仅Series明确含非负统计时标记有效。24/8/1显示数字；明确有效零且Played=true显示勾，未知/负数/未完成的空剧库隐藏。电影只允许已看勾，不显示数字。
- 现有rating来自Emby CommunityRating，无法确认豆瓣来源；Provider ID也不是评分。首页彻底停用该评分。DoubanRatingBadge只接受独立douban_rating、0–10分、一位小数、右下胶囊；当前核心尚无可靠豆瓣分数接入，因此真实首页隐藏评分。没有引入爬虫、随机值或设计图示例。

## 已验证

- 最终Android定向回归54/54通过：HomeCinemaTest 4、HomeHeroTest 9、FloatingTabTest 5、PhoneHomeRefreshTest 18、ResumeCardTest 7、BrowseCacheTest 3、PhoneThemeTest 8。
- 包含360dp短屏、393dp标准组件、430dp宽屏/200%字号播放边界；Hero手动/自动切换、长按触摸暂停、后台/离屏暂停、系统减少动画、单项指示器隐藏和背景缺失回退。
- 核心check-core全部通过（vet、无缓存测试、出库、FFI、宿主契约、20条差分对账及其余门禁）。使用项目工具链与本机libmpv运行库，不修改门禁判据。
- 故障注入：把缺失统计错误标为可靠，HomeUnplayedCountPresence正确失败；恢复后解析回归通过。
- Android命令参数检查257处通过，响应字段检查通过；git diff --check与隐私检查通过。
- 独立复核本次源码差异：首页展示模式未扩散到其它卡片，继续观看入口保持、身份取消、缺失数据与评分来源检查。独立探子未按时返回，未将其作为通过证据。
- arm64手机release完整出包成功；apksigner确认v2/v3签名有效；liblpcore.so/libmpv.so均为arm64 ELF；约76MiB，符合80MiB预算。

## 安装包与构建

安装包：[app-arm64-v8a-release.apk](../../../build/android/app-arm64-v8a-release.apk)

SHA256：`75ded0d388a25201967cab66276ed514beae75941a0c44c3a2acc1447261d6ae`

项目现有构建入口：`bash scripts/pack-android.sh arm64-v8a`。需要现有JDK/SDK/NDK与本地签名配置；本次使用仓库.toolchain中的工具，未新增工程依赖。

## 未验证与剩余风险

- 当前没有已连接且登录真实Emby账号的设备，未提供真实首页截图；组件截图使用测试夹具，仅用于渲染检查，不能当实际媒体验收。
- 未做真机播放、服务端实际联调、手势/三键导航、IME系统动画或低端设备滚动性能测量。
- 本次Hero允许正常图片通道加载未命中缓存的真实图像，首次可能增加最多6张背景/海报及存在的Logo请求；缓存复用已保留，实际首屏流量和帧率仍需设备测量。
- 豆瓣分数接入仍缺失，可靠统计字段缺失的服务端会隐藏剧集角标，不伪装为完成。
- 本次临时日志/基线已清理，启动的ADB/Gradle后台进程已关闭；APK与测试渲染产物位于已有忽略目录。
