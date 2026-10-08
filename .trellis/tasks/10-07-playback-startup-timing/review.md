# 独立复核

两轮default只读审查，主线程按出处核查。

第一轮指出remember缺origin键可能复用closed测量。核查：controller以route目标/版本构造，起播effect与测量同key；正常已提交切换必产生新controller。第二轮确认一般路径结论不成立，因此未盲目增加origin key，避免等待stop阶段仅更新起点就重建测量。交错调用的真实起点归属风险通过局部捕获switchedAt、stop后同route提交解决。

同一Compose提交内多个程序化切换最终回到原目标的极端并发未验证；该时序也可能让原控制器/起播effect不重启，未找到可触发生产链，本批不重构播放控制。数据源分支由PlayerController(if(route.src != null) "mpv" else initialMode)强制MPV，仅请求阶段符合设计。

自主复核发现加载前旧媒体错误不能终止新尝试；播放器错误只在本次load后接受，命令异常仍可终止请求阶段。补充真实listener注入错误回归。未发现其它阻断。
