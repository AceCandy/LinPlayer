# 当前代码核查

- 评分：LinPlayer core/emby/emby.go:123/329 从 CommunityRating 映射通用 rating，详情detail.go:155/253亦如此。当前服务端HEAD bc0ce96，工作树存在既有改动；只读核查，未改服务端。MediaStationGo internal/service/emby_items_detail.go:428 与 emby_hongguo.go:459 输出 CommunityRating，model/library_media.go:64 有内部 DoubanRating，但未写入Emby响应。
- Hero：首页resume + 已加载latest + collections，按ID去重，真实Movie/Series/Episode随机取6项；沿用图像缓存与Backdrop/Primary回退，不发额外推荐查询。
- 共享入口：标准手机MediaCard/LpRow，网格、搜索、收藏、合集与推荐共用；SourceCard与插件卡片保留来源契约，标题/年份共用PosterCaption，长按CardMenu共用。
- 主题：UiPrefs只存本机呈现；MainActivity按TV形态保留默认，手机显式传色系；Lp.colors与M3同步。Monet使用已有Material3动态API，API31起有效、API28回退。未找到本机Yamby参考源码，不声称复刻其动态调色。
- 独立只读探子审查未完成，已停止；最终源码复核由主线程另行完成，不将其计作通过证据。

## 回归测试的取样约束
缓存资料在联网结果到达前不可点击，详情Tag会切换背景及文字色。透明度回归必须固定capabilities.filters=false，比较同一种视觉样式，不能拿可点击联网标签作为不可点击缓存标签的像素基线；PhoneDetailCacheTest保留0.02容差验证无重播。栏目统一增加间距后，入场动效测试需要保持目标卡片真实处于视窗，不能用完全出屏的零尺寸坐标判断动画。
