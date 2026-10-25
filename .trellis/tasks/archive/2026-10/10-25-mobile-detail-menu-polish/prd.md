# 手机详情与切服菜单精修

## 目标
手机 UI 观感不低于 Yamby / Hills，减少说明性文字，聚焦视觉层级。

## 范围
- 详情作品主标题 32/40sp；首页头图片名保持 28/36sp（`LpText.banner`）。
- 视频/音轨/字幕选项统一 1dp 描边外形；可选项淡底 + 箭头，最小高 48dp，只读 40dp；轨道全名换行不截断。
- 切服菜单服务器条目 16sp 半粗、行高 ≥56dp；管理/添加操作保持默认菜单行。
- 主按钮形状核对：头图与详情播放按钮均 R.md，通用按钮 R.sm，已统一，无需改动。

## 验收
- HomeHero / PhoneDetailOptions / PhoneHomeRefresh / PhoneManagementUi 等测试通过，截图复核。
