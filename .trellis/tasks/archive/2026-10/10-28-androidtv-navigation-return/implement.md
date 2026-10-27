# 第一阶段实施

- [x] 明确产品边界与证据，完成 PRD 收敛。
- [x] 最终方案确认后激活本子任务。
- [x] 完整读取即将修改函数与调用者，检查工作树。
- [x] 先复现根页返回保留、退出计时隔离和详情退一级，修复并回归。
- [x] 复现剧集下行再上返，补充首屏几何断言，修复范围缺口。
- [x] 复现收藏切首页和异步头图插入，修复切页恢复；重验详情返回保留位置。
- [x] 四种首页组合、多卡、弹层、私密与禁用后恢复焦点回归。
- [x] 独立复核 diff 与实际渲染，更新任务验证记录和适用规范。

## 验证命令
项目工具环境下在 apps/android 执行：
`./gradlew :app:testDebugUnitTest --tests '*SharedTvBrowseTest' --tests '*TvPrivacyTest' --console=plain`
根据新增测试类补相应过滤；需要截图时添加 `-Proborazzi.test.record=true`。运行 `python3 scripts/check-android-args.py` 和 `git diff --check`。不运行 assemble，不宣称 JVM 结果代表真机连按。

## 收尾状态
实现及本地回归已完成，测试证据、未验项目见父任务 `verification.md`。保持待提交状态；本轮未出包、安装、提交或归档。
