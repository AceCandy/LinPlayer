# 第三方组件与发行许可边界

项目源码许可见仓库根 LICENSE。它不替代发行包、系统依赖和可选下载组件的许可。

| 范围 | 组件与许可边界 |
| --- | --- |
| 播放内核 | Windows 完整版 libmpv、Android libmpv、Linux 系统 libmpv 各自带有 mpv/FFmpeg 及其依赖的许可;不能把整个发行包统一解释成项目源码许可。 |
| 旧版通用补帧包 | VapourSynth、Python、k7sfunc、vs-mlrt、akarin、ONNX Runtime、模型及运行库按各自许可使用。DirectML.dll 适用 Microsoft DirectML 软件许可条款;MSVC 运行库适用 Microsoft 的再分发条款。 |
| 旧版 N 卡加速包 | 额外包含 NVIDIA TensorRT/CUDA 等专有许可运行组件,不属于完全开源组件包。 |
| DirectML 仓库 | 示例、工具等源码的 MIT 许可与可再分发的 DirectML 二进制软件条款是两份许可,不能混用。 |

Windows 外置补帧组件及其下载、安装入口已下线；Android 内建 OpenCL 补帧不依赖这些组件。
Windows 播放运行包的固定来源与 SHA256 在 third_party/libmpv/windows.lock.json。

旧版补帧包仍受其随附许可和 NOTICE 约束；删除源码和入口不改变已发布二进制的许可。
此轮没有重发第三方运行包，也没有声称完成全部再分发法律审查。
