# 实现
- 轮播：`CachedHomeBanner` 背景 `AnimatedContent` 改 slideIn/OutHorizontally（T10），方向随 `advance`；文字同向1/3幅宽；预解码下一张；简介按当前+下一张经 `emby.itemDetail` 各取一次。
- 放大：`sharedPoster` 改 sharedBounds + RemeasureToBounds（T7），来源延迟淡出托底；详情 SeriesHead/EpisodeHead 头图层、平板 WideHead 海报为目标；`aboveSharedPoster` 让标题与顶栏在放大/缩回期间画在共享层上；`PhoneRoot` 对 `zooms` 的详情改原地淡入淡出。
- 豆瓣：`LpIcons.douban`（Simple Icons 字形 + #007722 圆角底），须用 Image 绘制。
- 其余：NetImage 加载途中中性底色；`LoadingState` 默认填满居中；周条去数量；`LpTabBar` 每格58dp；删 HistoryPage/Route.History。
- 验证：全量 1031 例通过；查看放大中间帧、轮播与角标截图。未真机验证流畅度与豆瓣标志小尺寸清晰度。
