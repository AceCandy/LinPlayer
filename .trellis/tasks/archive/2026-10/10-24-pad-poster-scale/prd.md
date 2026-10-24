# 平板海报刻度与封面请求尺寸适配

用户反馈Pad上首页海报偏小、进入媒体库后3列海报过大；随后指出封面请求应按实际显示比例与尺寸取图。源码提交为`bb1f7f7e`。

## 已完成范围

- [x] `Cards.kt`新增`posterLayout`：竖屏手机（<600dp）保持104dp与官方3列；宽屏按上限150/170dp推算列数并平分余宽，首页轨道卡宽与网格单元同宽；横屏手机（高<480dp）上限仍为104dp只增列；TV不参与；插件`posterColumns`覆盖优先。
- [x] 媒体库、收藏分类、条目列表、人物作品、搜索结果、流影收藏及全部`LpRow`轨道的封面请求高度改为`posterImageHeight`（显示高度×密度，再落120/220/330/480/720/1080档）；TV保持330/220。
- [x] 方案参考docs/cankao中plezy（首页与网格同宽）、JellyCine、Plozz等项目的自适应做法。
- [x] 同步`UI_MOBILE.md` §3.3断点表与`.trellis/spec/frontend/android.md`；新增`PosterLayoutTest`，既有测试的`LpRow`调用随签名改为双参数。

## 未验证与剩余风险

- 按项目约定未编译、未运行单元测试，未在Pad/手机真机或模拟器上目视验收；建议执行`./gradlew :app:testDebugUnitTest --tests xyz.linplayer.app.PosterLayoutTest --tests xyz.linplayer.app.PhonePosterMotionTest --tests xyz.linplayer.app.ResumeCardTest --tests xyz.linplayer.app.HomeCinemaTest --tests xyz.linplayer.app.PluginThemeTest`。
- 手机封面多数由330档升至480档，流量与首次加载增加，升级后缓存键变化需重新拉取；首页离线兜底沿用各档缓存，未实测。
- 150/170dp上限待真机观感确认后可微调；首页轨道变高后续播区与Hero衔接需在Pad上目视确认。
- 未推送。
