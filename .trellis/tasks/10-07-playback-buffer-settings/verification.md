# 验证记录

## 已实现

Android手机/TV播放器偏好新增buffer_target_bytes，0自动、自定义64～512MiB；UI每次64MiB调整、初次自定义128MiB。磁盘缓存配置独立，未增加磁盘容量UI。Media3等偏好就绪后构造DefaultLoadControl，自定义size优先、默认时间保持；MPV使用本片loadfile demuxer-max-bytes，退出本片恢复原配置。Android本地入口同样接入，桌面不应用。配置/命令复用现有字段体系，无服务端修改。

## 红绿与自动回归

- 原实现核心新配置回归失败（目标回显nil）；手机新设置回归因缺少自定义入口失败。
- 忠实反向验证：去掉ExoEngine.setLoadControl，两项实际构造/手机生产页等待偏好测试均失败，expected134217728 actual-1；去掉MPV容量拼接，参数测试失败，实际仅loadfile ... replace。全部恢复源码后重跑。
- 最终相关JVM套件87项，失败/错误/跳过0，含容量实际加载判据、实际播放器构造、手机/TV等待偏好单次起播、手机/TV保存回滚、TV容量步进及回自动，及原控制器、选轨记忆、换集/回退、面板、服务、刷新率、诊断回归。
- 新Media3测试初版空Timeline触发fixture越界，改成真实SinglePeriodTimeline后绿；不将fixture错误计为功能红证据。
- 后续仅增强手机设置容量步进/回自动断言，并补深色大字号独立渲染测试；专项2项通过（其中新增1项），因此本批相关回归共88项已通过。
- Go全核心vet、无缓存测试及核心十关门禁通过；初次门禁缺python/dotnet命令，设置本机工具环境后完整重跑通过。
- 绑定四关通过，306条命令/ABI1无新增命令。Android请求参数255处通过；响应字段209处检查通过，239处动态/无struct返回放行（53%），不是全覆盖。
- 字段检查此前7项假红：COMMANDS.md将player.status动态map误标Status而误配progress.Status；核实handler实际map字段后只修正文档返回类型Result<Value,String>，保持脚本原有规则。
- 隐私门禁覆盖跟踪与未跟踪文件，无新增真实地址/凭据；既有基线411处未改。
- git diff --check通过。

## 内核与渲染证据

锁定源码及旧版文档均有loadfile本片选项结束恢复契约。Linux实际libmpv0.35.1探针：96MiB原配置→本片128MiB→stop恢复96MiB→自动下一片96MiB；实例已销毁、WAV临时目录已删除。这不是Android so实测。

生成手机深浅主题、TV容量焦点截图并人工查看。浅色手机和TV文本、步进与焦点正常。主题切换首次截图缺失文字，等待重绘后深色截图完整；独立深色大字号截图文字及控件可读，未改公用主题组件。

## 独立审查

core配置/命令/MPV与Android状态/构造/UI由两个独立只读审查分别核验，均未发现阻断项。主线程按关键行号点验范围、数字类型、自动策略与就绪门。TV等待回归只有起播计数，未单独断言TV实例内部容量；共用构造与手机生产接线回归提供辅助证据，不能冒充TV真机。

## 未验与风险

Android真实媒体播放、实际低内存/高码率/弱网收益、Android实际libmpv缓存行为及TV真机未验。目标只约束压缩媒体缓冲，码率/时间与内存压力影响实际值，不限制解码/纹理/字幕/代理等总占用。本批不出TV包，旧TV包不代表本批。不提交归档，保留前批工作树改动。

## 手机包

标准pack-android.sh arm64-v8a完成，最终包build/android/app-arm64-v8a-release.apk，63,196,378 bytes。

SHA256：ec210eaf18284a20f999254cf3ba557d64452c88985017396ed3bd4221c39fec。

apksigner验证成功，v2/v3=true（v1 scheme=false，不把证书存在当v1有效）。仅arm64-v8a，共13库全部ELF64 AArch64；liblpcore.so/libmpv.so存在。最终包与release中间包字节一致；Dex含新增字段及两项设置文字，native核心含新增字段，核实本批功能进入包。

已清本批临时日志及javap用jar；MPV探针实例已销毁，无本批调试服务。JVM截图保留在已忽略build目录用于复核。
