# 验证

- Android 18项通过（PhoneLibraryUiTest 6、PhoneThemeTest 8、FloatingTabTest 4）；首轮新定位/方向断言在旧实现下失败后修复通过。
- 手机深浅色、320dp与1.3字体实际Compose截图复核；无条件时首屏不占筛选行，图标/当前排序/箭头同行，标题长名省略，筛选和库内搜索回归通过。
- 服务端PostgreSQL临时隔离库：TestEmbyLibraryWorkTimeSort旧代码在年份与发布日期冲突时失败，修复后movie/tv/mixed的双向首/尾页/总数通过，实际EXPLAIN确认查询执行并且SQL不含sort_values日期汇总。
- NFO TestNFOLibraryMetadataSorts旧代码失败；修复后三元数据双向分页/总数通过。TestNFOLibraryPagingMatchesHierarchy、TestNFOLibraryPagePlans（十万文件查询计划）通过，原权限/状态/层级/页内查询保持。
- 相关服务回归通过：TestEmbyItemsCountModes、TestEmbyRandomGlobalWithoutExternalCatalogs、TestEmbyRandomSortUsesSeed、TestEmbySeriesDenseCountPlan、TestEmbySeriesPaginationDoesNotProbeFilesForWholeCatalog、TestEmbyFileDateSortQualifiesOnce。HTTP解析TestParseEmbyItemsParamsFields通过。
- MediaStationGo接口目录说明同步；web lint/build通过。两次独立只读审查通过。Android参数262/字段202（240既有放行）和两仓diff检查通过。
- 未验：真机动画/触摸/系统栏、生产服务器与真实大库耗时、更新后的服务端部署、接口目录页面浏览器回归。临时PG与合成查询不能替代生产联调；不宣称速度提升倍数。
- 随机跨请求排序不稳定暂未启用；普通库DateCreated别名合并，NFO首次创建日期未单列。红果缺失日期/年份资料，不声称提供有意义的元数据排序。
- MediaStationGo service/handler go vet通过；release双ABI、核心/播放库ELF、体积、正式目录与源包哈希一致、验签全部通过。
- app-arm64-v8a-release.apk：SHA256 `2c6bf3a48b2677f90fa176fbcf2e21e8e6d7a25007987dc98e5feb7561b77423`；63065306 bytes；ELF 183；apksigner v2/v3通过。
- app-tv-armeabi-v7a-release.apk：SHA256 `4347a6bf15ce8976a77198736d1c8142fdd569278e6def0793bb4b2cdfeedc81`；59372270 bytes；ELF 40；apksigner v2/v3通过。
