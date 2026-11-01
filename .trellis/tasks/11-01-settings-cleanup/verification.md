# 审查与验证记录（2026-11-01）

## 已修复的审查发现

1. Windows 换源直接进入新服详情但保留旧会话：切活跃账号后核对新会话、更新外壳并清返回栈。真实 HTTP / FFI 回归在旧点击逻辑下报“换源详情使用了旧服务器会话”，修复后通过；覆盖重复点击和提交中关闭弹窗。
2. 当前服使用备用线路时仍成为候选：先由配置 Resolve 归一到账号主键。新增 aggregate 回归旧实现返回两服，修复后仅返回其它服。
3. Android 换源保留旧服详情返回栈：真实 Compose NavHost 回归修复前返回首页断言失败，重建首页根后通过。
4. Android 提交切服后可关闭弹窗中断交接、会话读取失败仍进入详情：补充两项测试，旧逻辑两项失败；增加关闭门控和目标会话核验、失败清栈提示后，SwitchSourceTest 三项全部通过。
5. Windows 原扩展页的外置补帧安装卡被移入播放设置：按用户追加确认删除整套外置运行时、安装命令 / 事件、设置卡、播放器选项、自检脚本及残留悬停提示。Android OpenCL 和其它增强保留。
6. 插件包删除后 go.mod 仍保留其依赖：go mod tidy -diff 确认全部已无消费者；清理后恢复核心无第三方 Go 模块依赖。

独立复核覆盖核心配置 / 聚合 / 绑定、桌面与 Android 外壳；追加定点复核覆盖补帧平台边界及 Android 异常收尾。修复后的定点复核未发现确定残留。最初指定的外部审查服务两次启动均返回 503，没有将其当作审查通过。

## 通过的验证

- `bash scripts/check-core.sh`：vet、全量 Go 无缓存测试、c-shared、FFI 13 个导出、C# 宿主契约、20 条差分全部通过。
- 清理无消费者依赖后再次执行 `go mod tidy -diff` 与 `go test ./...`。
- `bash scripts/check-bindings.sh`：最新生成产物、C# / Kotlin 编译与 Go / Markdown / C# / Kotlin / Swift 五方对账，231 条命令一致。
- `dotnet build tools/seekcheck -c Release`：桌面与检查工具编译通过，0 error；保留现有 WPF 引用 / 废弃 API 等警告。
- `seekcheck --discovery-only`：真实 HTTP / FFI，榜单分页、周历、实时精确匹配、权限撤销及跨服换源会话通过。
- Windows `GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go test -c ./interp` 交叉编译通过，临时测试二进制已删除。
- `bash scripts/build-core-android.sh arm64-v8a`：核心与 JNI 符号检查通过，只编核心，不生成 APK。
- Android 定向 `:app:testDebugUnitTest`：SwitchSourceTest、PhoneManagementUiTest、TvSearchSettingsTest 通过；追加异常修复后 SwitchSourceTest 三项再通过。
- 前一阶段 tools/listcheck、productcheck、homecheck、privacycheck 构建通过。
- 工作流 50 个 shell 块与 5 个凭据接线、桌面风格 7 项、Android 参数 151 处 / 返回字段 112 处、UI_MOBILE 命令引用 119 条及相关 shell 语法通过。
- `git diff --check` 无空白错误；提交前另执行暂存内容敏感值门禁。

## 已知基线与未验证项

Android 全量此前执行结果为 1079 项、5 失败、2 跳过；以下 5 项已在原基线 `2ff373c6` 同环境复现，未通过改断言或门禁规避：

- PhonePlayerTabletTest.osdAndMenusOnTablet
- SharedTvBrowseTest.focusedNavigationTabShowsPillAndKeepsCurrentLineInLightTheme
- SharedTvBrowseTest.focusedNavigationTabShowsPillAndKeepsCurrentLineInDarkTheme
- TvFocusStyleTest.actualPosterGrowsOutsideItsFrameWithoutWhiteCaptionStrip
- TvFocusStyleTest.sharedFallbackHighlightIsRaisedWithShadowBelow

复现入口：项目 JDK 21 / SDK / Gradle 缓存下执行 `:app:testDebugUnitTest --tests <上述类名.方法名>`；对照基线使用临时工作目录，已清理。本轮最后只重跑受影响范围，不宣称全量全绿。

全仓敏感扫描有未改历史 journal 中的版本字符串误报；本任务不新增豁免，也不改写无关历史。仅移除已删文件的旧敏感基线条目。

未验证 Windows / Android 真机播放、完整设置视觉 / TV 遥控焦点、真实媒体服务器换源、空间切换并发和 tvOS 真机。没有生成安装包、推送、远端部署或删除用户媒体 / 旧组件磁盘文件。
