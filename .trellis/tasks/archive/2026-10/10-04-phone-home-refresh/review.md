# 归档前复核 · 2026-10-04

## 范围与结论

核查未提交的 HomePage.kt、PhoneHomeRefreshTest.kt、Android 规范、UI_MOBILE 设计及本任务材料；未修改业务代码。本任务代码尚未提交，目录尚未归档，因此将提前填写的 `completed` / `completedAt` 改回 `in_progress` / `null`。工作提交之后，再通过任务脚本正式归档，避免先产生归档提交、后提交功能代码。

任务的 implement/check 清单各有两条有效规范引用，均指向任务目录外的现存规范；归档移动不会使它们失效。未发现外部文档引用本任务活动目录，也未发现任务内临时导出或隐私文件。

## 独立审查与源码复核

- 首页取消路径由页面协程和 RESUMED 生命周期管理；CoreClient.callJson 取消时调用 Native.cancel 并删除 pending，dispatch 忽略已删除序号。新增 FakeCore 测试是同步响应，不能据此宣称已覆盖挂起请求、迟到响应或取消时刷新指示器。
- 审查提出“views 失败导致推荐永久骨架”，复核当前 HomePage.kt 后不成立：hero 已有 `views is Block.Fail` 分支，非静默失败还有整页 ErrorState。未为此修改代码。
- 返回首页测试对随机请求数取前后基线比较，即使基线为零，也能发现新增随机请求；该断言有效。不过它没有直接断言返回时各库最新请求数不增加，不能将其描述为完整请求时序验证。
- 既有材料提到账号切换短暂保留旧内容及轮播选中条目稳定性。本轮没有复现、修复或真机验证，保持观察项，不将其写成已经解决的缺陷。
- Android 规范与 UI_MOBILE 已包含本任务刷新、去掉接下来看和最新内容复用契约。本轮未产生需要另加长期规范的已证实代码规则。

## 当前工作树复验

在仓库根目录执行；本地 JDK、SDK 和 Gradle 缓存通过环境变量指定，不读取或输出凭据。

```sh
JAVA_HOME="$PWD/.toolchain/jdk-21.0.2" \
ANDROID_HOME="$PWD/.toolchain/android-sdk" \
GRADLE_USER_HOME="$PWD/.toolchain/gradle" \
apps/android/gradlew -p apps/android --no-daemon :app:testDebugUnitTest \
  --tests xyz.linplayer.app.PhoneHomeRefreshTest \
  --tests xyz.linplayer.app.PhoneThemeTest
python3 scripts/check-android-args.py
git diff --check
git -c core.quotePath=false ls-files --cached --others --exclude-standard |
  python3 scripts/check-secrets.py scripts/secrets-allow.txt scripts/secrets-baseline.txt
python3 .trellis/scripts/task.py validate 10-04-phone-home-refresh
.toolchain/android-sdk/build-tools/36.0.0/apksigner verify build/android/app-arm64-v8a-release.apk
```

- Gradle 成功，测试任务实际执行；XML 报告：首页 9 项、主题 8 项，失败/错误/跳过均为 0。报告位于已忽略的 `apps/android/app/build/test-results/testDebugUnitTest/`。
- 参数检查 265 处通过；diff 空白检查通过；两份上下文清单校验通过。
- 隐私扫描包含未跟踪文件，通过，未新增真实地址或凭据。首次 shell 包装入口因环境缺少 `python` 无法执行，改用相同 Python 扫描器和基线，通过 `python3` 执行，未更改判据。存量基线不在本任务整改范围。
- 现存最终手机 APK 验签通过，且与 release 输出 SHA-256 一致：`cb23674af312501f722a2435b8730298833677065e8d597ae11bb2e441887378`。

## 未验证与归档前提

本轮未重建 release；验签及字节一致仅证明现存两个 APK 一致且签名有效，不能建立当前源码与 APK 的完整构建对应关系。历史出包记录保留为历史结果。

未做真机手势、系统适配、播放退出最终进度上报与首页续播查询联调、真实服务端耗时/图片编码次数对比、账号切换及轮播稳定性验证。未重测 TV、桌面、Go 核心。本轮未启动调试服务，未产生需入仓的截图或日志。

待确认工作提交后，依次执行工作提交、任务归档、会话记录；正式归档前再次核对工作树。未获提交授权时保留本任务活动目录，不运行会自动提交的归档或日志命令。

## 提交与归档执行

用户确认上述顺序后，工作提交为 `fdeb7193`（首页代码、测试、Android 规范及手机设计），暂存区隐私与空白检查通过。归档前工作树仅余本任务材料，正式归档由任务脚本完成，未推送远端。上文“尚未提交”描述归档前复核时的状态，以本节为最终执行记录。

任务直接在 main 完成，没有 PR；归档使用脚本为此提供的 `--skip-branch-validation`，未虚构功能分支。脚本已移动目录并写入 completed，但自动提交因旧任务目录从未被跟踪、旧路径 pathspec 不存在而失败；确认暂存区只有本任务七份归档文件后，单独完成归档提交，未修改归档脚本。
