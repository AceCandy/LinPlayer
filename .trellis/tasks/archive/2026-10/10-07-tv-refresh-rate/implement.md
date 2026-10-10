# 实施与验证

用户已回复 ok 授权实施，随后确认整理并提交本地播放基线；不推送。

1. 新建 TvRefreshRate：同分辨率模式算法、窗口偏好恢复、Media3 策略生命周期与双内核采样。接到现有 TvPlayerPage，不改变起播/停止语义。
2. 新回归覆盖分数/整数帧率、分辨率隔离、已有匹配、未知/不支持、补帧保护、真实播放页换集/退出、迟到命令和 Media3 策略恢复；故障注入确认断言能失败。
3. 独立只读复核，主线程点验与修复；运行相关 Gradle 回归、Android 参数门禁、隐私扫描及 diff check。
4. 同步 Android 规范与 UI_TV 正本，构建 TV release，核对最终 APK 的签名、版本与 ABI；关闭启动的服务并记录验证边界。

验证命令：apps/android 下 ./gradlew :app:testDebugUnitTest --tests '*TvRefreshRateTest' --tests '*TvRefreshRatePlaybackTest' --tests '*TvFocusTest' 及上一轮双内核相关类；根目录 python3 scripts/check-android-args.py、bash scripts/check-secrets.sh、git diff --check、bash scripts/pack-android.sh tv。

工具链使用现有 .toolchain 目录；实际设备未连接时明确未验 HDMI、实际黑屏/切屏、HDR/DV 和补帧输出，不把 JVM 结果当真机通过。

上述四步编码、独立复核、63项相关回归、门禁与 TV 出包已完成，详情见 verification.md。本轮收尾提交；用户暂缓 TV 设备验收，任务保持待验收，Gradle daemon 已停止。
