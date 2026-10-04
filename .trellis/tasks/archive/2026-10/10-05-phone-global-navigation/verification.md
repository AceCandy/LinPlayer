# 验证记录

- 6个测试类共39项通过：PhoneHomeRefreshTest(16)、FloatingTabTest(4)、PhoneLibraryUiTest(4)、PhoneThemeTest(8)、PhoneDetailOptionsTest(3)、PhoneManagementUiTest(4)。
- 缺封面故障注入：临时恢复无条件imageUrl，noPrimaryFlagShowsNameEvenWhenImageServiceWouldSucceed失败；恢复has_primary判断后通过。
- 独立只读审查完成：补横轴主导判断，首页16dp边距改为局部参数；二级搜索结果/详情的实际外层滚动容器已有底部pad。TV无glass/buttonSkin/Layer直接调用。
- 截图人工复核：首页320dp/1.3字号、缺封面库名、深浅服务器菜单、库页大字号单入口、收藏显隐、设置浅色和详情深色面板。
- 参数检查262处通过，字段检查202处通过（240处既有放行），git diff --check通过。
- 尚未真机验收：滚动动画手感、软键盘和不同设备系统栏适配、真实服务器图片与播放。JVM截图使用图片替身，不代表真实海报效果。
- Release双ABI构建通过；正式目录与源APK哈希一致，核心/播放库ELF与ABI匹配，体积符合上限，apksigner通过。
- app-arm64-v8a-release.apk: SHA-256 `9a9768bd81316a0d899d7eb7f9e0c106439e9efad934c8537256fb115842b91d`；63065304 bytes；ELF 183；签名通过。
- app-tv-armeabi-v7a-release.apk: SHA-256 `17f8c24d43b05ea838593c4a4366fd5d3013068b2a81130fc66e61cbc17d32ae`；59372275 bytes；ELF 40；签名通过。
