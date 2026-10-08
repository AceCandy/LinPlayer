# 设计

## 数据流与最小边界

MPV track-list.forced → core/player.Track.Forced *bool / JSON forced,omitempty → Android matchingTrack。仅新增可选JSON字段，ABI与绑定不变，旧宿主忽略；缺失/null省略，显式false保留。
Media3 currentTracks选中Format → TrackIdentity(title,language,forced?,bitmap?) → fallback → restoreTracks现有屏障。

Media3 forced位存在取true，缺位unknown（Format默认0无法证明元数据明确非forced）。只在字幕采样。图形MIME VOBSUB/PGS/DVBSUBS取true，SSA/SubRip/VTT取false，其它unknown。MPV codec精确白名单 hdmv_pgs_subtitle/dvd_subtitle/dvb_subtitle取true，ass/subrip/webvtt取false，其它unknown。不使用image（它标记静态视频），不使用MimeTypes.isText（包含位图字幕），不按标题猜SDH。

匹配保留title/language严格约束与ISO归一。源语义已知时，目标必须已知且相等；源unknown时不添加该约束。仍须至少一个有效title/language且唯一候选，不以单一属性猜无标签轨。未知候选不能作为已知false替身；不足则保留pending等待晚到轨表或手选。无需评分框架。

修改四个生产/测试文件：transport.go/test验证真实解析与序列化；PlayerController.kt/Test验证真实Format快照、匹配与命令。独立审查后补契约文档与Android规范。

## 研究纠正

Media3 1.11.0没有MimeTypes.isBitmap；官方tag源码核验推翻前次调查说法。ROLE_FLAG_CAPTION等不等价SDH；暂缓而不制造字段语义。MPV固定提交e167836802da6d5a4301bd4c4eeb3c5c3c17ccb8 DOCS/man/input.rst:3503明确image仅单图片视频。字幕codec分类参照FFmpeg descriptor，本批仅使用已核实的六个canonical名称，未知不扩展。

## 回滚

移除新增属性与过滤即可，无持久化迁移。严格unknown策略可能放弃部分自动恢复，保留用户手选入口。
