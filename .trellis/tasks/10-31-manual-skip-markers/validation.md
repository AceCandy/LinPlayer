# 验证记录

## 本次结果
- Go 核心、Android 手机/TV、桌面均接入当前位置标记，默认本季/本集覆盖、区间补标、选定范围清除与旧配置兼容。
- Android 121项回归通过：PhoneEnginePlaybackTest 66、PhonePlayerPanelTest 11、TvFocusTest 35、TvPlayerLayoutTest 9；lintDebug通过。
- Go vet通过；补齐本机libmpv动态库搜索路径后，`go test -count=1 ./...`全量通过。标记作用域、偏移、校验、回滚和兼容均有回归。
- 旧核心实现通过go overlay运行新的作用域测试，在“intro为空，预期20..90”处失败；未改动工作树。手机/TV清除后恢复继承区间的回归，在修复前均于新seek断言失败、修复后通过。
- 桌面及seekcheck编译通过，真实面板→FFI→本地HTTP fixture验证本季默认、当前点保存、刷新不跳转、下一集提示与保留片尾后内容。未生成安装包。
- check-bindings完整通过；生成绑定无需变化。Android参数检查、桌面风格7项、git diff --check通过。
- 本次工作树改动（含新增文件）的红线检查通过。
- 实际查看手机/TV标记面板渲染图；TV遥控行为由真实页面按键测试覆盖。截图保留在已忽略的app/build/outputs/roborazzi/player-skip-markers.png和tv-skip-markers.png。

## 全仓门禁遗留
- check-core初次完整执行有两项失败：本机libmpv搜索路径未配置、既存TV插件贡献点缺失。前者补齐环境后Go全量已通过；后者涉及plugin.anchors/homeItems/homeSections/searchActions/settingsSections/sidebar，不属本次标记功能，未改。
- 全仓check-secrets命中既存`.trellis/workspace/AceCandy/journal-2.md:24`，已确认该文件与HEAD一致。本次未触碰该日志，不在此记录匹配内容；本次改动单独扫描通过。
- 检查脚本重生成的无关插件覆盖表差异已撤回；包含敏感扫描输出的临时日志已删除。

## 未验证与剩余风险
- 未进行真实服务器账号/真实媒体播放联调、Android TV遥控器真机、Windows视频窗口验收；Headless与Robolectric不代替这些验证。
- 同季不同发行版本的片头片尾长度可能不同，提供仅本集覆盖；未自动识别或校正媒体差异。
- 同类标记写入已串行；其他既存配置命令的全配置并发读改写未在本次改造或做压力验证。
- 代码尚未提交或归档。保留此前TV下一集任务的未提交改动，没有打包、安装或发布。
