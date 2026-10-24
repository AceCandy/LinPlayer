# 实施与验证记录

日期：2026-10-25。验证对象为两仓当前任务工作树，不含部署或真机验收。

## 已实现

- 隐私聚合入口改为同一 Row、等宽，保留普通聚合读取前的早返回。
- 榜单前三名改为整卡单一点击，标题、海报、番号及积分均可触发原精确匹配；不新增标题搜索、端点或来源回退。
- MSG 黄果匹配兼容旧混合库和正确 kind 的电影/剧集专用库，保留启用、可见库、ready 投影与真实文件绑定过滤。
- JavRanking 从完整 external_id 显示番号；score 以可空 int64/Long 贯通，0 保留，缺失/null 不补零，不当评分。普通来源评分不变。
- Android 规范、手机设计正本与 MSG 扩展规范同步；UI_MOBILE.md 保留其它任务的既有修改。

## 修复前证据

本任务先建立回归并记录了以下失败，再应用对应产品修改：

- 核心 HTTP→JSON 的 score=0/正数断言失败，原解码与输出未保留字段。
- 隐私入口同行、红果领奖台标题触摸、JavRanking 番号/积分及私密来源标题触摸回归失败。
- MSG typed 库测试仅正确 movie/movie 和 tv/series 组合未命中；真实路由测试也返回空匹配。

## 已通过

### LinPlayer

- `go test ./emby ./bus -count=1`。
- `go test -race ./emby ./bus -count=1` 中 emby 包通过；bus 失败另列，不将整条命令记为通过。
- `go vet ./emby ./bus`。
- `bash scripts/check-core.sh` 十关全部通过：全核心 vet/无缓存测试、共享库、FFI、C# 契约、20条差分语料、插件正反例及 SDK/UI 门禁。初次因环境失败，使用已有本地 mpv-test 运行库、dotnet 和 python3 后重新完整执行通过，未安装或升级依赖。具体环境如下：

```sh
export LD_LIBRARY_PATH="$PWD/.toolchain/mpv-test/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PATH="$PWD/.toolchain/dotnet:$PATH"
export DOTNET_CLI_HOME="$PWD/.toolchain/dotnet-home"
export NUGET_PACKAGES="$PWD/.toolchain/nuget"
python() { python3 "$@"; }
export -f python
bash scripts/check-core.sh
```

- Android `:app:testDebugUnitTest`，指定 PhoneDiscoverUiTest、PhoneAggregateCacheTest、TvPrivacyTest，并开启 Roborazzi 记录：31条，0失败/错误/跳过（17+11+3）。其它任务更新共享组件后再次执行通过，核对执行前后 Kotlin 源码摘要一致。未执行 APK 打包。
- 新增失败反馈回归覆盖 E_NOTFOUND、E_INVALID（多匹配）、E_NETWORK，断言原原因提示、不导航、不切服、不重载及可重试；触摸后需 `waitForIdle` 推进异步解析/消息投递，再等待消息断言，不能只用不推进主线程的 waitUntil。
- `python3 scripts/check-android-args.py`：266处调用参数检查通过。
- 两仓 `git diff --check` 与本轮 Go 文件 gofmt 检查通过。
- 本任务文件的隐私扫描通过；全仓扫描的存量误报另列。

核心门禁会重新生成 COVERAGE.md，本轮仅还原了由门禁产生的无关 D326 行差异，未改动该功能或其它任务代码。

### MediaStationGo

使用本轮专用临时 PostgreSQL 16，随机回环端口；每个测试独立 schema，不连接业务库，不保存 DSN。下列测试均实际执行：

```sh
go test ./internal/repository ./internal/handler -run '^TestExtension' -count=1
go test -race ./internal/repository ./internal/handler \
  -run '^TestExtension(CatalogMatches|HuangGuoLibraryKindsAndVisibility|JavRankingMatches|RoutesAuthCacheAndMatching)$' -count=1
go vet ./internal/repository ./internal/handler
```

- 正确库型、错误库型、隐藏/允许/锁定、禁用、投影错误和无绑定均覆盖。
- 真实注册路由从 library/matches 得到红果合集、黄果电影/剧集、JavRanking NFO 身份，再用同一用户请求标准 Emby 详情，断言准确 Id/Type。
- 成人隐藏时，即使播放配置允许黄果 typed 库，仍不泄露关联。
- 本轮四个 MSG 文件的隐私扫描通过，未新增真实地址或凭据。
- Handler 测试改用正式 database.AutoMigrate 以创建真实详情依赖的展示视图；补齐旧红果图片夹具的合法 owner，未放宽数据库约束或修改生产迁移。
- 临时数据库容器已停止并删除。

## 独立复核与渲染

- 客户端与 MSG 分别只读复核；未发现明确阻塞产品缺陷。主线程抽查实际 diff、字段、SQL、权限入口及精确身份。
- 提交前再次分别审核两仓代码、相关调用方和测试，未发现阻塞问题；共享文档仅暂存榜单段落，不带入其它任务的界面调整。
- 复核中“未见前三名触摸/单次解析、入口 bounds/路由断言”的意见，经定位完整工作树测试确认已有覆盖；工具截断的片段不能当作测试不存在的证据。
- 手机失败反馈原来确实缺专门回归，已补并通过；同时加强普通 rating 保留、JavRanking 不渲染 rating，以及缺失/null 不产生额外战力0的断言。
- 深浅色、320dp、字号倍率1.3的四张模拟渲染图已检查；入口同行同宽，番号、0和11位战力整数可见。长标题沿用两行截断，长积分可换行，无横溢。图位于忽略的 Android build 目录，不入仓。

## 未通过、未验证与风险

- 扩大 race 到 bus 包时，未修改的 queue_test.go 写入测试全局 stall 参数与 queue.watchStall 并发读取产生竞态；两个停滞测试失败。本任务不修改该模块，不把普通全核心门禁通过表述为全库 race 通过。
- 全仓隐私扫描被未修改的 journal-2.md 版本号误判为可疑地址拦截；本轮文件扫描通过。没有新增豁免、重写基线或修改旧会话日志。
- 未在用户实际 MSG 部署、账号权限/能力缓存、真实媒体绑定和手机设备联调。夹具通过不能承诺生产数据必然命中；缺能力、无文件、无权限或多匹配仍明确失败。
- MSG 与 LinPlayer 的改动需要分别发布后才对实际客户端生效。用户已授权本地提交并归档；因两仓存在较多未推送历史，本轮不推送、不部署、不打包。
- 保留其它并行任务修改；不为本任务处理无关测试、样式或详情改动。

## 本地提交与归档

- LinPlayer：`8ed012f4a107278a361ae4b546489b3a2ec3b3cd`，仅本轮8个代码、测试与规范文件。
- MediaStationGo：`7215aea7ee91fea2d8fd9898c89f2cf0412b4e0c`，仅本轮4个代码、测试与规范文件。
- 其它任务先完成了自身提交，两份共享文档剩余差异均为榜单契约，未带入其它任务修改；`10-18-unified-product-completion/resume.md` 原有修改保留。
- 提交前再次核对暂存范围、工作树一致性及两仓暂存差异格式；本轮16个客户端/任务文件、4个服务端文件的隐私检查通过。
- 用户确认本地提交并归档；未推送、部署或打包。测试结论沿用本记录实际执行结果，不将归档或提交视为额外实服验收。
