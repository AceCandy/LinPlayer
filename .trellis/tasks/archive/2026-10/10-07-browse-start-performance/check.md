# 检查与交付记录

## 修改与证据
- 首页、媒体库与详情未发现需要修改的重复网络请求；首页只补请求计数与非阻塞回归。
- 旧实现的真实挂起 PlaybackInfo 回归先红：取消后旧预热替换正式代理；修补后转绿。
- 预热拥有独立可取消 context，离页、新详情、正式起播和 Stop 取消旧请求。正式 PlaybackInfo 不合并，预热量不缩减，共享代理缓存保留。
- 独立只读审查发现取消检查与发布句柄的竞态窗口，已使用 warmCancelMu 保护发布。最终人工核查：proxyMu → warmCancelMu；取消方先释放 warmCancelMu 再等待 proxyMu，不存在反向同时持锁。

## 已验证
- PhoneHomeRefreshTest：17 项，零失败。权限与插件栏目请求同时挂起时可进入媒体库；views/resume 各一次，NextUp/random 为零。
- Go player、net/preload、net/prefetch：race、count=1 通过；追加离页/正式起播/新详情三种取消入口回归及取消后的缓存句柄复用。
- 最终代码完整 scripts/check-core.sh 十关通过，包含 20 条黄金差分。
- Android 参数对账 255 处、任务上下文校验、git diff --check 通过。
- 统一隐私扫描覆盖 tracked + untracked，无新增地址或凭据；既有 baseline 未改。
- Linux 新包通过脚本自检：核心依赖、图标、版本、命令行核心调用；不代表桌面渲染或真实播放通过。
- 手机新包 63098078 字节，SHA256：2d6130f2f94a68fe90c8294ec08b21a97f2ba3a066dc1374121e63b49b7a0806。与本次 Gradle APK 字节一致，liblpcore/libmpv 为 ELF64 AArch64；apksigner minSDK23 验证 v1/v2/v3 通过。

## 证据边界与剩余风险
- HTTP 回归验证真实取消后的旧流不启动、活动代理不被替换；HTTP 客户端可能在服务端响应写回前已因取消返回，不宣称覆盖忽略取消且成功解码的第三方取流实现。
- 未做真实服务器联调、手机首屏/首帧计时、TV 真机播放、Linux 图形渲染或 Windows 出包。不能把单测耗时当作性能收益。
- APK 当前为未提交工作树构建，壳内版本提交号仍来自上一提交；不会冒充本轮提交构建。
- 未提交、未推送，待具体清单确认；旧 TV 刷新率任务继续保留待真机。

- TV 新包 59421426 字节，SHA256：aae863330f157edd272693a9707e748b017453b7fc64c003e4c117e86b32861f；与本次 Gradle APK 字节一致，liblpcore/libmpv 为 ELF32 ARM，apksigner minSDK23 验证 v1/v2/v3 通过。
