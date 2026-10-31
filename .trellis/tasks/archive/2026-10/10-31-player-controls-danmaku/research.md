# 现状与参考

- 手机独立按钮底色集中在PlayerPage.PlayerControl，倍率另有共用胶囊；点选原有16档和200dp宽度。本轮保留点击区域、按压反馈和上下渐变，点选136dp宽。
- 桌面原EpisodeDrawer同时承载选集、章节和插件，移除选集后改为PlayerDrawer。手机、TV旧插件episodes请求均明确拒绝；SDK旧字面量保留但注明不支持。
- 弹幕自动加载已经由Android startDanmakuFor和桌面StartDanmaku触发，仅开启时执行。core/danmaku/resolve.go仅尝试排序首项；新增TestResolveSkipsEmptyCandidateWithinSource已在旧代码复现首空次有却不加载，修复后通过。未取得实际故障源和媒体证据。
- Yamby参考使用10-13-msg-extension-client/research/yamby-danmaku-runtime.md中的既有实测：源新增验证、排序与持久化。Hills参考同任务hills-danmaku.md中的静态文案：手动匹配记忆优先；模拟器运行曾失败，不重复安装或声称已观察其播放行为。
- 用户已明确倍速档位和加减规则，直接实现本次要求，不将参考端旧档位覆盖当前需求。
