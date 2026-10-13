# 验证记录

## 已实现

- 官方及自定义源统一启停、排序；三端自定义新增/编辑/删除，连接测试及折叠鉴权。
- 共享 resolve 单次返回语料与来源，逐源8秒预算，手动多源搜索不被官方限速整体阻断。
- 手选按服务器/账号/媒体及季保存，未知身份仅临时挂载；真实集表接力，不计算分集ID；源内容指纹隔离缓存和记忆。
- 三端显示来源、重匹配、清记忆与时间偏移。核心播放代数拒绝旧挂载，Android 取消旧任务，桌面绑定播放取消令牌。
- 时间偏移重排不累加，换语料归零，热力图统一偏移。

## 验证

- `go test -race ./danmaku ./player`：通过；优先级/故障源回退、账号隔离、非连续分集ID/SP、配置指纹、损坏及取消不覆盖、长ID缓存键、协议测试、偏移重置及旧代数。
- 反向注入：暂时移除核心挂载代数判断，`TestDanmakuGenerationAndOffset` 在“旧播放不得挂载”断言失败；恢复后通过。
- `scripts/check-core.sh`：全门禁通过，含全量测试、出核心库、FFI、宿主契约及差分检查。
- `scripts/check-bindings.sh`：通过，314产品命令与两条调试命令对账。
- Android：`DanmakuSourcesTest`、`PhonePlayerPanelTest`、`TvFocusTest` 通过；源测试失败保留草稿不保存，迟到自动结果不覆盖手选，TV遥控启停和排序及官方保护。
- 手机与TV Roborazzi 图片已目视检查，位于忽略的 app/build/danmaku；这是测试环境渲染。
- 桌面 `seekcheck --danmaku-only`：真实控件与本地HTTP/FFI通过，验证源启停落库、官方保护、手选来源和偏移。
- Android 参数/字段、桌面风格与隐私门禁通过，`git diff --check` 通过。
- 两个独立只读审查：修复长ID缓存键碰撞，并保留既有 pathToken 鉴权类型。主线程进一步复核取消与播放代数边界。

## 未验证与限制

未出安装包，未提交或部署。未使用生产弹幕凭据调用官方或用户自建源，未做手机/TV真机解码播放与Windows/Linux实际视频窗口验收。第三方API兼容和网络配额仍需实际环境验证。新缓存键不复用旧弹幕磁盘缓存，首次访问会重新获取；旧缓存仍可由原清缓存入口删除。保留此前MSG接入工作树。

## 服务器 raw 弹幕叠加（2026-10-13）

- 用户确认与其它源叠加，MSG 与 LinPlayer 均已实施；未提交、未部署、未生成安装包。
- 新增回归：能力缺失/未知格式不拉 raw、根路径与反代前缀、令牌、每次实时获取、不跟随跳转；XML 时间/颜色/转义/大 ID/空值/非法 XML；自动与手选叠加、两路失败隔离、取消不返回可挂载语料。
- go test -race ./danmaku ./emby、check-core.sh、check-bindings.sh 全通过；Android 参数/响应字段检查通过。
- Android DanmakuSourcesTest 通过；桌面 seekcheck 编译及 --danmaku-only 真实控件/FFI 测试通过。
- MSG TestExtensionRoutesAuthCacheAndMatching / TestEmbyDanmuRawRoutes 在临时 PostgreSQL 17 上通过（非 skip）；临时容器已由 trap 删除。web tsc 与两个 API 目录 ESLint 通过。
- 独立只读复核未发现新增问题；复核后仅补充手选/取消测试与文档。
- 未验证实际部署服务与真机播放叠加。桌面已有 Tmds.DBus.Protocol 漏洞警告及 WindowsBase 版本冲突警告；测试退出成功。MSG 当前 raw 仅红果媒体有数据，普通媒体为空。
- MSG 工作树出现其它并行任务的数据库/资料库改动，本轮未触碰。
