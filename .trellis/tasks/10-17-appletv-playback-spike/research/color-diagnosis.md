# tvOS 颜色偏差调查

## 用户反馈与范围

- 用户观察“其它的好像没问题”，但画面存在偏色。此反馈不等于完整的画质验收，偏色作为未通过项继续调查。
- 用户确认 B（videotoolbox-copy）与 C（软件解码）看起来没有偏色；偏色具体形式和更多片源尚未确认。不假定是电视设置或 HDR 造成。
- 本轮只调整临时诊断程序，未修改产品代码、正式平台支持或用户电视设置。

## 片源与代码事实

- 通过同一选片路径读取测试电影及 PlaybackInfo：服务端已探测时长为 4911.082 秒，视频为 H.264 High、8-bit、yuv420p、SDR，ColorPrimaries/ColorSpace/ColorTransfer 均为 bt709。鉴权会话登出返回 204，未记录片名、媒体标识、地址或凭据。
- 当前 `core/player/player.go` 的 baseOptions 和播放默认设置没有为临时 tvOS GLKit 路径明确配置输出色域/色调映射。不能据此推断 libmpv 实际没有默认色彩处理。
- 固定 MPVKit tag `1.1.0-n9.0.2` 的 OpenGL tvOS 示例注明：`OpenGL rendering can not play 10bit videos correctly on iOS/tvOS.` 该警告不能直接解释本轮 8-bit SDR 样本。
- 同 tag OpenGL 示例使用 OpenGLES2；临时探针使用 OpenGLES3、GLKView RGBA8888。示例没有提供可直接证明正确的显示色彩空间配置。Metal 示例的 gpu-next/Vulkan/MoltenVK 属另一条输出路径，不能在未对照实测时宣称自动修复偏色。

固定源码：

- https://github.com/mpvkit/MPVKit/blob/1.1.0-n9.0.2/Demo/Demo-tvOS/Demo-tvOS/Player/OpenGL/MPVViewController.swift
- https://github.com/mpvkit/MPVKit/blob/1.1.0-n9.0.2/Demo/Demo-tvOS/Demo-tvOS/Player/Metal/MPVMetalViewController.swift

## 同片段解码对照实测

保持同一片源、GLKit/ES3/GL render-context 和画面尺寸，逐段设置 hwdec，并重新跳到同一 60 秒处播放。临时画面显示 A/B/C 标签，未改变电视设置。

| 阶段 | 实际 hwdec-current | video-params 像素格式 | 硬件底层格式 |
|---|---|---|---|
| A | videotoolbox | videotoolbox | nv12 |
| B | videotoolbox-copy | nv12 | 不适用 |
| C | no | yuv420p | 不适用 |

- 三段实际 `current-vo=libmpv`，输入与 `video-out-params` 的 colormatrix/primaries 均为 bt.709，colorlevels 为 limited，gamma 为 bt.1886。输出帧像素格式分别与上表一致。
- 每段均正常推进至约 75 秒，说明切换后实际解码与播放仍在运行，不是只接受选项而未生效。
- `video-out-params` 是播放器帧参数，不能当作最终 framebuffer、Core Animation 合成或电视色彩空间的证据。
- 另一次 framebuffer encoding 查询返回 GL_INVALID_ENUM（1280），未取得有效结果；该查询值不能用于判断输出色彩空间。错误已读取清除，后续正常播放；后续实验应先纠正查询对象/上下文，不能依赖这个值。
- 构建、签名、严格验签、安装、启动、卸载均返回 0；单次 fileLoaded、1886 次 render、15 次非黑采样，C 字符串分配/释放均为 301，error 日志事件计数为 0。正常执行停播、核心关停，服务端登出 `server_ok=true`。

## 当前判断与下一步

- 用户观察 B、C 无偏色，结合实际解码模式读回，问题更可能位于 VideoToolbox 硬件帧直接进入当前 GL 渲染的路径；具体根因未确认，不能扩大为所有 VideoToolbox 硬解都有问题。
- B 保留硬解，是当前已有人眼对照依据的候选方案；尚未修改产品默认选项，也未验证其高码率、长时间播放与额外帧复制开销。
- 需独立验证最终选定方案的真实画面；没有据此扩大到 HDR、杜比视界、所有 10-bit 或全部片源支持。
