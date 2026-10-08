# 轨道字段核验

本批只读调查，两条独立路径核对Android控制器和最终payload；未做服务端改动或运行时探测。

- `core/emby/emby.go` rawMediaStream 的IsForced是可选指针；`core/emby/mediainfo.go` StreamInfo保留is_forced可选值。这是详情字段，不代表运行轨表已包含。
- `core/emby/playback.go` ExternalSub只有url/title/lang/is_default/mime_type，未携带forced/SDH；title优先DisplayTitle。
- `core/player/transport.go` Track及parseTracks输出id/kind/title/lang/default/selected/external/ff_index/codec；没有forced/SDH/image字段。缺失ff-index用-1。不能把缺失属性当false。
- Android PlayerController.snapshot此前只保存Media3 Format.label/language/ordinal；matchingTrack在唯一标题/语言不命中时用旧序号。本批删除该序号兜底。
- 手机PlayerPage及TvPlayerPage调用同一restoreTracks；TrackPrefs/ExoEngine仍按全局语言/正则处理默认选轨。现有切集路径没有保存系列手选身份，本批不引入持久化。

后续完整语义身份应先确认实际可用属性与跨内核格式等价关系；标题解析不能替代forced/SDH标记。跨集偏好与同片回退是不同规则，须独立设计作用域和优先级。

另有详情缺失Index折成0的存量风险：mediainfo.go versionFrom使用derefI，消费者可能把未知当容器ff_index=0。本批不改详情选择或数据契约，需要另批覆盖真实payload及兼容性。
