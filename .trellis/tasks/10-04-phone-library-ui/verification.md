# 验证与交付

## 本轮改动
- LibraryPage 使用 LpScaffold 紧凑库名 / 服务端总数 / 返回和库内搜索；去掉大库头图、取色和折叠动效。
- 排序 / 筛选条移出网格与 BlockBox，滚动、加载和空结果时均保留入口；使用媒体蓝色选中状态。
- 网格保留 3 列及插件覆盖、现有 MediaCard，行距改为 16dp，骨架与正式网格顶边保持一致。普通条目不显示时长。
- 移除库头独占 coverLib；更新 UI_MOBILE §7.3 和对应主题断言。

## 已验证
- 独立静态审查未发现本轮明确缺陷；主线程复核实际 diff、确认已删除符号无剩余调用。
- PhoneLibraryUiTest 4 项：1.3 倍字号/长库名/首屏网格/末行留白、固定筛选条、服务端排序与库内搜索范围、空筛选结果与清除评分、完整壳进入库后隐藏 Tab / 返回恢复。
- PhoneThemeTest 8 项、ResumeCardTest 7 项、FloatingTabTest 3 项；合计 22 项通过。
- 已查看真实 Compose 的库页深浅主题、1.3 倍字号和列表底部截图。图片是测试色块，不是真实海报。
- Android 参数 263 处、字段 202 处检查通过；git diff --check 通过。

## 未验证 / 剩余风险
- 本轮没有真机、真实服务器海报或播放验证，设备系统安全区尚未实测。
- 排序筛选请求形状有测试；分页实现未改，仅做静态复核，没有新增分页续页/终止回归。
- 保留已有未提交工作，不提交或推送。

## 安装包
release 构建通过。手机包 `build/android/app-arm64-v8a-release.apk` 60.14 MiB；TV ABI 包 `build/android/app-tv-armeabi-v7a-release.apk` 56.62 MiB。两个包与构建输出 SHA256 一致，播放库 ELF / ABI 检查通过，v2/v3 签名通过（v1=false），整数 MiB <= 60 门禁通过。

截图与安装包保留在忽略的 build 目录，临时日志已删除。未启动调试服务。任务完成但未提交，留待统一提交归档。
