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
