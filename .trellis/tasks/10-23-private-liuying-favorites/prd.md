# 隔离隐私空间与流影收藏展示

## 目标
隐私空间普通媒体收藏与流影收藏保持独立展示，与已有媒体库边界一致；保留所有既有收藏状态。

## 已确认事实
- 用户明确要求流影收藏独立，不应混入隐私空间普通收藏。
- 普通收藏分页 `core/emby/favorites_page.go:18` 与全量入口 `core/emby/lists.go:365` 均使用跨库 `IsFavorite` 查询，没有排除流影。
- 已有 `core/emby/liuying.go:94` 的 `withoutLiuYing` 识别 `LibraryType=liuying` 和 `ly-work-` ID，可复用，无需独立存储或迁移。
- 流影已有专用收藏查询 `emby.extLiuYingItems` 的 `favorite=true`；Android 入口见 `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/PhoneLiuyingFavorites.kt:20`。
- Android 普通收藏 `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/ListPages.kt:142` 与桌面 `apps/windows/LinPlayer.Desktop/Views/MorePages.cs:570` 均调用同一核心命令。
- 分页 `next_index/has_more` 依服务端原始结果推进，不能用过滤后的条数替代；桌面保留加载更多入口，Android须验证过滤空页仍能继续加载。

## 范围与关键决策
- 在核心普通收藏分页及全量返回复用流影识别规则，隔离展示，不改变服务端收藏写入与流影专用查询。
- 保留服务端原始分页游标语义，不把原始 total 冒充过滤后的精确数量。
- 检查两端过滤空页的续页行为及展示缓存，必要时仅修复阻断后续普通收藏的接线。
- 不改服务端、不增加新收藏存储、不删除/迁移数据、不新增入口、不打包或提交。

## 验收标准
- 混合结果中的流影身份条目不出现在普通收藏，普通条目及其收藏状态保留。
- `LibraryType=liuying` 与 `ly-work-` 两种身份均有回归覆盖。
- 分页与全量入口一致；纯流影页不被误判为结束，后续普通收藏可达。
- 流影专用收藏仍保留原有条目与收藏/取消收藏能力。
- 回归测试须先在修复前失败，再在修复后通过；独立复核实际 diff。

## 验证边界与风险
针对性核心测试和两端调用检查，不自动编译安装包。真实服务器、设备与旧展示缓存表现未经联调不能声明通过。仅识别现有协议明确的流影身份，不按名称猜测来源。

## 实施与验证记录
- 已在普通收藏分页和全量返回复用现有流影识别；收藏写入、流影专用查询及客户端页面不变。
- 普通收藏展示缓存键增加独立版本后缀，旧混合快照不再读取；其余命令缓存不变。
- 修复前新回归在混合页、纯流影页、全量与旧缓存断言上失败；修复后 `go test ./emby ./displaycache ./bus -count=1` 全通过，`go vet ./emby ./displaycache ./bus` 通过。
- 只读独立审查无缺陷发现；主线程核查 Android ListPages.kt:237/258 与桌面 MorePages.cs:625 的 has_more 续页入口，过滤空页仍保留加载更多。
- 未运行完整 check-core（含出库）、安装包编译、真实服务器联调或设备渲染；未提交，未归档。
- 根因是首页流影排除规则未覆盖跨库收藏读取；防复发契约和回归入口已补入 shared/product-and-api.md。

## 用户追加并批准：手机流影收藏弹层
- 保留底部弹层，复用正常列表的海报列数规则与封面/标题样式，不固定两列。
- 保留普通流影舞蹈/直播分类；移除可见标题、完成、说明和返回全部入口，不增加卡片操作按钮。外点与系统返回关闭弹层。
- 点选准确播放该条收藏，立即切为循环当前视频；其余收藏不进入播放队列。
- 手动滑动后接当前feed/tab的正常随机视频，清频道/衣着及仅收藏限定，循环模式继续保持。
- 仅影响手机；不改变TV/桌面交互，不改服务端、收藏状态或图片接口，不自动出包。
- 验收包括真实Popup的网格/列数/返回、缩略图URL、点选与循环、滑动正常随机、分页失败重试及空态/迟到响应。

## 手机追加需求实施记录
- 已复用posterColumns()/MediaCard与既有Primary图片通道；收藏弹层仅保留分类、封面标题、必要加载/失败/分页状态，可见header关闭，无障碍paneTitle保留。
- PhoneLiuyingPager只接入被点中的一条收藏作为独立窗口；点选同时关闭autoAdvance及favorite限定，后续新seed/offset=0取普通候选，不延续收藏页偏移。
- 新回归先在原实现下因“旧标题仍存在”“未进入循环模式”失败；最终PhoneLiuyingTest 52、PhoneLiuyingChromeTest 3、TvLiuyingTest 2共57项全通过。新增缺图仍可准确播放用例；正常列数3与主题覆盖4均覆盖。
- 参数检查269处通过；15个变更文件秘密扫描无新增可疑值；git diff --check通过。测试只编译JVM所需代码，未生成安装包。
- 人工查看真实Popup合成渲染图：build/liuying-mobile/favorite-grid-dark-synthetic.png与favorite-grid-light-synthetic.png（位于Android app已忽略build目录）；海报色块仅是合成图片，不代表真实服务器响应。浅色主题内标题仍为白色。
- 独立只读审查提出Native旧回调仍设favorite=true；主线程按NativeLiuyingContent.kt:49-51确认手机在函数开头委派PhoneLiuyingFeed并return，该旧分支不可达，拒绝误报，不改TV行为。其余未发现确定性缺陷。
- 未做真机手势/解码、真实服务器封面联调、性能验收；未提交或归档。
