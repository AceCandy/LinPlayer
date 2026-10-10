# 验证与审查

- 最终相关Gradle回归104项全通过：BrowseCache3、首页18、库11、搜索/浏览34、详情缓存20、详情选项12、悬浮导航5、筛选重置逻辑1。
- 真实故障注入：禁用浏览磁盘读完后的展示回调，diskCacheAppearsWhileRefreshIsPendingAndFailureKeepsPosters红测；恢复后绿色。无论网络是否已完成均验证缓存先出现与刷新指示，失败保留内容。
- recordRoborazziDebug生成本轮首页、搜索浅/深色大字、库冷加载、电影详情浅/深色截图，主线程检查；测试图像是fixture，不代表真实海报/真机帧率。测试截图查看后清理。
- Android参数门禁256处通过；git diff --check通过；隐私门禁扫描所有改动文件未新增地址/凭据。
- 两轮探索/独立审查完成。已修：切会话拒收、缓存旧写入不可覆盖新快照、同条件重试取消分页。缓存状态只允许服务端响应的临时展示快照，不能作为播放/跨服合并/回写数据源；因此保留服务端上次显示的resume/played字段，避免缓存卡片几何突变。此边界与详情缓存的元数据白名单不同。
- MediaStationGo只读核查：5ea965a、工作树clean；Items查询接受PersonIds/IncludeItemTypes，分页契约测试包含Person。未修改或启动服务端，未做真实账号联调。
- Yamby只有APK和历史录屏研究可核验；本次冷占位、详情头部/图标、搜索芯片无精确参数证据，属于按用户指定方向的近似设计，不能声称像素级复刻。
- 未验证：真机键盘/安全区/掉帧、真实服务端冷/热首屏耗时与人物数据、TV。手机正式包验签信息交付后补充。

- 缓存新入口未被原参数门禁覆盖，补充browseBlock和命名params识别；assert自检覆盖直接args及两个命名params分支，门禁现检查256处。
- 手机正式包80,404,452 bytes，SHA256 757c0834e4142c23aa0fdbbd987e26e8340427467aec9abac63670ad811750f7；最终目录与Gradle输出逐字节一致；v1/v2/v3验签通过；13个ARM64 ELF包含libmpv和liblpcore。TV包未更新。

## 本次追加：剩余接口骨架（2026-10-09）
- 根因：收藏总览/分类及Facet仍调用两套12格闪烁网格；历史/下载/本机浏览有自定义闪烁条，BlockBox默认120dp骨架影响搜索人物弹窗、数据源收藏、发现与插件管理页。
- 改动：将库页箭头提取到Base.LpRefreshIcon，LoadingState复用；移除两套失去调用的网格骨架，默认BlockBox替换成同高轻量等待区；手机宿主插件空树等待采用该反馈，TV及插件主动声明Skeleton不变。启动闸口轮廓与首页静态封面保留。
- 回归：新增挂起收藏请求浅色总览/深色分类用例，修改前2项在缺失刷新节点断言失败；修改后通过，释放网络后真实收藏出现、等待图标撤销。
- 相关回归70项通过：PhoneBrowseUiTest36、PhoneLibraryUiTest11、PhoneDiscoverUiTest4、PhoneManagementUiTest6、PluginSystemTest5、PluginAnchorTest3、PluginUiFocusTest5。
- 渲染：recordRoborazziDebug真实输出两张收藏等待图，已查看，标题/操作栏与中心刷新正常，无旧网格。普通测试不作为截图生成依据。
- 独立复核：主线程完成后单独检视差异、各调用者与尺寸；仅Loading分支变化，空态/错误分支及请求未改。共享插件入口显式保持TV分支。只读审查代理未及时返回已停止，其未返回结论不计入通过证据。
- 门禁：256处Android参数检查通过；diff --check通过；54路径隐私门禁无新增敏感内容。
- 未验：Android真机、真实账号慢网络、实际帧耗时；未声称像素级复刻Yamby。本轮未启动服务或模拟器。
- 最终手机APK：build/android/app-arm64-v8a-release.apk；80388070 bytes；SHA256 89b0217d36df4adf15fc78fa011d92f5a7d4aa12650341646f35dca43194fbfc；交付文件与Gradle输出一致，13个ARM64 ELF含核心/播放库，v1/v2/v3验签通过。TV包未更新。
