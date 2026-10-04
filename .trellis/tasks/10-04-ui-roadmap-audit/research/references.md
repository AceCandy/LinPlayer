# docs/cankao UI 参考调研

## 覆盖项目

`docs/cankao` 下本地参考项目目录共 7 个：CineIsle、Ghosten-Player、JellyCine、Moonfin-Core、plezy、Plozz、qEmby。以下按可核验的源码布局列出 8 条具体参考；这里只描述源码结构，没有运行参考项目，也没有据此推断截图外观。

## 可借鉴布局（按对 LinPlayer 的适配优先级）

1. **qEmby：资料库浏览页的固定工具栏 + 内容网格（桌面）** — `docs/cankao/qEmby/src/qEmbyApp/views/media/libraryview.cpp:37-70`。纵向主布局先放 65px 固定高度的横向工具栏，再由 `MediaGridWidget` 占用主体；工具栏内有顶边距、间距和 `setupTopBar`。可参考桌面媒体库把筛选/排序/视图操作稳定放在内容网格上方。适用端：桌面。限制：项目本身也是 Emby 客户端，具体按钮和媒体类型仍须按 LinPlayer 当前 Emby 与本地文件边界筛选；不要把服务端管理能力一并引入。

2. **qEmby：搜索结果按类别切换，结果区复用媒体网格（桌面）** — `docs/cankao/qEmby/src/qEmbyApp/views/search/searchview.cpp:79-115,117-191`。65px 顶栏含 All、Movies、Shows、Collections、People 互斥标签、视图切换和结果计数，下面是 `MediaGridWidget`；查询失败时状态文字为 “Error Loading Results”（同文件 `:238`）。可借鉴“分类筛选/计数与结果区同屏”的信息组织，以及清楚展示检索错误。适用端：桌面。限制：只保留 LinPlayer 实际支持的媒体类别与搜索来源；不因参考增加人物、合集等未确认能力。

3. **qEmby：详情页把核心信息与动作置于首屏，再纵向排列关联内容（桌面）** — `docs/cankao/qEmby/src/qEmbyApp/views/media/detailview.cpp:511-697,914-936`。主内容在可滚动区中，海报与右侧文字并排；右侧从标题、元数据、标签、动作、标语到简介排列，之后才接剧集、季、演职员、合集、相似内容等分区。可参考桌面宽屏详情页的主次顺序与长内容分区。适用端：桌面。限制：演员、推荐、合集等要以 LinPlayer 已有 Emby 字段/能力为准；不得混入外部片库/在线发现服务，也不能把网络元数据变成必需信息。

4. **qEmby：设置采用侧栏分类 + 右侧页面栈（桌面）** — `docs/cankao/qEmby/src/qEmbyApp/views/settings/settingsview.cpp:32-109`。横向布局中左侧固定 260px，显示设置标题及 General、Appearance、Player、Library、About 分类；右侧为 `SlidingStackedWidget` 页面栈。可借鉴桌面设置的信息架构，减少长页面混杂。适用端：桌面。限制：类别与选项只按 LinPlayer 已有偏好设置映射，不照搬该项目的配置项。

5. **CineIsle：本机视频浏览支持列表/网格与专用空状态（Android 手机）** — `docs/cankao/CineIsle/app/src/main/java/app/marlboroadvance/mpvex/ui/browser/videolist/VideoListScreen.kt:633-644,733-762`。空文件夹单独呈现图标、标题“该文件夹中没有视频”和说明“你添加到此文件夹的视频会显示在这里”；非空时用 `MediaLayoutMode.LIST/GRID` 决定单列或网格，并对网格加滚动条与间距。可借鉴本地媒体空态和可切换密度的浏览布局。适用端：手机本机文件浏览。限制：LinPlayer 的手机播放时长仅用于续播，不能把本项目任何“历史/播放统计”样式解释为需要展示时长、时长排行或观看历史；文件权限和目录行为按 LinPlayer 现有实现。

6. **JellyCine TV：详情首屏使用背景图上的摘要块，以下内容进入可滚动内容面板（TV）** — `docs/cankao/JellyCine/tv/src/main/java/com/jellycine/app/ui/screens/detail/DetailContent.kt:556-667,787-800`。首屏左下摘要区按类型显示标志/标题、年份、片长、分级、最多三行简介；当摘要内容面板展开时，后续 `LazyColumn` 承载演职员等内容。可参考 TV 大屏详情首屏内容密度、长简介截断与分区滚动。适用端：TV。限制：装饰与层叠结构需服从 LinPlayer TV 设计正本；内容选择限于 LinPlayer Emby 数据，片长只按该端既定呈现规则核实。

7. **JellyCine TV：播放控件将焦点落在主操作，并显式处理遥控按键（TV）** — `docs/cankao/JellyCine/tv/src/main/java/com/jellycine/app/ui/screens/player/ControlsOverlay.kt:57-89,114-121,329-352,394-421`。覆盖层参数把播放、跳转、字幕/音轨、前后集等操作分开；进入后请求播放/暂停焦点，操作按钮可聚焦且在 `onPreviewKeyEvent` 处理按键。可借鉴遥控器下明确焦点起点和可见焦点移动规则。适用端：TV。限制：D-pad/返回键/确认键行为必须以 LinPlayer 当前 TV 遥控契约为准；手机触屏手势和桌面鼠标不应复刻此焦点模型。

8. **plezy：手机播放面板可由底部手势切换到章节/队列内容条（手机）** — `docs/cankao/plezy/lib/widgets/video_controls/mobile_video_controls.dart:22-31,224-290`。源码说明顶栏固定；常态下中部播放控制、底部时间轴分层，章节或队列可用时支持上滑打开底部内容条，同时淡出普通控制。可参考播放面板将低频内容放入手势展开区域的层级方式。适用端：手机播放。限制：队列与章节只在 LinPlayer 本身有对应数据时采用；“时长角标仅续播”约束针对媒体卡片，不限制播放器必要的当前时间/总时长；不借此新增观看统计；TV 应采用遥控器可发现的焦点操作，而不能要求上滑手势。

## 其他项目覆盖情况

Ghosten-Player、Moonfin-Core、Plozz 的 UI 源码也存在于参考目录，但本轮未选入上述 8 条：在限定条数下优先选取了与 LinPlayer 端型及指定页面更直接可核验的实现。该结论仅说明未纳入本清单，不代表这些项目没有可用布局。
