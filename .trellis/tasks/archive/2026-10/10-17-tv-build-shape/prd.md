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

## 最终交付验证
- 修复提交b0f7134b；TV和手机使用独立构建配置，实际APK哈希不同。TV Manifest标记true（0xffffffff）、手机false（0x0），脚本实际marker校验均通过，独立审查待核实项已排除。
- 两份arm64-v8a包apksigner v2/v3实际验证通过，全部so为ELF64/AArch64且压缩；libmpv与本次liblpcore一致性通过。新旧签名证书一致，可覆盖保留数据。
- TV文件LinPlayer-TV-2.0.0-b0f7134b-arm64-v8a.apk，手机文件LinPlayer-Phone-2.0.0-b0f7134b-arm64-v8a.apk，均在build/android；此前错误命名的b67f2a0f独立TV副本已移除，基础路径已替换为正确构建。
- 测试合计51次：TV构建48项、普通构建3项。未在用户模拟器安装，也未做实际设备播放/性能验收；不把入口判断回归当作用户设备验收。
