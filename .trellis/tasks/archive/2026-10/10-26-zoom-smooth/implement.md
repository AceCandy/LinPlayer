# 实现
- 预览：SeriesHead 的 previewUrl 改回来源原地址；NetImage 以原地址作 memoryCacheKey，未命中读 `/img-cache`。
- 缩回：`sharedPoster` 返回方向 T7 standard，两端按当前页判断方向。
- 侧滑返回：导航2.10默认 predictivePopExit 为 scaleOut(0.7)；`PhoneRoot` 对 `zooms` 的详情配置 predictivePopEnter/Exit 与返回键一致，其余页面沿用默认。
- 预热：`LpRow.warmDetail` 卡片停留300ms后调用 `warmDetailBackdrop`，HEAD 预热 Backdrop 720，最多两路、同址一次、分集与无背景图跳过。
- 验证：全量 1034 例通过；录屏逐帧定位原因。未真机验证手势返回与首次进入效果。
