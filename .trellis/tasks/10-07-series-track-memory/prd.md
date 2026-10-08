# 按剧轨道记忆

## 目标

继续播放器可靠性C阶段，让同剧下一集沿用用户明确选择的音轨、字幕或字幕关闭状态，减少逐集重复操作。

## 已核实背景

- core/config/prefs.go:73、122仅有全局语言/正则/字幕开关；core/player/applyprefs.go:46应用全局偏好，没有按剧手选记忆。
- 手机PlayerPage.kt:286按itemId/versionId重建控制器；TV TvPlayerPage.kt:157按target重建控制器。现有回退pending因此不能直接充当跨集记忆。
- core/emby/detail.go:60和core/player/playback.go:462可提供SeriesID及服务器/用户作用域。
- core/player/playback.go:445的画质seriesScope只包含服务器与剧ID，不包含UserID；不可直接作为轨道记忆的账号隔离键。
- PlayerController.kt:356已有同片唯一身份匹配，forced/bitmap为可空语义；MPV ID、Media3组内索引和ff_index都不能作为跨集稳定身份。

## 共同约束

只记明确手选，不把默认选轨、详情自动初选或换核恢复反写成偏好。字幕关闭是独立意图，不和unknown/缺轨混淆。跨服务器/账号/剧集不传播；电影、本地资源及无法确认SeriesID时维持现有行为。用户本次手选及明确详情预选优先，自动恢复不得覆盖。候选缺失、未知或歧义时保留默认选择，记忆不被失败/自动降级冲掉。

## 验收

同剧换集手选意图可恢复，换剧/账号/服务器不会沿用；关闭字幕能跨集保持；轨道顺序或ID变化不影响匹配，歧义或缺轨不猜选；晚到轨表在用户手选后不得覆盖；Media3与MPV各有真实生产入口回归。离页重进/新实例后不记忆；无持久化新增数据。

## 已确认生命周期

用户选择仅本次连续播放有效。记忆由Android播放页持有，离页即清，不写磁盘、不改核心配置。当前继续Android手机/TV共用链路，桌面另批。

## 范围

Android播放器选轨链路；不改MediaStationGo，不做跨设备或跨服务器同步，不按标题猜SDH，TV真机继续暂缓。生命周期已确认，可实施。
