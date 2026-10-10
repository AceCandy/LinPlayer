# 验证

- `:app:testDebugUnitTest --tests '*PhoneEnginePlaybackTest' --tests '*PhonePlayerOsdTest' -Proborazzi.test.record=true`：14+5项全部通过。
- 生产Media3缓冲读数与Canvas缓冲绘制分别断接线后对应测试失败，随后恢复真实生产代码，完整回归绿。
- MPV绝对前沿、控件隐藏、后台暂停查询/前台恢复、OSD重开与换版本迟到响应、三段像素、拖动不中断、缺值/非法值/越界/未知时长均覆盖。
- 横屏深浅/紧凑/大字号及竖屏真实Compose截图已人工检查。
- Android参数门禁255处通过；隐私扫描1603文件无新增凭据或真实地址，411处既有基线未改；`git diff --check`通过。
- 已同步Android规范和手机UI正本。
- 未验手机真机、TV真机、RTL手势和真实TalkBack；显示单个内核缓冲前沿，不能推断磁盘离散缓存或全片下载比例。
- 未提交、推送或归档；本批未启动服务。

手机release：`bash scripts/pack-android.sh arm64-v8a`退出0，最终包`build/android/app-arm64-v8a-release.apk`，63,212,765字节，与本次中间release逐字节一致。SHA256：`7aab2e406bee8097bd0f6ea6581a85b86f3e1c8c3340074b68b61a69cc0d4e3f`。

- 默认apksigner按minSdk24验证v2/v3=true；显式`--min-sdk-version 23`确认v1/v2/v3=true（仅扩大签名校验范围，不改变应用最低系统要求）。默认报告v1=false不能解释为缺少v1签名。
- 13个SO均仅arm64-v8a、ELF64 AArch64，liblpcore/libmpv存在。
- release DEX包含“已缓冲至 ”、“缓冲进度未知”和既有`load_to_frame_ms`；不是旧包。
- TV包未重建；临时打包日志已删除。
