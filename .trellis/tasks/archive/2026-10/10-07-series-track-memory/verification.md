# 验证记录

## 本批范围

Android手机与TV播放页仅在本次连续播放中保留同剧明确手选音轨、字幕与关闭状态。按服务器、账号和剧集隔离，离页释放；无磁盘、核心配置或服务端变更。前批工作树变更保留，本批不提交、不归档。

## 回归与门禁

- 相关JVM共73项，失败/错误/跳过均0：SessionTrackMemory 11、PlayerController 24、PhoneEnginePlayback 6、PhonePlayerPanel 7、TvTrackTiming 3、PlayerEngineLifecycle 2、PlaybackService 11、TvRefreshRatePlayback 9。
- 初始恢复桩回归出现4项预期功能失败；另1项Format测试构造错误已修正，不计红证据。实现后对应回归通过。
- 手机生产接线反向验证：临时断开面板当前轨表回传，真实OSD选字幕再换集测试期望命令[2,9]、实际[2]，测试失败；已恢复接线并通过最终套件。
- 手机OSD与TV遥控字幕面板实际操作后换集，新轨道ID按身份恢复且只提交一次；此为JVM页面操作回归，不等于真机视频播放。
- Media3点击Format、变更组索引、MPV/Media3跨核、off、失败手选、未知/歧义、晚到轨表、详情预选优先、账号取消与旧回执均有回归。
- Android命令参数检查254处通过；隐私门禁覆盖跟踪文件与未跟踪文件，无新增真实地址/凭据；既有基线欠账不由本批清理。
- git diff --check通过。

## 独立复核

首轮无阻断。第二轮提出取消详情请求可能跳过解绑的风险，主线程核验clearSeriesContext位于请求前同步执行，取消不会撤销解绑；补两次账号切换、取消挂起详情、旧选轨回执晚到场景，最终回归通过。其它选轨身份、优先级、稳定轨表及页面接线意见已点验。未为归因不成立的意见增加额外机制。

## 未验证与风险

未做真实视频字幕显示及音轨输出验收，TV真机继续暂缓；本批不生成TV包，目录既有TV包不代表本批。严格标题匹配、缺失语义或候选歧义可能放弃自动恢复，保留默认和手选行为。未验证桌面跨集记忆，桌面不属于本批范围。

## 手机安装包

标准 `bash scripts/pack-android.sh arm64-v8a` 完成。

- 最终包：`build/android/app-arm64-v8a-release.apk`，63,196,381 bytes。
- SHA256：`1b7461b5bc1d57f229bddb00f3a409844155be5f2b38f1660a17197fa40cca7e`。
- apksigner验证成功，v2/v3有效；v1 scheme为false，不将证书文件存在误写为v1签名有效。
- 仅arm64-v8a，共13个库，全部ELF64 AArch64；liblpcore.so及libmpv.so均包含。
- 最终交付包与本批release中间产物字节一致。
- 本批临时日志已清理，无本批调试服务。
