# 第三方组件与发行许可边界

项目源码许可见仓库根 LICENSE。它不替代发行包、系统依赖和可选下载组件的许可。

| 范围 | 组件与许可边界 |
| --- | --- |
| 播放内核 | Windows 完整版 libmpv、Android libmpv、Linux 系统 libmpv 各自带有 mpv/FFmpeg 及其依赖的许可;不能把整个发行包统一解释成项目源码许可。 |
| 通用补帧包 | VapourSynth、Python、k7sfunc、vs-mlrt、akarin、ONNX Runtime、模型及运行库按各自许可使用。DirectML.dll 适用 Microsoft DirectML 软件许可条款;MSVC 运行库适用 Microsoft 的再分发条款。 |
| N 卡加速包 | 额外包含 NVIDIA TensorRT/CUDA 等专有许可运行组件,不属于完全开源组件包。 |
| DirectML 仓库 | 示例、工具等源码的 MIT 许可与可再分发的 DirectML 二进制软件条款是两份许可,不能混用。 |

可选补帧组件由用户主动下载,基础播放不要求安装。安装前界面说明包含专有许可组件。
Windows 运行包的固定来源与 SHA256 在 third_party/libmpv/windows.lock.json;补帧包的固定来源和 SHA256 在 core/interp/runtime.go、trt_windows.go。

重打补帧包时必须保留与实际版本对应的许可文本和 NOTICE,核对各模型及运行库的再分发条件,重新计算哈希并使用新运行包版本。scripts/pack-interp-runtime.py 的说明已纠正 DirectML 标注;已经发布的旧运行包不会因修改脚本而自动更新。
此轮没有重发第三方运行包,也没有声称完成全部再分发法律审查。
