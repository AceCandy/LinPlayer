# 手机流影收藏弹层设计

## 边界与数据流
收藏查询仍通过extLiuYingItems(favorite=true)获得原始row，只用item_id向既有app.imageUrl请求Primary图。PhoneLiuyingFavorites改用正常posterColumns/MediaCard；仅构建展示用未知类型Item，不虚构媒体类型、状态或评分。现有Sheet增加默认开启的可见header开关，收藏关闭，保留paneTitle无障碍命名与原有遮罩/返回语义。

## 精确选片与随机范围
PhoneLiuyingFeed点选后设置autoAdvance=false、favorites=false并清频道/衣着；保留被点选row，PhoneLiuyingPager只把这一条作为首个独立窗口，不采用相邻收藏或收藏offset。首项立即准确播放，后台后续候选使用新seed、offset=0、favorite=false请求正常随机范围；只准备不抢播。滑动才选取后续候选。沿用session选择代数、单活声音和停止屏障，无新播放模式或存储。

## 兼容与回滚
收藏交接的首轮随机seed排除已点选item_id；其它收藏与后续随机轮次不受限制。窗口额外保存按原始响应条数推进的nextOffset，默认值保持旧调用兼容；过滤空页但has_more仍为真时沿同seed继续读取，原始空页或无后续则停止。失败不接入部分结果，沿原请求重试。

PhoneLiuyingSheet其它使用者默认header不变；NativeLiuyingContent不可达手机遗留分支仅同步删掉onAll回调参数，TV行为不改。不改命令/绑定、服务端或数据。代码回滚无需数据迁移，原收藏隔离修复保留。

## 验证边界
Robolectric真实组件测试+合成图片/截图验证布局与交互，不当作真机解码、服务器封面有效性或流畅度证据；不执行assemble或pack。
