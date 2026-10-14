# 设计

边界：Android 现有 libass JNI 字体初始化、手机轨道标签和 OSD；不替换播放架构、不引入第二份 libass。

证据：Yamby 使用独立 ASS overlay 和 Matroska 事件时间，非只靠 Media3 文本样式。当前绘制循环会更新 frame，尚无依据认定 Compose 不刷新。宿主 libass 0.17.1、NONE provider、缺失样式字体 + sans-serif 默认族实际返回空帧，存在的默认族返回图像。JNI 另存在附件先到创建 g_lib 时跳过字体目录的顺序问题。

方案：JNI 为实际字体文件提供兜底，字体目录设置不依赖 g_lib 是否首次创建，附件更新复用相同兜底。保留字幕原始样式和已有优先字体。列表按 codec/MIME 标注 ASS/SRT/PGS 等；不从标题猜格式。顶栏按钮取消顶部额外留白，网速放在行后；状态居中留出空间。

回滚：按本轮提交回滚；不改变持久化或服务端接口。
