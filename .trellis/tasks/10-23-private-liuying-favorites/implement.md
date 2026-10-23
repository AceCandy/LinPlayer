# 实施与验证

1. [x] 更新PhoneLiuyingTest：先断言精简海报网格、默认/覆盖列数、图片请求、点选后循环和正常随机续页，原实现下应失败。
2. [x] 改PhoneLiuyingFavorites/Sheet及调用者；PhoneLiuyingPager只接选中的单条收藏，下一候选使用正常随机查询，保留取消和停止屏障。
3. [x] 运行针对性PhoneLiuyingTest/PhoneLiuyingChromeTest、Android参数检查；记录合成截图并独立复核，不编译安装包。
4. [x] 同步手机规范/设计正本最新行为，记录已验与未验；不提交或归档。

## 审查后补修：收藏与随机候选重合

- [x] 混合随机页加入当前收藏ID，新增仅重复页续取、无其它候选停止请求回归；修复前3项均失败，分别复现重播当前收藏、未继续取页及单项库再次播放。
- [x] 手机首轮随机排除点选ID，按原始nextOffset续页；过滤空页继续同轮请求，无后续停止。不改变收藏状态、服务端或界面。
- [x] 修复后PhoneLiuyingTest 54项、PhoneLiuyingChromeTest 3项、TvLiuyingTest 2项共59项通过；269处参数检查及git diff --check通过。
- [x] 独立只读审查后主线程核验分页、重试、取消及单项库。审查提出原始空页has_more=true可能死循环，实际more还要求items非空，因此不成立；服务端违反offset契约并持续返回重复非空页不属于当前已核实的服务端行为。
- 未验证真实服务器封面、真机解码/手势或性能；未打包、提交。
