# 实施和验证

1. 先加聚合首次失败/刷新失败保留数据回归，旧实现应失败。
2. 共享筛选chip；分别调整搜索、收藏和聚合布局，更新UI正本。
3. 运行PhoneBrowseUiTest、PhoneLibraryUiTest、FloatingTabTest、PhoneThemeTest、ResumeCardTest，检查截图。
4. 独立审查，命令参数/字段检查和diff检查；release构建、ABI/ELF/签名及归档。
5. 记录已验/未验与风险；不提交，清理临时日志。
