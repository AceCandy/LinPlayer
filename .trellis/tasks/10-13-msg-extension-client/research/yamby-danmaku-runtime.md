# Yamby 多弹幕 API 源运行核查

## 依据与环境
用户提供的 `docs/cankao/Yamby-v2.1.0.11-arm64-v8a-release.apk`（versionName 2.1.0.11，versionCode 20010012）。复用已有 API30 Google APIs x86_64 镜像，在普通用户、仅 KVM 设备、无外网的临时容器启动。原 APK 安装并运行，实际启动入口为 `com.hush.yamby/.default_icon`。未使用真实账号。

## 已实际操作
- 设置 → Danmaku → Danmaku API（Add and manage danmaku API）。初始显示一条标记 Default 的弹弹Play源。
- Add API 弹窗只有 URL 输入项。点击 Add 会先显示 Verifying，不是直接保存。
- 用容器内模拟 HTTP 服务成功添加两条不同 URL。捕获校验请求为 `GET /api/v2/search/anime?keyword=test&type=tvseries`；带子路径的基址也可添加。模拟成功响应不能代表真实服务兼容性。
- 新添加条目位于列表前部；各条目有拖动手柄。将默认源从第三位拖到第一位，界面顺序确实改变。
- 强制停止并重启应用，再进入同页，两个自定义 URL 与拖动后的顺序均保留。
- 默认源未显示删除按钮；自定义源显示删除按钮并要求确认。确认后对应条目从列表移除。
- 点击 URL 行未打开编辑或单选弹窗；不能据一次点击推断所有手势行为。
- 弹幕设置另有 Merge third-party items 与 Merge Duplicated Danmaku，两者是不同选项；未验证具体合并语义。

## 结论与边界
用户指出的多弹幕 API 源管理确实存在，弹弹Play属于应保留的弹幕接入。LinPlayer已有的官方源、自定义源配置和签名逻辑维持原样。本轮未修改业务代码、未出包、未提交。
已核对 UI 层级文本及实际截图，验证新增校验、多个源、排序、重启持久化与删除。未验证视频播放时是逐源请求、并行聚合还是失败切换，不把列表顺序说成已证实的请求优先级；未访问真实弹幕服务。

## 本次纠正与查证顺序
此前只检索公开源码，遗漏了用户已经提供的 APK 和模拟器。后续参考应用核查必须先检查当前交付物、`docs/cankao/`、任务记录与已有测试环境；有 APK 时优先运行观察，再按需做定向静态分析。只有现有材料确实不足时才要求用户重复提供链接。

临时模拟器、模拟 HTTP 服务、AVD、ADB状态、日志和截图在核查结束后清理；保留原 APK 和已有 SDK 镜像。
