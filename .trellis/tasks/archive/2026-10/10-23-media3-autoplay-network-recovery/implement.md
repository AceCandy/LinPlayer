# 实施与验证计划

## 实施前门禁

- [x] 用户已同意创建任务。
- [x] 已记录症状、范围、验收和技术未知项。
- [x] 用户在最终规划摘要之后明确批准进入实施。
- [x] 批准后加载 Phase 1.4、校验上下文并运行 task.py start。

## 执行步骤

1. 建立最小故障注入回归。
   - 完整读取即将修改的生产代码、注释和测试；复用现有 Robolectric、本地媒体与本地 HTTP 测试方式。
   - 覆盖正常衔接 → 当前集卡顿 → 预载中断/失败 → 网络恢复 → 原会话至少三次自动换集。
   - 验证：保留原代码失败的断言、失败阶段与解释；若无法重现跨集残留，继续缩小范围，不提交推测修复。
2. 修复唯一被复现证明的恢复断点。
   - 初始预计涉及 `ExoPreload.kt` 或 `PlayerPage.kt`，以实验确定必要文件；加载控制和核心不是默认改动项。
   - 保留原播放器身份、正常起播降级、停止屏障、字幕与轨道、业务身份及上报顺序。
   - 验证：故障回归通过；同一播放器的后续三次预载与队列接管重新有效，无重复 stop/load。
3. 独立复核与针对性回归。
   - 复核所有改动行、共享调用方、异步取消和旧结果屏障，不顺手修改 UI 或流影。
   - 验证：运行相关 Android 单测；涉及命令参数时额外运行参数契约检查。确须 Go 改动时先重新审阅范围。
4. 交付证据与限制。
   - 记录已证实根因、修复点、原失败/修复后结果及设备验证缺口。
   - 必要时补既有状态规范或故障 lesson，不自动出包、提交、推送。
   - 关闭本次临时服务，删除临时下载、导出和敏感材料。

## 当前执行进度

- 已补底层故障注入与页面自然连播实验；受控场景下原生产代码可以恢复，尚未满足 AC1 的用户故障先红后绿。
- 生产代码未修改，步骤 2 尚未开始；未靠隐藏加载、调阈值或重建播放器绕过问题。
- 两个新增测试已独立复核并强化缓冲/自然切入/释放断言；三组针对性回归曾联合通过，后续一次复测被并行流影任务的编译错误阻断。
- 无可连接 Android 设备；需要问题手机日志或设备再继续缩小范围。
- 用户随后明确要求出手机测试包，标准打包及产物校验已通过，详见研究证据。
- 用户要求先提交归档；本轮按排查阶段收尾归档，不代表故障修复或 AC1 达成。后续收到真机日志后继续排查，未授权推送。

## 预计文件

| 文件 | 原因 |
|---|---|
| `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/ExoPreload.kt` | 单预载槽、管理器、失败/取消恢复和队列接续的主要核查位置；只有证实相关才改。 |
| `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/PlayerPage.kt` | 许可、请求、排队与目标替换的生产接线；只有证实相关才改。 |
| `apps/android/app/src/test/kotlin/xyz/linplayer/app/ExoPreloadRecoveryTest.kt` | 必要时新增底层受控数据源恢复回归，不以页面状态替身证明预载恢复。 |
| `apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneEnginePlaybackTest.kt` | 补真实页面内连续切集及请求/停止顺序回归，避免只测辅助函数。 |
| `apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneMedia3RecoveryTest.kt` | 页面 HTTP 自然连播故障注入使用独立测试类，保持已有大型页面测试不受夹具影响。 |
| `.trellis/spec/frontend/state-management.md` / `docs/lessons/android.md` | 根因明确后仅补该类恢复的可执行约束或实测结论。 |

## 针对性命令

实施时显式使用仓库已有 JDK、SDK 和 Gradle 缓存，不写入用户目录。以下为计划命令，目前尚未执行：

```bash
cd apps/android
./gradlew :app:testDebugUnitTest \
  --tests 'xyz.linplayer.app.ExoPreloadRecoveryTest' \
  --tests 'xyz.linplayer.app.PhoneEnginePlaybackTest' \
  --tests 'xyz.linplayer.app.PlayerEngineLifecycleTest' \
  --tests 'xyz.linplayer.app.PlaybackBufferTest' \
  --tests 'xyz.linplayer.app.Media3DiagnosticsTest' \
  --tests 'xyz.linplayer.app.PlaybackReturnTest'
```

仅在新增了上述测试类后使用对应过滤；按实际改动增加必要的共享调用者测试，不运行无关全量构建。

```bash
python3 scripts/check-android-args.py
git diff --check
git status --short
```

针对性单测会编译测试所需代码，但不构建 APK。真机/模拟器网络故障复现取决于可用设备；不得改动用户正在使用的会话或清空应用数据。
