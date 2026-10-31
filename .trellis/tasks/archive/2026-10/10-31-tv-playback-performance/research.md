# 参考实现与适用性

## Media3 1.11.0

项目依赖 `apps/android/app/build.gradle.kts` 指定 1.11.0。该版本 [VideoFrameReleaseHelper](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/VideoFrameReleaseHelper.java) 在实际倍速变化后，以媒体帧率乘播放倍率更新 Surface 帧率提示。OFF 策略关闭该提示，但没有关闭视频 VSYNC 对齐。

当前 TV `MatchTvRefreshRate` 为避免 Surface 与窗口争夺模式而临时关闭该策略，自行匹配窗口；却漏掉实际倍率。修复继续使用既有窗口所有权，Media3 读取 `playbackParameters.speed`，MPV 使用已有 `player.opts` 中的 `speed`，不新增命令或轮询。`ui.speed` 在保存/长按期间并非内核实际值，不能用于匹配。

`UI_TV.md` 已要求临时长按不反复切屏。长按开始取消在途匹配，保留当前模式，松手且在途倍率操作完成后从内核重新读取正常倍率；不因修复普通倍率而改变这项设计。慢恢复期间也暂停菜单倍率回读，避免连续长按把临时值当成下一次恢复目标。

## mpv-android

参考提交 `255901dd586fa750000fa0075f69d6ffdd26cd9c` 的 [MPVView.kt](https://github.com/mpv-android/mpv-android/blob/255901dd586fa750000fa0075f69d6ffdd26cd9c/app/src/main/java/is/xyz/mpv/MPVView.kt)：默认 `profile=fast`、`vo=gpu`、`hwdec=mediacodec,mediacodec-copy`，gpu-next 和插帧默认关闭。这是偏向低开销的默认值，不能证明所有电视均流畅。

咱们 Android 当前统一 `gpu-next,gpu`，为了杜比映射而优先 gpu-next；`DolbyAutoSW` 默认开启且适用于所有平台，识别到 DV 的 Emby 起播/切集会强制软解，本机文件没有同样的 DV 识别。这些分支需要分别记录实际 hwdec/VO、颜色和丢帧，不能笼统认定 MPV 或电视性能不足。

## Plezy

参考提交 `96504b618d99113434029080e52d5e3413ec588d`：

- [SurfaceFrameRateVote.kt](https://github.com/edde746/plezy/blob/96504b618d99113434029080e52d5e3413ec588d/android/app/src/main/kotlin/com/edde746/plezy/mpv/SurfaceFrameRateVote.kt) 按呈现帧率乘实际倍率投票，逐场输出另乘 2；暂停/停止/换 Surface 清理。
- [MpvPlayerCore.kt](https://github.com/edde746/plezy/blob/96504b618d99113434029080e52d5e3413ec588d/android/app/src/main/kotlin/com/edde746/plezy/mpv/MpvPlayerCore.kt) 使用其定制 `vo=mediacodec,gpu`，硬解帧带时间戳交系统合成，字幕/OSD 单独一层；软件 DV 重映射才切 gpu-next，具体依照 profile 和硬件能力分流。
- [DecoderOperatingRate.kt](https://github.com/edde746/plezy/blob/96504b618d99113434029080e52d5e3413ec588d/android/app/src/main/kotlin/com/edde746/plezy/mpv/DecoderOperatingRate.kt) 按帧率、倍率及设备上限声明解码吞吐需求，留有余量和上限；注释的设备数据不能外推到小米电视。

## 当前固定 mpv 的限制

构建固定 mpv `e167836802da6d5a4301bd4c4eeb3c5c3c17ccb8`，libplacebo `3330a515d62139259c26239014f286e233bd3a5c`。Gread 用于仓库定位；精确提交内容从官方 raw 源码核验，因为 Gread 读取接口没有版本参数。

- [vo.rst](https://github.com/mpv-player/mpv/blob/e167836802da6d5a4301bd4c4eeb3c5c3c17ccb8/DOCS/man/vo.rst#L725) 的直出为 `mediacodec_embed`；该路径不支持 mpv 字幕、OSD 和视频滤镜。因此不能把现有输出改成这个名字就交付。
- [options.rst](https://github.com/mpv-player/mpv/blob/e167836802da6d5a4301bd4c4eeb3c5c3c17ccb8/DOCS/man/options.rst) 及 `video/decode/vd_lavc.c` 均无 Plezy 使用的 `hwdec-mediacodec-operating-rate` 选项；不直接照搬该配置。
- [builtin.conf](https://github.com/mpv-player/mpv/blob/e167836802da6d5a4301bd4c4eeb3c5c3c17ccb8/etc/builtin.conf#L45) 的 fast 包括双线性缩放、关闭抖动及高质量缩放步骤。用户后续授权仅TV采用轻量缩放，保留fruit抖动且已有有效自定义配置时跳过；手机和桌面不改变。

## 后续验证优先级

1. 安装实际包含修复的 TV 包；同片同段对照 1 倍与问题倍率、弹幕开关、Media3/MPV。
2. 记录实际硬解、输出驱动、分辨率/帧率、掉帧增量、缓冲与显示模式。刷新率修正只解决节奏匹配，不能证明解码/渲染吞吐够用。
3. 若 MPV 落软解，优先核查杜比策略与设备解码能力；若已硬解但渲染掉帧，再比较 GPU 配置。若需要直出，应同时设计字幕/OSD、HDR/DV 和回退，再独立验证库升级。

本轮没有真机或参考应用同片对照，未证明任何参考应用在目标电视上完全不卡。不开启服务、不生成安装包。

## 本轮实施与探针环境

- 复用宿主已有platform=android_tv；仅无有效用户mpv配置时设置scale/dscale=bilinear、correct-downscaling/linear-downscaling/sigmoid-upscaling=no、dither=fruit。hdr-compute-peak沿用既有Android平台选项，VO、杜比软解、字幕和硬解偏好不改。
- 本机已有测试库是mpv 0.35.1，不支持profile=fast；显式采用已核实的缩放参数避免依赖profile名称或未来扩展，真实探针已验证参数回读、自定义配置及hwdec/VO/字幕/tone-mapping未被这些参数修改。它不是Android GPU或目标固定提交的运行验证。测试须设置LD_LIBRARY_PATH指向.toolchain/mpv-test/usr/lib/x86_64-linux-gnu。
- player.opts保留字符串值协议，新增两类独立累计计数。Android只展示打开面板时的快照；缺失/非法值隐藏，零值保留，Media3只读自身倍率。
- 实际TV面板回归证实末尾音频编码无法用方向键看到；仅媒体信息启用上下键滚动，其余侧栏仍由原焦点逻辑导航。
