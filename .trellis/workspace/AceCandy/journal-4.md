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
