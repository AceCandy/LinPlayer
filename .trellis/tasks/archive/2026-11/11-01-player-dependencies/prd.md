# 核对并升级播放内核依赖

## Goal

核对 Media3 与各平台 mpv 的官方发布和固定输入，将 Media3 升级到最新稳定版并验证播放相关回归，记录 mpv 是否需要升级。

## Requirements

- 按最新稳定版核对播放器依赖；不自动切换 alpha 或追逐开发分支。
- Media3 从 1.11.0 升级到 1.11.1，保留现有预加载、字幕和拖动接线。
- mpv 官方最新稳定版为 v0.41.0；Android 固定 e167836802da6d5a4301bd4c4eeb3c5c3c17ccb8，比该标签领先 925 个提交；Windows 固定 3186d369f9，比该标签领先 1092 个提交。二者已包含稳定版，保留现有自编补帧与固定哈希，不降级或换用新每日构建。Linux 继续使用系统 libmpv。
- 不打包应用、不改上一轮功能的范围，保留未提交改动。

## Acceptance Criteria

- [x] 对照官方发布记录与 GitHub compare 验证版本。
- [x] Gradle dependencyInsight 确认全部 7 个 Media3 模块为 1.11.1。
- [x] Kotlin 主代码与测试代码编译通过；11 个测试类共 88 项，0 失败、0 错误、0 跳过。
- [x] 独立复核最终 diff；git diff --check 与 check-workflows.sh 通过。未出包、未验证真实设备播放。

## Validation

- 使用仓库已有 .toolchain/jdk-21.0.2、.toolchain/android-sdk、.toolchain/gradle，显式配置 JAVA_HOME、ANDROID_HOME、GRADLE_USER_HOME。SDK 实际存在，先前任务中“本机无 SDK”的判断不成立。
- :app:testDebugUnitTest 定向运行 PlayerControllerTest、ExoPreloadRecoveryTest、PlaybackBufferTest、StartupTimingTest、ExoAssMergeTest、LibassSelectionTest、ExoSubtitleTest、ExoPrefsTest、Media3DiagnosticsTest、PhonePlayerOsdTest、PhoneMedia3RecoveryTest。包含上一轮新增的拖动模式回归。
- 1.11.1 删除的 HAGC 实验 setter 在本项目无调用，升级无需业务 API 适配；保留既有弃用警告。
- mpv 固定源码比最新稳定标签更新，不等于最新开发提交。未变更原生库、未对新版每日构建做兼容性验收。

## Notes

- 核对日期：2026-11-01。
- Media3 发布记录：https://developer.android.com/jetpack/androidx/releases/media3#1.11.1
- Media3 对应标签：https://github.com/androidx/media/blob/1.11.1/RELEASENOTES.md
- mpv 发布记录：https://github.com/mpv-player/mpv/releases/tag/v0.41.0
- Android 比较：https://github.com/mpv-player/mpv/compare/v0.41.0...e167836802da6d5a4301bd4c4eeb3c5c3c17ccb8
- Windows 比较：https://github.com/mpv-player/mpv/compare/v0.41.0...3186d369f9
