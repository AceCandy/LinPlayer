# 开源排轨对照

- B站 DanmakuFlameMaster，核查提交 e2846461a09e33720a049f628f09c653f55531f0：`DanmakuUtils.willHitInDuration`检查当前及前条离场时的位置；`R2LDanmaku`按(视口宽+实际字宽)/时长移动；`DanmakusRetainer`安全即复用同轨，拥挤可略过。
  https://github.com/bilibili/DanmakuFlameMaster/blob/e2846461a09e33720a049f628f09c653f55531f0/DanmakuFlameMaster/src/main/java/master/flame/danmaku/danmaku/util/DanmakuUtils.java
- 字节 DanmakuRenderEngine：ScrollLine先Paint测量，检查入口间距和后条更快时的追赶量；同行允许多条，尺寸变化调整布局。
  https://github.com/bytedance/DanmakuRenderEngine
- 上述为公开源码对照，不代表当前商业B站客户端实测。不引入依赖、不复制整库；本项目继续保留不同模式间的纵向避让。

## 根因与判据

上轮仅改绘制实际字宽，核心仍整段占轨，因此无法解决一批离场才来下一批。设W视口宽，a前条宽，b后条宽，D时长，delta出现时间差：入口间距=(W+a)*delta/D-a，前条离场间距=W-(W+b)*(D-delta)/D；两端均不少于半字间距才允许接入。不能以关闭防重叠或延后时间戳代替正确排轨。

## 设置核对

四项常用项已恢复；仍隐藏的能力有粗体、合并重复、防止重叠、进度条热力图。是否恢复全部到更多设置已询问，未收到回答，本轮先不修改其入口与默认值。
