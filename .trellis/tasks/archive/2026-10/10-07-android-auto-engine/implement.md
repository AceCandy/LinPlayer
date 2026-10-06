# 实施顺序

1. 共用 PlayerController / 两个适配器、错误分类与回退快照，新增故障回归。
2. 接手机/TV 起播、基本控制和失败路径；核对选轨、字幕关闭、服务切换与离页收尾。
3. 设置加入自动/Media3/MPV；兼容旧值、长按及非 Emby 入口。
4. 独立只读审查，再运行 Android 参数检查和相关 Gradle 回归；核查 release 两端出包。
5. 更新 Android 规范与两端 UI 设计正本，记录实际验证/未验证与设备风险。

验证命令：python3 scripts/check-android-args.py；在 apps/android 执行 ./gradlew :app:testDebugUnitTest --tests '*PlayerControllerTest*' --tests '*ExoPrefsTest*' 与新增/受影响的播放器、设置回归。项目 JDK/SDK/Gradle 缓存使用 .toolchain。

最终独立复核不得修改无关代码。编码阶段未提交；用户随后确认收尾并提交本地播放基线，不推送。临时文件和启动服务结束前清理。
