# 验证记录

## 播放器弹窗（2026-10-13）

- 参考证据沿用research/player-popup-audit.md；本轮未重新运行参考APK。
- 旧音轨面板的重复标题断言先失败，确认回归能检测原行为；改后通过。
- 离线JVM相关回归88条全部通过：PhonePlayerMenuTest 7、PhonePlayerPanelTest 7、PhonePlayerOsdTest 11、PhoneEnginePlaybackTest 45、PhoneDetailOptionsTest 18。
- 覆盖真实Popup的WindowManager位置、横/竖屏与320dp/1.3字号浅色主题、180dp窗口收窄、左勾选、无标题/无遮罩主控保留、插件入口锚点、窗口返回及外点关闭、退出窗口先失去焦点而表面保留/最终移除、关闭取消自动读取与重开读取新轨道。
- 原分页、迟到失败、换目标等竞态保留；PhonePlayerPanelTest的「面板关闭后手选仍排在在途恢复后提交」直接覆盖关闭后释放恢复屏障，确认手选第二轨最终完成。全屏倍率实走player.setSpeed及default_speed保存，play不重启。字幕滑杆未加载禁用、拖动零写、松手一次写、范围和步长正确，其它样式入口保留。
- 实现审查与修改分开：两次独立只读审查指出插件锚点和极窄窗口问题，已修正并补回归；手动弹幕匹配属于用户已提交操作，随播放页完成，菜单自动读取随active取消。
- 组件截图不能可靠呈现绘制层alpha；开合测试证明生命周期，未将其说成逐帧视觉动效证据。参考的精确时长/曲线未测得，本端150/100ms为近似。
- 截图保留在已忽略的apps/android/app/build/player-osd：landscape/portrait-audio-popup.png、landscape/portrait-more-popup.png、substyle-slider-light.png。它们是JVM布局与合成背景，不能代表实际解码/系统旋转/真机动效。
- 参数门禁255处通过；隐私门禁1810文件无新增（410处既有基线不改）；git diff --check通过。
- 未验证真实设备、实际Emby服务器/插件交互、其它内核特有弹窗；原功能保留，不宣称参考所有功能完全一致。
- 最终第三次独立只读复核未发现新阻塞，实际窗口坐标换算、入口注册、窄窗口与退出输入屏蔽已点验；其切轨测试遗漏已按上文明确指向PhonePlayerPanelTest。
- 本轮无安装包、提交或归档；没有启动调试服务。保留此前竖屏片源改动。

## 用户授权出包（2026-10-13）

- 按当前工作树执行 scripts/pack-android.sh，手机 arm64 Release 构建通过，版本 2.0.0。
- 最终交付 build/android/app-arm64-v8a-release.apk，81694577 字节（77.91 MiB），低于 80 MiB 预算；与本次 Gradle APK 的 SHA256 一致。
- apksigner 校验通过：正常平台范围的 v2/v3 签名通过，单独限定旧平台范围的 v1 校验也通过。
- 包内 13 个原生库均为 arm64 ELF；liblpcore.so 与 libmpv.so 均匹配本次 Gradle 剥离产物。
- 未安装到真实设备，真实播放与视觉动效仍待设备验证；未提交或归档，没有启动调试服务。

## 用户授权提交归档（2026-10-13）

- 代码提交 5cd90d06，包含竖屏片源布局、播放器弹窗、相关回归与同步规范。
- 提交前参数门禁255处通过；代码与任务资料22个文件的隐私门禁无新增真实地址或凭据；暂存diff检查通过。
- 归档沿用本轮88条回归、独立复核与Release出包结果；未重复运行测试或重新出包。
