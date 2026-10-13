# 自动使用服务器弹幕：契约核对与待定行为

用户要求：无需配置，通过能力查询发现服务器弹幕能力，自动请求 `/api/danmu/:id/raw`。

## 已核实

MSG HEAD `f593cf7`，本次未修改服务端；其未跟踪 core 文件未读取或触碰。

- `internal/handler/emby_routes.go`：GET `/api/danmu/:id/raw` 和 `/emby/api/danmu/:id/raw`，Emby 认证及当前用户权限。
- `internal/handler/emby_static.go:164`：返回 XML，Cache-Control no-store；非红果返回200空正文。不可见/不存在红果条目404，查询或获取失败500。
- `internal/service/hongguo_danmu.go:220`：id接受红果公开分集ID或实际媒体文件ID，只处理当前账号可见叶子。
- XML根i，d节点的p为时间秒、模式、字号、颜色等B站XML格式，非弹弹Play JSON格式。合法空XML与空正文均应视为无弹幕；损坏XML不能伪装为空。
- `internal/handler/emby_ext.go:18`：GET `/emby-ext/v1/capabilities` 已存在，声明榜单/日历/匹配，尚无弹幕字段。整组受can_view_discover限制。
- `/Sessions/Capabilities` 是客户端能力上报，返回204，不能用作服务器功能发现。此前只读探索遗漏ext能力接口，主线程已用真实源码纠正。

## 建议的最小接入

1. MSG capabilities增加独立的弹幕描述符，例如danmaku.enabled、format=bilibili_xml、item_id=emby。能力查询保留登录/有效账号验证，发现内容端点继续单独检查can_view_discover；具体字段以批准后的实现为准。
2. LinPlayer共享Go自动流程读取能力，未声明则不猜测或请求raw；使用固定同源raw路径和当前Emby令牌，不跟随跨站跳转，保留反代前缀。响应遵守no-store，不将此数据放入长期第三方弹幕缓存。
3. raw调用前检查取消，回包继续沿用播放器代数防止旧片覆盖；关闭弹幕时不请求。
4. 桌面resolve目前只传server/user，不传token；需补会话令牌或直接使用有效server_id。Android已有固定会话快照。
5. 回归覆盖能力缺失/关闭、当前item路径、认证、反代前缀、空XML、乱码/非法XML、401/403、取消以及不泄露重定向凭据。

## 已确认并实施

用户明确与其它源叠加。MSG 已增加独立弹幕能力，能力查询与发现内容权限分离；LinPlayer 共享核心并行汇合自动/手选源与 raw，部分失败返回 warnings，宿主显示部分失败。以上“已核实”为实施前基线；本轮验证见 verification.md。
