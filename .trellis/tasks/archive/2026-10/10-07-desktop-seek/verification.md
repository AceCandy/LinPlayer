# 桌面跳转批验证记录

日期：2026-10-07。范围为 Windows/Linux 共用播放器页面；保留既有 Android 工作树，本批未改核心、服务端或绑定。

## 实现与独立复核

PlaybackSeek 集中目标累加、排队合并、回执代数、15秒显示超时和停止屏障。PlayerPage 保留真实进度用于上报/弹幕/停播，显示采用待跳转目标，状态查询不重叠。
只读独立审查已检查页面接线、真实位置隔离、迟到响应、旧页起播/seek/stop等待顺序，未发现阻断问题。审查未执行设备播放，运行验证由主线程完成。

## 已通过

- `dotnet build tools/seekcheck --nologo -v q`：0错误；25警告包含桌面既有弃用/空值提示及Headless引用链中的WebView2 WindowsBase冲突，不将警告数等同于新增产品问题。
- `dotnet run --project tools/seekcheck --no-build -- build/pack-linux/LinPlayer/liblpcore.so`：纯状态与真实Avalonia页面控件入口通过。使用受控发送回调；不是实际mpv seek。
- 状态断言覆盖三次+10从40到70、只发送50/70、未提交/缓冲/旧代数不能确认、落点确认、零点/时长钳制、14999/15000ms超时、无效输入、旧失败隔离、当前失败释放、停止失效队列并等待在途seek、重复停止共用回执。
- Headless真实按钮、方向键与进度条指针松手接线通过；目标显示1:10同时真实位置仍40，超时恢复0:40。
- 反向验证：临时把相对基准改回actual，回归非零退出并报告“连续三次+10未累加到70秒”；finally还原源码后重建，状态和页面回归再次通过。最终Linux核心库亦用于一次全量回归。
- `check-style.sh --summary`：7项全过。环境只提供python3，以shell函数映射python运行相同检查，不改判据。
- 已跟踪和未跟踪源文件隐私门禁通过。首次未跟踪扫描包含测试编译目录的依赖文档；新增bin/obj忽略规则后重查通过，最终删除本批测试工具编译产物。
- `git diff --check` 与任务jsonl validate通过。
- 标准 `scripts/pack-linux.sh` 成功：系统libmpv动态加载约束、图标、版本2.0.0-dev、核心命令冒烟均通过。
- `scripts/smoke-linux-gui.sh build/pack-linux/LinPlayer 20`：完成框架初始化并存活20秒；timeout关闭。已删除冒烟生成的userdata。
- zip与暂存目录的外壳程序集/核心库内容相同，包内无userdata或开发头文件。

## 交付

`build/pack-linux/LinPlayer-Linux-v2.0.0-dev.zip`

64594583字节，SHA256：`60609a137103165000708bc83e60a30d004ccce0b40cc8a3bbb251a73fdc0abd`。

## 未验证与剩余风险

- 未构建/运行Windows包，未验Windows窗口与视频子窗口；未做真实显卡、真实本地/Emby视频精确落点和快速换片验收。
- 未测TV；不以本批桌面结果替代上一批Android真机验收。
- 原生命令永久无回执时，仅UI目标超时释放，停止和下一页起播继续等待。不能取消不可撤回命令或提前放锁，否则旧seek可能作用于新媒体。
- 1.5秒状态容差不能证明视频帧已呈现。
- 编译成功与Xvfb开窗只证明各自覆盖的层级，任务保留in_progress待设备验收；本批未提交、推送或归档。
