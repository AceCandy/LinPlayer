# 实施与检查

1. 真实TvRoot回归先证明旧入口不使用共享首页。
2. 接通共享浏览壳、顶部导航和遥控交互，隐藏TV下载。
3. TV播放交接保留参数、停止与末集返回；保留伴侣遥控。
4. 运行相关Robolectric导航/按键/隐私/播放回归并渲染TV图，独立审查后修复。
5. 同步TV设计与平台约束；不出APK，报告设备未验范围。

## 完成结果

- 正式TV入口已复用PhoneRoot/MainShell；顶栏与遥控焦点适配，共用页面隐藏下载。
- 保留TV扫码/目录选择、伴侣和播放器；Route.Player补TV本地文件标记，退出回传实际末集。
- 独立只读审查两轮覆盖浏览与播放。采纳本地播放标记缺失问题；下载旧路由按用户要求不恢复。主线程复核修复与实际本地命令回归。
- 渲染发现并修复TV详情头图过高；首页/聚合/详情使用测试素材输出并人工查看，不是真实服务器截图。
- 79项Robolectric回归全部通过：SharedTvBrowseTest(4)、TvPrivacyTest(3)、PlayerActivityTest(7)、FloatingTabTest(9)、HomeHeroTest(15)、TvFocusTest(36)、TvTrackTimingTest(4)、PlayerLaunchPageTest(1)。命令参数检查259处通过，diff空白检查通过。
- 未生成APK，未做真机遥控/媒体播放/性能验证。旧TV浏览文件仍保留作历史测试，不作为正式入口。
