# 核对结果

- 原 SkipOverrides 以服务器|剧ID保存；setSkipRange整条替换，片尾支持outro_len。新动作由核心合并单段，避免标片头时清掉片尾。
- Emby item已有SeasonId、SeriesId、ParentIndexNumber，无须新增服务端接口。MediaStationGo核对提交24f720fda304fc5462fcf96ba58b7fb42db566f6，相关detail及payload测试文件无工作树差异；未修改该仓。
- 手机PlayerPanel和TV MorePanel没有标记入口；桌面ShowSegments原为输入时长。桌面复用已有顶栏入口，不额外增建更多菜单。
- TV原nextUp出现会无条件撤掉skipWhat；本轮片尾跳过与下一集使用同一右下焦点路径，片尾后有内容时不使用常规末尾提示。
- 独立复核修正：季ID不依赖剧ID、旧绝对片尾回退、保存失败回滚、忙碌时保留TV焦点、保存关闭面板仍保护自动跳、保存后当前有效区间刷新、短后续片段不被容差吞掉。
- 最终复核发现旧已跳过状态会屏蔽清除后恢复的新区间；手机和TV新回归均在修复前于末尾seek断言失败。修复只重置发生变化的区间，保留另一段的已跳过状态。
- 同类标记命令现串行保存；原有其他配置命令的全配置并发读改写没有全局事务，本轮未扩展改造共享配置层。
