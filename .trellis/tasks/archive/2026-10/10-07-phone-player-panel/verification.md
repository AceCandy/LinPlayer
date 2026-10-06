# 验证记录

## 变更与边界

- 只修改手机 PlayerPanel：整季逐页显示、当前集到达后定位一次、失败页保留列表并续取；MPV 音轨/字幕读取失败显示中文提示和重试。
- 请求与临时状态按面板/条目/实际 Media3 实例隔离，取消不变成空结果。字幕关闭、中文语言呈现与切轨命令保持；换集仍通过页面目标回调，由控制器收尾并起播。
- 不改 Go、生成绑定、依赖、Media3/MPV 解码与自动回退，也未重做 TV。
- UI_MOBILE §8.3/§8.8 与 frontend 规范同步；临时状态变更的测试必须等待 Compose 组合更新后再释放旧请求。

## 故障与回归证据

- 原生产代码下新增的 4 个用例全部失败：长季不续页，失败页无重试，轨道失败无错误反馈，旧轨道失败覆盖新面板。原有语言/选择用例仍通过。
- 修复后 19 项相关回归通过；独立复核后补强重试滚动和目标切换/离页时序，最终 20 项通过：PhonePlayerPanelTest 6、SeasonEpisodesTest 3、PhoneEnginePlaybackTest 1、PlayerControllerTest 10。
- 第80集定位在第120条之后的请求仍被门闩挂起时验证；手动滚到第100集后释放末页，第100集仍可见且第80集离屏。第143集可选，目标回调收到对应ID，面板未直接调 player.play。
- 当前集在首批内时，失败页重试保留手动滚动位置；游标精确为 0/40/40/80。
- 换目标后新季游标从0开始，旧目标的非协作式响应不覆盖新列表；移除面板并等待组合移除后释放新响应，没有继续取第80条之后的一页。
- 额外反向注入移除 episodes 的 remember(kind,itemId) 隔离键，目标切换用例明确失败：期望新季游标0/40，实际仅40。注入已恢复，最终回归在恢复后通过。
- 补强用例曾在组合更新前释放旧响应，导致调度超时/离页前续取；修正等待顺序后通过。这是测试时序问题，未追加产品逻辑。

## 独立复核与门禁

- 两轮独立只读复核覆盖加载/取消/目标回调与测试断言。首轮提出的重试复位、条目列表串用疑点经状态键/positioned守卫及补强回归未成立；末轮未发现阻断问题。
- 真实 Compose 组件渲染已记录并查看音轨错误与末集面板；既有音轨/字幕正常态深浅主题、1.3倍字号回归通过。图片位于忽略的Android build目录。
- Android 参数门禁255处调用通过；任务上下文清单验证通过；git diff --check通过。
- 同一隐私扫描器覆盖1459个已跟踪及未跟踪文件，没有新增真实地址/凭据，不改存量基线或豁免。

## 安装包

- `bash scripts/pack-android.sh`成功；最终手机包为 `build/android/app-arm64-v8a-release.apk`，63098072字节，SHA256 `e022468164e244b8bd11c6075f64e5af27f5b740dc3783a414e708d097710984`。
- 最终包与本次Gradle产物摘要一致；仅arm64-v8a，13个native库均为ARM64 ELF，包含liblpcore.so和libmpv.so。
- apksigner检查v1/v2/v3均有效（检查minSdk23以覆盖v1，应用minSdk仍为24）；未输出签名材料。
- 版本沿用2.0.0/versionCode20000，TV包未重新构建。安装包及截图保持忽略；本次未启动adb或其它调试服务，Gradle均使用单次进程，结束后停止。

## 未验证与剩余风险

- 未安装本次手机包、未播放真实媒体或与MediaStationGo实际联调；Robolectric和替身结果不代表设备解码、首帧或真实网络恢复表现。
- 服务端参考MediaStationGo 97e1cc5，无已跟踪工作树差异；核查通用Users/Items的ParentId/StartIndex/Limit与核心映射。复用已有分页命令，不改服务端。缺总数等既有核心兼容边界未在本任务扩展。
- 目标切换、离页及面板切换已回归，实际Media3实例切换依赖状态键静态复核，没有单独新增该实例变化的设备测试。
- TV真机验收继续暂缓，不记为通过；未启动调试服务。

## 提交范围

单一本地工作提交：`fix(android): 补齐手机播放面板选集与错误重试`。

- apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/PlayerPanel.kt
- apps/android/app/src/test/kotlin/xyz/linplayer/app/PhonePlayerPanelTest.kt
- docs/go-migration/UI_MOBILE.md
- .trellis/spec/frontend/android.md
- .trellis/spec/frontend/hook-guidelines.md
- .trellis/tasks/10-07-phone-player-panel/prd.md
- .trellis/tasks/10-07-phone-player-panel/task.json
- .trellis/tasks/10-07-phone-player-panel/implement.jsonl
- .trellis/tasks/10-07-phone-player-panel/check.jsonl
- .trellis/tasks/10-07-phone-player-panel/verification.md

用户已确认按上述范围本地提交，不推送；安装包及截图不入仓。
