# 设计

Media3 1.11.0 DefaultMediaSourceFactory 在存在 SubtitleConfigurations 时创建一层 MergingMediaSource；MergingMediaPeriod.onPrepared 为全部轨道 Format.id 加子源索引前缀。LibassParserFactory 已在合并前按原 ID 注册数据，syncLibassTrack 却按合并后 ID 激活，导致精确查找失败。

在共享选轨同步边界按 currentMediaItem 的外挂配置判断是否合并，仅去除一层前缀。无外挂保留原 ID。原始 null ID 被合并为索引加冒号，恢复既有 ass fallback。避免无条件取最后一段或按语言/名称匹配。

源证据：androidx/media tag 1.11.0 libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/DefaultMediaSourceFactory.java 603–659、MergingMediaPeriod.java 314–328。时钟探查未发现此次整轨空白的确证，不扩大改动。
