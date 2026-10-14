# 证据与独立复核

## Yamby对照
本地Yamby 2.1系列（构建11）APK，jadx存在42处反编译错误，以下仅引用成功解析部分：AssPlayerKt.buildWithAssSupport接ExoPlayer；r3/C5263选择OVERLAY_CANVAS并挂AssSubtitleView；AssSubtitleParserFactory将Matroska与完整外挂分流；AssTrackOutput直接交付样本时间和事件。EmbyMediaStream保存Codec/DisplayTitle/IsExternal，但未证明选择器显示内外置标签，不据此宣称UI完全还原。

## 根因
JNI NONE provider + 不存在的sans-serif默认族，缺失样式字体时空图。附件先到初始化g_lib使系统目录设置被跳过。已排除“carrier过滤后没有Compose重绘依赖”的推断：LibassLayer有独立33ms循环及frame状态。

## 验证
- 直接提取旧HEAD与工作树libass_android.go的C前导，连接宿主libass 0.17.1：普通/提前创建库两种顺序，旧版均有效像素0，新版均3292；外挂文件、内封chunk、move向右移动、字体刷新后仍可见、结束清屏均通过。
- 测试字体来自宿主实际字体，改名为候选名只在忽略目录中，用于验证文件兜底，不证明Android字体外观。
- OSD修复前新增断言失败“网速必须在首排按钮下方”，修复后通过；640×360/320×640、1.3字号截图检查网速位于按钮下方且与倍率无重叠。
- 37项相关Android本地回归通过；补充Logic49、PhoneEnginePlayback54、TvTrackTiming4通过，共144项。
- 核心门禁全部通过；252处Android命令参数检查通过；隐私检查通过（需把python3临时别名放入PATH）。

## 独立复核
原生字体与UI/菜单分别只读审查，未发现阻断问题。指出设备测试Roboto固定文件路径依赖，已改为从真实系统字体文件中选择附件。主线程复看截图后去掉重复语言，MPV已知ASS/SSA分别标名；对应回归重新运行。

## 设备边界
软件模拟器API30支持ARM翻译，无法使用KVM。两APK已安装；instrumentation首次长时间无输出，指定ARM64重试120秒仍超时，未产出测试结果，不能认定设备验证通过。用户具体片源、真机全链路仍未验证。

## 交付包
Android手机ARM64，2.0.0 / 20000，29,681,488字节。SHA256：`6fe951956309e4ad119c27b7dad338723584caba180c0443ff93bba1b6256ce4`。
v2/v3验签通过，13个ARM64 ELF均压缩，extractNativeLibs=true，交付与Gradle产物逐字节相同，包内核心与本轮JNI构建相同。未配置崩溃上报。最终路径`build/android/app-arm64-v8a-release.apk`。
