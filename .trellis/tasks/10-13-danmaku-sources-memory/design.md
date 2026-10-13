# 设计

行为缺口位于 core/danmaku 的源配置、自动匹配和 relay；三端设置与播放界面负责交互。SourceConfig 增加 disabled（旧记录默认启用），官方使用不含凭据的描述符参与排序，执行时注入凭据。源配置变更使缓存与记忆的指纹失效。

增加 resolve 返回 items 与 match，避免读取全局 lastMatch 的竞态；保留旧命令。手选记忆存独立原子 JSON 文件，键散列服务器、用户、媒体身份，不保存鉴权。分集接力读取真实列表匹配集号，未知身份不持久化。自动逐源限时，手动搜索分组并发。

增加 testSource、forget；播放器 offset 只在当前弹幕挂载有效，排版及热度时间统一偏移，原始数据不累加修改。宿主取消旧操作并校验代数，防迟到覆盖。

必要文件：core/danmaku、paths、player及回归；COMMANDS 与生成绑定；Android 设置/播放和 TV 对应入口；桌面 SettingsSections/PlayerPage；UI 正本对应小节。独立复核后运行相关检查，不动其它业务。

## 服务器弹幕叠加

MSG `/emby-ext/v1/capabilities` 增加 `danmaku:{enabled,format:"bilibili_xml"}`。能力路由保留认证与活跃账号校验，按实际发现权限声明榜单/日历/匹配；其余发现路由保留发现权限拦截。客户端 emby 包按能力和当前身份实时取同源 raw，不缓存、不跟随重定向；danmaku 包严格解析 XML 并与现有匹配并行汇合，最终统一过滤、去重、排序。部分失败随结果 warnings 返回，来源显示叠加状态；全部无数据且存在错误才报失败。桌面补齐会话令牌传递。
