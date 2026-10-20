> 2026-10-20：用户已确认将本任务后续工作并入[跨端统一与 tvOS 对齐及验收](../../../10-18-unified-product-completion/resume.md)。本任务按“后续工作已转交”归档，未勾选验收仍未通过；以下保留历史证据，后续以合并任务的交接说明为准。

# tvOS 续作入口

## 当前停点（2026-10-18）

- 目标：对齐现行 Android TV 共享手机页面，不是重新做最小客户端。用户已授权继续实现、开发签名和真机更新；未授权提交、推送、发布。
- 最新源码已完成横幅容器/渐变、自绘卡片焦点和播放/详情按钮，以及设置二级页、多线程、主进度服、媒体缓存和隐藏库海报。详见 implement.md 末节。
- 最终 check-tvos-ui.sh 已通过（全源码 warnings-as-errors、320 命令与 Swift 回归）；预取保存误清其它账号的故障注入已检出；独立复核已做。没有新源码变更时不重复这些步骤。
- **本轮视觉/设置版已完成签名、严格验签、更新安装和启动命令检查**。保留原应用数据；远端 tvOS 源码与本地逐文件哈希一致；正式包已同步本地并核对内容、权限、符号链接一致。签名预检已通过，不要再次让用户解锁或重新配对；下一次真实预检失败时再按错误分支处理。
- 先前用户 GUI 解锁后 SSH 仍报 errSecInternalComponent；登录钥匙串在搜索列表且开发身份存在，显式指定钥匙串也失败，show-keychain-info 报 User interaction is not allowed。请用户在其终端交互执行 `ssh -t 29 'security unlock-keychain ~/Library/Keychains/login.keychain-db'` 后，本次继续重试成功。已证明本次会话可签名；不要推断所有未来 SSH 会话永久可用，也不要把交互解锁当作所有同码错误的唯一修复。
- 下一步：真实电视核对横幅上下融合、媒体库卡片焦点白板、播放/详情主次和设置二级页遥控可达性；按反馈继续改。A1–A14 整体验收仍开放，安装/启动成功不等于 UI 或真实网络设置已验收。

## 最新双图反馈后的版本

最新版本已签名、严格验签、更新安装并启动，原应用数据保留。首页改顶栏覆盖背景、单层侧边距；海报250×375、续播400×225、横幅650点，全部网格同步。顶栏采用自绘按钮样式以处理照片中仍出现的白底；收藏页漏用系统card的差异已在独立复核后修正。列表新增真实 `rating` / `DoubanRating`、明确未看集数、续播剩余时间。最终 Swift 检查/回归及豆瓣字段名故障注入通过，隐私扫描通过。

下一步应核对本次电视照片，不再重复上一版改动。重点：顶栏白板是否确实消失，背景是否延伸到顶部、侧边留白/遥控焦点可达性、海报尺寸和角标可读性。不能以编译或安装成功替代视觉验收；轮播及其它功能对齐差距继续开放。

## 当前轮播/滚动续作（源码通过，签名再次阻塞）

本轮最终 check-tvos-ui.sh、独立复核、回归和隐私扫描通过；pack 同连接私钥预检再次返回 errSecInternalComponent，尚未签名/安装本轮版本。已安装的仍为上一版沉浸/评分版，勿混淆。下一步请用户在其终端交互解锁后重试签名，勿重跑已通过检查。

已实现5秒/手动轮播、横幅焦点/滚动/离页/后台暂停；顶栏聚焦切栏（服务器例外）；移除MediaRow每次出现的scrollTo与焦点写入，固定轨道高度并防重复载入；详情补评分/日期/标语/分级与展开简介。用户尚未具体说明详情差异，不能视为全量对齐。缓存只共用核心命令，**tvOS未消费Go displaycache partial**，Android宿主浏览/详情/heroPool缓存没有直接复用，详见implement.md最新节。

## 最新详情/缓存接线（优先于上文历史状态）

最新源码已接Go展示快照：CoreClient每请求partial流，MediaAPI校验身份/取消；首页/详情先显示核心缓存再用实时结果更新。详情presentation与实时操作state分离，网络错留资料、失效错清展示。不新增tvOS磁盘缓存；Android宿主缓存与其它tvOS页面仍有差距，勿再笼统说“全部共用”或“完全未接partial”。

详情新增季海报/统计、横排比例分集卡/本集名与时长、媒体大小码率/音轨信息、多线路入口；音轨精确预选与完整季集菜单仍开放。已完成独立复核、最终Swift检查和新增缓存/季统计回归；当前尚未签名安装（前轮私钥受阻，用户明确先做详情）。不要重复请求解锁，待进入安装步骤时按既有预检恢复。

## 已确认的操作入口

SSH 使用已有别名 `29`，代理 socket 为 `$HOME/.ssh/linplayer-agent.sock`。地址、密码、证书身份、团队和设备 ID 不写入文档。远端项目工作目录为 `~/Developer/LinPlayer/.toolchain/tvos-formal-check/`。

远端环境：`DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`，PATH 前置 `$HOME/Developer/LinPlayer/.toolchain/go1.27.2/bin`，已有离线依赖使用 `GOPROXY=off GOSUMDB=off`。tvOS 源码用 rsync 同步 `apps/tvos/` 到同路径；其它文件只按实际修改同步。当前源码已同步，勿覆盖远端工具链。

- 检查入口：`bash scripts/check-tvos-ui.sh`，不生成 app。
- 正式包：`bash scripts/pack-tvos.sh`，运行时提供 `LP_APPLE_SIGN_IDENTITY` 和 `LP_APPLE_TEAM`。
- 身份：从 security find-identity 读取唯一 Apple Development SHA1；Team 取已有有效应用描述文件的 TeamIdentifier。验证有效期、DeveloperCertificates 包含当前证书、application-identifier 匹配；绝不能用证书名称括号后缀当 Team。
- 包位置：`build/tvos/LinPlayer-tvOS-v${VERSION}.app`。当前目录里的旧包不能当作本轮版本。
- devicectl JSON 用临时目录保存，内存选择唯一匹配 appleTV/tvOS27 的设备；更新安装后 launch --terminate-existing，不卸载、不清用户数据。
- 输出仅保留操作结果，不输出设备 JSON、签名身份或媒体凭据；临时导出/日志自动删除。
- 原有 `.toolchain/tvos-before-alignment.app`、`.toolchain/tvos-before-visual.app` 为备份，不覆盖。

## 不要重复走错

1. 产品基准：TvApp → PhoneRoot(tv=true) → ui/pages；旧 tv/SettingsPage.kt 不是当前共享设置入口。
2. 原生 SwiftUI 不能直接复用 Compose 视图；复用分组、语义、Go 配置及命令，并核验 Aether 真正消费。不能只保存无效开关。
3. 多线程参数嵌套 settings；服务器使用规范账号 server，不是线路 URL。局部更新媒体预算，保存当前服时保留其它服。
4. 主进度 retry 成功信封仍可能有 error；显示待同步/冲突，不能提示全部同步成功。私密空间隐藏跨服入口。
5. 横幅原本已有渐变，当前修改针对尺寸和合成，根因仍待真机验证。安装/启动返回0不等于视觉、遥控、播放验收通过。
6. 尚缺片头片尾消费、弹幕、插件宿主、第三方账号、备份/文件/网页承接等；网页方案仍未定，不默认为删除。
7. 工作树有大量其它端/核心既有改动；仅处理本任务，不提交、不清理别人的文件。不为接续任务重做配对、账号登录或依赖下载。

详细证据：implement.md、research/settings-current-inventory.md、research/product-alignment.md；通用构建规则：../../../../spec/shared/build-release.md。
