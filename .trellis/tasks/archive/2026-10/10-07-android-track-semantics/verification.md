# 验证记录

## 已实现

核心player.tracks新增可选forced三态透传；Android快照保存选中Format的正向强制字幕标志与已确认格式类别；恢复要求已知属性一致，保留既有标签/语言和唯一候选约束。保护现有手选/off及提交屏障，不改服务端或跨集记忆。

## 红绿证据

- 旧Go parser下TestParseTracksForcedJSON失败：forced为空，期望true；修复后player全包无缓存测试通过。
- 扩展身份结构、保持旧matchingTrack时，新增语义回归失败：expected 2 / actual null。接入语义过滤后通过；冲突/未知/重复候选都不提交。
- 临时将snapshot退回仅title/language（finally恢复源码），真实Format快照回归失败：expected bitmap=true / actual null。恢复后最终全绿。
- 最终Android JVM共51项：控制器24、引擎生命周期2、手机回退5、手机面板7、TV选轨2、播放服务11；全部无失败/跳过。测试读取真实Media3 1.11.0对象与生产snapshot/restoreTracks，但核心命令为替身，不等价真视频。
- 独立只读复核无阻断；主线程复核明确unknown过滤与optional JSON旧宿主兼容。
- core完整十关门禁通过：vet/无缓存全test、c-shared、FFI、C#宿主契约、差分与插件门禁。首次因未配置libmpv搜索路径失败，使用本机既有.toolchain/mpv-test运行库（LD_LIBRARY_PATH）后完整重跑通过，未跳过判据。
- Android参数253处通过；隐私1500个跟踪文件、54个未跟踪文件无新增敏感内容；git diff --check通过。

## 未验证与风险

未测真实Media3→MPV视频回退、外字幕晚到、实际字幕呈现与设备解码；TV真机暂缓。未知语义可能保留MPV当前选择而不恢复，用户可手选；SDH严格跨核等价仍未确认，本批暂缓。只白名单分类六种codec/对应MIME，其它unknown，不宣称支持全格式语义。桌面未改源码，旧宿主兼容以核心契约门禁为证据，未做本批桌面真视频或新桌面安装包。

## 手机交付

标准pack-android.sh arm64-v8a通过；最终build/android/app-arm64-v8a-release.apk为63,179,988字节，SHA256 337deda6ba9abce82f330205bcf8e4cd75fae49cf56c8e40635bf908ce37e98e。与本次release中间APK字节一致；apksigner实际验证v2/v3有效（v1 scheme=false，不将META-INF证书检查声称为v1验证）；仅arm64-v8a，全部13个原生库为AArch64 ELF，含真实liblpcore.so/libmpv.so。TV目录包为旧包，不作为本批交付。

临时红/绿/反向/核心/隐私/打包日志全部删除；无本批调试服务。未提交、推送或归档。
