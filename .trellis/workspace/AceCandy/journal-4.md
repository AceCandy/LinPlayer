# Journal - AceCandy (Part 4)

> Continuation from `journal-3.md` (archived at ~2000 lines)
> Started: 2026-10-26

---



## Session 133: 首页轮播推移与详情海报放大转场
<!-- trellis-session: v=2 fp=3e1c3d3870ab332e -->

**Date**: 2026-10-26
**Task**: 首页轮播推移与详情海报放大转场
**Branch**: `main`

### Summary

轮播评分简介与推移换图、海报/轮播放大进详情、豆瓣标志、统一加载与缺图占位、移除本机观看历史

### Main Changes

- 轮播：去顶部暗层，渐变至续播卡3/4；★与豆瓣评分、简介；去播放键；同时推移换图
- 海报与轮播点击放大成详情头图，标题顶栏浮在共享层淡入，返回缩回原卡
- 豆瓣评分改豆瓣标志；加载中不闪暂无封面；加载统一居中；日历周条去数量；底栏收拢；移除本机观看历史

### Git Commits

| Hash | Message |
|------|---------|
| `9a800a34` | feat(android): 首页轮播与详情放大转场、统一加载与缺图占位 |
| `bcf3ee0c` | chore(task): archive 10-26-hero-zoom-ux |

### Testing

- [OK] 全量单测 1031 例通过；查看放大中间帧与轮播/角标截图

### Status

[OK] **Completed**

### Next Steps

- 真机验证放大与推移流畅度、豆瓣标志小尺寸清晰度


## Session 134: 详情放大转场去换图与侧滑返回缩回
<!-- trellis-session: v=2 fp=41707276a4b92a76 -->

**Date**: 2026-10-26
**Task**: 详情放大转场去换图与侧滑返回缩回
**Branch**: `main`

### Summary

录屏逐帧定位：首次进入背景图转场后才到导致换图；侧滑返回落到导航默认0.7整页缩放。改为命中来源海报内存、首页预热详情背景图、侧滑返回淡出加海报缩回。

### Main Changes

- 详情预览图以来源原地址作内存键，未命中只读本机缓存
- 放大进入的详情侧滑返回改为淡出与海报缩回
- 首页卡片停留300ms后HEAD预热详情Backdrop 720

### Git Commits

| Hash | Message |
|------|---------|
| `6cce9029` | fix(android): 详情放大转场首帧不换图、侧滑返回只缩回海报 |

### Testing

- [OK] 全量单测 1034 例通过

### Status

[OK] **Completed**

### Next Steps

- 真机确认侧滑返回与首次进入效果


## Session 135: 统一加载动画与收藏单次取150条
<!-- trellis-session: v=2 fp=5c464fa8b4a0e3c7 -->

**Date**: 2026-10-26
**Task**: 统一加载动画与收藏单次取150条
**Branch**: `main`

### Summary

整页加载统一大动画、有缓存静默刷新、去掉加载更多、手机收藏单次150条、数据源分类首批空页续取；未编译、未跑单测、未真机验证

### Git Commits

| Hash | Message |
|------|---------|
| `d4bb6a96` | fix(android): 统一整页加载动画，收藏单次取 150 条并去掉加载更多 |

### Status

[OK] **Completed**


## Session 136: TV 对齐回归修复与提交归档
<!-- trellis-session: v=2 fp=16be48ff9d6a2783 -->

**Date**: 2026-10-27
**Task**: TV 对齐回归修复与提交归档
**Branch**: `main`

### Summary

提交当前 TV 向手机端对齐改动与旧页面清理；修复回调类型、测试夹具导入、时间文字断言及服务上报测试生命周期。13 类 165 项回归通过，189 处 Android 参数检查与敏感信息门禁通过；已归档对应任务，未推送。

### Git Commits

| Hash | Message |
|------|---------|
| `fee32450` | feat(android): 对齐 TV 设置与播放器并清理旧浏览页 |

### Testing

- [OK] 165 项定向回归通过，无失败、错误或跳过
- [OK] Android 调用参数、暂存内容敏感信息及 diff 格式检查通过

### Status

[OK] **Completed**

### Next Steps

- 真机安装、遥控焦点、解码播放和 HDMI 刷新率仍待验收；本轮未重打 APK


## Session 137: 完成 Android TV 对齐修复并归档
<!-- trellis-session: v=2 fp=7e30ed93d94d4bf1 -->

**Date**: 2026-10-27
**Task**: 完成 Android TV 对齐修复并归档
**Branch**: `main`

### Summary

完成 Android TV 首页、顶栏、共享浏览与播放相关对齐修复；实现 TV 轮播被动播放、栏目获焦切换、黄色短线、浅色叠图高对比、继续观看焦点修复及窗口自适应；通过定向 TV/手机回归、视觉、隐私、参数与 diff 检查；未出包，未做真实 Android TV/实体遥控器/实服验收。

### Git Commits

| Hash | Message |
|------|---------|
| `096e825a` | feat(android): 完成 Android TV 对齐修复 |

### Status

[OK] **Completed**


## Session 138: Android TV 详情页布局与遥控交互
<!-- trellis-session: v=2 fp=9c88f9688f092c5e -->

**Date**: 2026-10-27
**Task**: Android TV 详情页布局与遥控交互
**Branch**: `main`

### Summary

完成TV详情页沉浸布局、紧凑操作与自适应参数；移除全局导航及屏幕返回按钮，修复遥控滚动和播放返回焦点，按用户要求提交归档。

### Main Changes

- TV详情背景铺满顶部和两侧，操作与续播信息分层，保留手机返回入口及共享播放契约。
- 补充焦点、返回及深浅主题渲染回归，同步UI正本与质量规范；任务材料为完成后补录。

### Git Commits

| Hash | Message |
|------|---------|
| `c7b2079f` | feat(android): 优化 TV 详情页布局与遥控交互 |

### Testing

- [OK] 最终61项定向测试通过，失败、错误和跳过均为0；独立复核完成。
- [OK] 参数门禁检查189处调用通过，工作与归档提交的暂存隐私门禁及diff检查通过。
- [OK] 全仓隐私扫描仍命中旧journal-2日志，本次未修改旧记录或放宽规则。

### Status

[OK] **Completed**

### Next Steps

- 电视真机、实体遥控器及真实片源验收待完成；未打包、未推送。
