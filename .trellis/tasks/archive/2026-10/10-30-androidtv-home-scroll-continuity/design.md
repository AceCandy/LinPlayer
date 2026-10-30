# 技术方案

## 已核实的边界

- Foundation 1.12.0 的 ContentInViewNode / UpdatableAnimationState 已在滚动中更新目标并保留在途速度，不添加方向键队列。
- 原首页每次 Up 新建 animateScrollToItem，连续请求反复重启动画；同起点655px、同160ms，单按剩16px、连按剩329px。
- 原生 TV 的30%停靠取决于 FEATURE_LEANBACK，应用 TV 模式不能保证它生效；同一张续播卡在无特征设备上靠近右边缘。
- LazyRow 内置焦点组和 beyond-bounds 搜索。外层 focusProperties.onExit + focusGroup 只拦真正左右离组，不阻止屏外项组合与组内搜索。
- 导航直接进入详情后返回，转场的临时内容聚焦会覆盖 tv.focus；真 TV 停靠随后会把导航推离屏幕。需要分别保存导航意图与卡片身份。

## 最终实现

1. HomePage 保留单个页面作用域回顶 Job。相同 Up 不重启；其他按键、内容失焦、切首页归零取消旧请求。回顶完成后等一帧再聚焦导航。
2. SharedBrowse.tvFocusRow 接入 Cards.LpRow、HomePage.ViewsRow、宿主 PluginHomeSection，只限制 TV 横轨左右出界。上下跨行与屏外项查找沿用 Foundation。
3. 每个导航 entry 保存 tv.navigation。导航获焦时清除旧卡片身份；内容的有效聚焦或按键恢复内容意图。转场未 RESUMED 的临时聚焦不能覆盖已保存的导航意图。
4. 正式 TvRoot 浏览入口统一提供 TvBringIntoViewSpec，两轴沿用原生 TV 的30%规则，卡片放不下时向前调整以完整显示；手机/独立播放器不继承，详情首屏的边缘规则继续覆盖。
5. 海报焦点绘制缓存固定几何和绘制对象，动画值只在实际绘制时读取，保持原像素、支点与布局边界。用中间帧和反向渲染验证，不能据此声称设备帧率提升。

## 风险与回滚

共享横轨规则也覆盖其它使用 LpRow 的 TV 浏览页，手机分支不启用。返回状态属于导航 entry，不改变网络、账号、图片权限、播放参数或持久数据格式。修改可按上述独立节点回滚，无数据迁移。

停靠曾在首轮试验撤回，之后横轨出界与导航返回的独立根因已修，再用完整回归验证重新接入；最终结论以 verification.md 为准。没有真机性能数据，不能将行为测试等同于 Apple TV 观感验收。

## 全浏览统一方案

- 已核查正式入口为TvRoot → PhoneRoot(tv=true)，TvFrame另供播放器使用。将首页TvHomeBringIntoViewSpec改名为TvBringIntoViewSpec并由TvRoot提供，删除首页重复Provider；详情ProvideEdgeSpecWhen仍优先，播放器与手机不继承新入口。
- 复用tvFocusRow到详情标签/季/集/演职员，数据源分类/筛选/线路，搜索类型/动作/插件结果，库筛选、聚合快捷、排行榜/星期条和宿主插件PosterRow/PlugTabs/FilterPanel。网格、不交互信息行、骨架以及通用插件ScrollView不套横轨封锁。
- 详情的播放回顶有三处重复scope.launch，已用连按/反向回归证实，收敛到页面内单个Job；保留头部获焦恢复整屏的视觉语义，不改播放决策。
- 预计新增修改TvApp.kt及上述共享页面/PluginComponents.kt；每处仅接线或替换重复任务，避免引入新的配置/依赖/焦点引擎。

- 真实数据源回归发现共享单行输入框吞上下键。Base.LpField仅在TV且lines<=1时转交上下焦点搜索，左右及多行编辑保留；同时补真实入口和单/多行组件回归。

## 全覆盖审查后的补漏

- Podium榜单前三名、PluginsPage四标签、SegRow设置分段、ProxyDialog代理预设复用tvFocusRow。SegRow的全部设置调用随共享组件生效，手机仍直接返回原Modifier。
- 栏目标题Down在滚回首项后需等布局，原独立协程会在快速Up/Left后迟到抢焦。请求改由MediaRowHeader持有单个Job，重复Down合并，非Down或失焦取消；标题焦点组onExit也取消，覆盖父页面先处理Up且暂时保留标题焦点的路径。保留滚回零项、等帧、聚焦首卡顺序，不改动画曲线或首页/详情父处理器。

## 既存失败收敛方案

- 现行胶囊样式及置灰播放占位保持，同步过时文档与测试；更新断言后继续执行加载返回、合集、分集/版本/字幕身份检查。
- 详情工具组的Up进入显式转为Enter，按当前横向布局从行首选择可用项，沿用框架重入保护；不新增按键处理器或改动播放业务。
- 单季与私密返回按实测逐键校准；私密内容通过Back进入当前导航再左移，保留真正退出空间的确认与状态断言。
