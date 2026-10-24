# 跨端视觉对照

`catalog.json` 是同数据对照的唯一资料源；PNG 为程序化生成的几何山脉与圆形，
不是影片海报、真实账号资料或外部授权图片。包含电影、长标题、缺图、续播、已看、
剧集/季集、演员和两个媒体版本；筛选选项同样读取此文件。

## 正式页面入口

从仓库根目录运行，使用项目已有工具链，不生成正式安装包：

```sh
DOTNET_CLI_HOME="$PWD/.toolchain/dotnet-home" \
.toolchain/dotnet/dotnet run --project tools/productcheck --no-restore -- \
  build/core/liblpcore.so --visual-alignment

JAVA_HOME="$PWD/.toolchain/jdk-21.0.2" \
ANDROID_HOME="$PWD/.toolchain/android-sdk" \
GRADLE_USER_HOME="$PWD/.toolchain/gradle" \
bash -c 'cd apps/android && ./gradlew :app:testDebugUnitTest \
  --tests "xyz.linplayer.app.VisualAlignmentTest" \
  -Proborazzi.test.record=true --offline --console=plain'
```

桌面走正式 MainWindow、Nav、页面及 Go 核心，只替换 Emby 上游资料；
首页候选选择在渲染阶段固定为 `hero` 中的影片，不作为候选算法验证。
Android 走正式 PhoneRoot / TvRoot、页面与组件，命令及图片由夹具提供。

tvOS 测试宿主也已接入这份资料及图片；仅在测试适配层补齐 Swift DTO 必需的传输字段，
保留正式 DTO 校验、跨服图片隔离断言和补充页面样本。在 Apple SDK 主机启动 tvOS
模拟器后运行 `bash scripts/check-tvos-visual.sh <模拟器UUID>`，输出到
`build/visual-alignment/tvos/`。核心六页（首页、媒体库、电影详情、剧集详情、设置、
播放器）提供深浅主题，详情另截滚动下半页；播放器使用与其它端相同的静帧及进度。
2026-10-24已通过 Swift 编译与模拟器运行，新增35图；播放器经正式RootView全屏呈现，
断言播放呈现为深色，避免外层浅色偏好导致假差异。首页另有实际文字/背景对比度采样，
加载预览检查六种Lottie视图的48×48点实际尺寸。

| 形态 | 逻辑尺寸 | 主题 | 页面 |
| --- | --- | --- | --- |
| 手机 | 393 × 873 | 深、浅 | 首页、媒体库、电影详情、剧集详情、设置 |
| 平板 | 1024 × 768 | 深 | 同上 |
| Android TV | 960 × 540 | 深、浅 | 同上 |
| 桌面 | 1440 × 900 | 深、浅 | 同上 |
| 桌面窄窗 | 640 × 900 | 深、浅 | 媒体库、电影详情 |
| 手机横屏、Android TV | 960 × 540 | 深、浅 | 播放控件与合成静帧 |
| 桌面 | 1440 × 900 | 深、浅 | 播放控件与合成静帧 |
| Android TV | 960 × 540 | 浅 | 播放失败、首焦点及重试 |
| tvOS | 1920 × 1080 | 深、浅 | 核心六页及电影/剧集详情下半页 |
| tvOS | 1920 × 1080 | 深 | 其它浏览/设置、加载预览、片头跳过与字幕样本 |

输出位于已忽略的 `build/visual-alignment/<形态>/`，重复运行覆盖同名文件，
不要并发运行同一个入口。81 张图片是审查材料，**不是已批准的 golden 基准**。
保留历史对照时先单独复制到本地忽略目录，
不要把“重新生成成功”当成“视觉回归通过”。

## 同端像素门禁

使用同工具链、同字体环境和同矩阵，先保存独立候选目录，再重新执行截图入口。
比较器只读基准；缺页、尺寸变化或任意像素变化均以非零退出，不会自动接受新图。

```sh
DOTNET_CLI_HOME="$PWD/.toolchain/dotnet-home" \
.toolchain/dotnet/dotnet run --project tools/productcheck --no-build -- \
  --compare-visual-selfcheck

DOTNET_CLI_HOME="$PWD/.toolchain/dotnet-home" \
.toolchain/dotnet/dotnet run --project tools/productcheck --no-build -- \
  --compare-visual build/visual-alignment-baseline build/visual-alignment
```

Android 重复运行若显示 `UP-TO-DATE` 不算重新渲染；使用 `--rerun-tasks` 强制执行。
基准目录由人工审查后单独保存，上述命令不会创建或批准基准。

## 验收边界

- 跨端人工比较信息层级、组件语义、配色、内容顺序和主要操作；不要求不同形态像素相等。
- 后续自动像素回归只比较同形态、同尺寸、同主题；需先重复渲染确认确定性，再批准基准。
- Android30图与桌面16图已有重复渲染一致证据；tvOS35图尚未建立逐像素确定性门禁，加载动效不能直接作为静态golden。
- 正式主题和真实页面渲染不等同于真机焦点、触摸、窗口/DPI、系统栏和播放验证。
- 夹具包含季集/演员/版本，但尚未覆盖所有滚动位置、版本弹窗与错误分支。
- 播放器截图是正式控件叠加合成静帧，不启动解码，不证明真实媒体播放正确。
- 外部Mac已通过最新产品源码的正式Swift门禁及333条绑定/CoreClient回归；
  tvOS新夹具运行、核心深浅配对和补充页人工检查已完成，但不是全平台真机验收。
