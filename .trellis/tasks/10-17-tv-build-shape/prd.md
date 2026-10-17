# TV构建形态与启动修复

## 根因与范围
用户提供了安装b67f2a0f命名TV包后的手机布局截图。此前共用APK改名交付，仅按UiModeManager判断，普通模式模拟器未进入TV；此前验收只检查页面组件和APK包含代码，没有验证构建形态到启动分支。

## 实施
- Gradle构建显式TV_BUILD，TV包优先进入TV，普通包保留自动设备检测与自检入口。
- pack-android支持tv [abi...]，默认tv仍v7a，显式tv arm64-v8a独立构建且保留-tv-命名；不得复制手机包冒充TV包。
- TV构建向核心插件宿主报告android_tv。Manifest记录同源形态标记，出包后核对实际产物。
- 新回归在普通系统模式下验证TV构建，保留普通构建及自检/系统TV行为。重打手机与TV v8a包，验签与ABI、形态检查。

## 验收和边界
先在缺少TV优先级实现时复现失败，再修复通过；独立复核。保留同applicationId、数据与签名，不修改用户配置、不清数据；不改Windows及AppleTV调研。真机播放仍由用户设备验证。

## 验证进展
- TV标记已定义但启动未使用时，normalSystemModeUsesBuildShape明确失败（expected true / actual false）；接入标记后48项TV构建回归全部通过。
- 独立只读审查未发现启动/插件/CI接线阻断，提出产物marker正则需要实包验证；将以实际aapt输出确认。
- CLI默认手机、默认TV v7a、显式TV v8a及显式手机v8a四组实际解析检查通过。
- 普通构建lp.tv=false下3项启动判断回归通过；工作流50块shell与5个构建凭据闸门通过；实际旧APK缺少TV标记被新产物门禁拒绝。
