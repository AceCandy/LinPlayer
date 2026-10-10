# 手机全局视觉统一交付

## 行为与数据

- 首页候选保持来自继续观看、已加载各库最新和合集；ID去重后随机选最多6项，不新增推荐请求。Backdrop优先，缺图沿原回退。
- Hero仅扩大背景绘制，逻辑占位、标题、播放键和指示线保持；按照媒体库入口实测高度与共用继续观看栏/图片刻度延伸到图片中点。渐变暗化位置按实际标题区域高度计算，适配亮剧照与大字号。
- 所有标准手机海报使用同一MediaCard/LpRow、EpisodeStatusBadge、DoubanRatingBadge；SourceCard复用PosterCaption/CardMenu并保留来源比例和备注语义。详情分集保留选集布局，复用观看状态角标。排行/日历保持排名/日期布局。
- 本机外观设置新增橙金/蓝/绿/紫/Monet，实时切换并保存。深浅模式独立；按钮、筛选、选中项、进度、弹窗及导航共用强调色；顶栏/设置图标24dp、48dp命中区。
- Monet使用现有Material3系统动态API（Android12起），低版本提示并回退橙金；不声称复刻Yamby源码。TV不读取手机色系偏好。

## 豆瓣与观看状态

当前Emby响应只有CommunityRating，LinPlayer映射为通用rating；服务端内部DoubanRating未进入Emby输出。本次没有改动服务端，Emby海报无明确豆瓣来源时隐藏，详情已有通用评分业务保留。插件ratings只有source明确豆瓣/Douban且max=10或缺省10时接入；TMDB不冒充豆瓣。

Series未看数字只认当前用户可靠unplayedCountKnown及非负统计；明确有效0且Played=true才显示勾。Movie与Episode完成只认各自Played，不显示数字；未知剧集状态隐藏，不逐海报请求分集。

## 修改文件

- [MainActivity.kt](../../../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/MainActivity.kt)
- [data/UiPrefs.kt](../../../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/data/UiPrefs.kt)
- [ui/theme/Theme.kt](../../../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/theme/Theme.kt)
- [ui/components/Base.kt](../../../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/components/Base.kt)
- [ui/components/Cards.kt](../../../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/components/Cards.kt)
- [ui/components/Dissolve.kt](../../../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/components/Dissolve.kt)
- [ui/pages/HomePage.kt](../../../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/HomePage.kt)
- [ui/pages/SettingsPage.kt](../../../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/SettingsPage.kt)
- [ui/pages/DataSourcePages.kt](../../../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/DataSourcePages.kt)
- [ui/plugin/PluginComponents.kt](../../../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/plugin/PluginComponents.kt)
- [ui/pages/DetailPage.kt](../../../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/DetailPage.kt)

测试修改：HomeCinemaTest、HomeHeroTest、PhoneBrowseUiTest、PhoneLibraryUiTest、PhonePosterMotionTest、PhoneHomeRefreshTest、ResumeCardTest、PhoneDetailCacheTest；新增PhoneColorSchemeTest。文档修改：docs/go-migration/UI_MOBILE.md、.trellis/spec/frontend/android.md；新增本任务目录中的PRD、设计、实施、研究、上下文清单及本报告。

## 验证与边界

13类相关回归190/190通过（色系7、Hero10、影院卡片4、主题8、续播7、浏览37、首页刷新18、Dock5、媒体库12、海报动效32、详情选项18、聚合视界4、详情缓存28）。缓存透明度测试固定不可点击标签样式，保留原0.02像素对比容差；避免联网标签与缓存标签点击能力差异改变颜色导致误判。

两项故障注入（蓝色色系不生效、Hero不延展）均被对应测试检出；恢复后全批通过。参数检查257处、字段检查207处通过（另240处既有放行）；git diff --check通过；30个改动/新增文件没有新增真实地址或凭据。独立主线程复核源码差异，探子审查没有返回完成结果，不作为通过依据。

未做真实Emby账号联调、设备播放、Android手势/三键/系统IME安全区、实际更换壁纸后的Monet更新、低端设备帧耗时测量。截图均为Robolectric夹具，不能当真实媒体首页验收。

截图：[Hero渐变边界](../../../../../build/android/ui-checks/extended-hero.png)、[设置蓝色系](../../../../../build/android/ui-checks/settings-blue.png)、[海报长按菜单](../../../../../build/android/ui-checks/poster-menu.png)。

安装包：[手机arm64 APK](../../../../../build/android/app-arm64-v8a-release.apk)，80,420,835字节（约76.7MiB）。核心与mpv均为arm64-v8a，apksigner verify通过v2/v3（不宣称v1方案通过；脚本仅检查META-INF证书存在）。SHA256：`763894d90f0f1ab09da75c7dc708ea94a191a28a40f5ab774fe351a3f64b2209`。旧TV包不属于本次交付。

构建入口：配置本机JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME后执行 `bash scripts/pack-android.sh arm64-v8a`。本次源码已通过debug测试编译与release构建，没有新增依赖或修改服务端/核心。代码已提交为 `38bf3289`，本任务已归档；未推送远端。
