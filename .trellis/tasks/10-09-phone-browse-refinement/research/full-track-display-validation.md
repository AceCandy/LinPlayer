# 完整轨道展示验证（2026-10-09～10-10）

## 用户纠正与根因

用户给出了Yamby与本项目的同单集截图。上次只验证框架/布局，视频自拼height+codec省略动态范围，音轨自拼codec+layout省略服务端规格和原始标题，字幕只显示title而不展示完整规格。布局回归成功不能证明信息完整；后续必须对照实际内容字段而非只核对框形。

本轮只改手机DetailPage摘要/呈现与两个示意图标。核心已有title/display_title/video_range_type/bitrate等字段，无需改服务端、核心或播放内核。参考中字幕为繁体，而本项目为简体，这只是实际选择差异，不授权修改偏好。

## 实现契约

- 视频按真实height/range/profile/codec显示，DOVI/Dolby显示完整Dolby Vision，HDR10Plus显示HDR10+；不从版本文件名猜HDR。缺字段不伪造参数，SDR/Unknown不生成HDR。
- 音轨/字幕主行使用服务器display_title规格，副行保留原始title；二者不互相冒充。缺标题时用已知语言/编码/声道/码率回退，码率复用既有fmtRate。
- 默认标记只追加一次；字幕关闭不留旧副行；未标注、und、空值不作真实名称。
- Stream.label和selectedTrack未改，选择弹窗仍以轨道真名优先；版本推荐、续播与起播入口保留。
- 主行常规14sp、副行13sp，全文换行；24dp留白/8dp项距，音轨mediaPanel、字幕主题次级色分层，视频与单音符填充示意图标。其它图标族和全局字体不变，点击项仍至少48dp。

## 验证

- 完整DV/音轨/字幕主副行、200%字号全文两个新增UI用例先在旧实现失败，修复后通过。
- 新增缺标题/HDR10+/640kbps已知参数回退及关闭字幕后旧文案消失用例。
- 58项直接相关回归通过：PhoneDetailOptionsTest 16、PhoneDetailCacheTest 23、SeasonSelectionTest 18、LogicTest轨道真名优先1。保留实际版本/起播/字幕关闭/换季验证。
- 实际Roborazzi渲染检查浅深色、320dp窄屏、200%字体的完整主副行；TextLayoutResult.hasVisualOverflow=false。新截图夹具补齐单集所属季，避免测试用模拟接口缺失产生的无关错误状态。
- 独立只读审查未发现确认缺陷，主线程复核默认标记、空字段、实际字幕选择与标题保留。
- 参数门禁256处及diff检查通过，隐私门禁无新增敏感内容。

## 既存失败（非本次范围）

扩大到完整LogicTest时105项中104项通过，`长按播放键换的是另一个内核`在无效值“垃圾值”处失败：测试期望exo，而UiPrefs.otherEngine现有实现返回mpv。HEAD的测试及实现也具有该差异，未改动内核逻辑或放宽断言凑绿。直接相关轨道真名测试独立通过。

## 未验证

未进行真实设备/真实服务端/帧耗时验收，不能声称Yamby像素级完全一致。服务端若缺原始标题/参数只能展示已知信息，不编造Full/side标记。

## 正式手机包

- `build/android/app-arm64-v8a-release.apk`，80,404,449 bytes，体积门禁通过。
- SHA256：`efaa615c751d3c2cb3470e955ca53f5a8a8365a54d1ad3ad57b4adb56051f163`。
- v1/v2/v3验签通过，13个ARM64 ELF包含liblpcore/libmpv；与本次Gradle输出逐字节一致。R8 mapping包含本轮videoSummary的新行号，确认正式构建纳入新格式化逻辑。
- 正式包与测试输出在已忽略目录，临时日志在结束前删除。未重出TV包。本次只记录，不提交归档。
