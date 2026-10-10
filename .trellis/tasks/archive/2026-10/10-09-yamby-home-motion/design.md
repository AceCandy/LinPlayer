# 设计

## 证据
APK 静态链已补齐：RouteHome -> C1759 case3 -> fc.m5372 -> fc.m5371 -> C2186 -> C1760 case2 -> fc.m5376 -> ec.m4997 -> C0152 case5 -> C1692 -> ec.m5020 -> C1694 case0。
此横轨入场450ms、CubicBezier(0,0,.2,1)，scale=.5+.5*p，alpha=.4+.6*p，translationX=(1-p)*width；首批错峰min(index,4)*80ms。另有simple entrance，不能混为首页该横轨。
原项目首页使用.9缩放、22dp上浮、.86透明度、轻弹簧，幅度/方向/时间曲线均不同。

## 改动边界
只修改 PosterMotion.kt 的 homePosterEntrance，曲线为该动效局部remember值，复用lpTween(T9)，不改通用Motion.kt。Cards/HomePage接线不变；PhonePosterMotionTest更新旧形态断言并补对照帧。
首次入场对齐450ms横向归位与明显放大；沿用独立图片渐显。保留项目的账号隔离/导航返回seen，以及每帧绘制层读取LazyRow边缘形变，满足用户持续滑动反馈，不盲目抄参考应用的普通remember重建语义。
动画只写graphicsLayer，无逐帧栏目重组，不消费滚动；系统零倍率直接正常。
错峰仅首排视野起点使用，已滚动横轨的新卡不增加等待；不等待停滑或图片。

## 测试环境
官方Android Emulator + API30 Google APIs x86_64，Docker仅映射KVM设备与本地SDK/本轮AVD目录，限制2核/4GiB容器，模拟器1024MiB、720×1280、禁用Vulkan、SwiftShader；首次较高配置OOM后降低配置。检查ARM翻译支持，失败时记录具体原因，不能声称已经跑过Yamby。
使用已有fakeemby，本地假账号；必要的临时fixture适配只放忽略目录，不进产品实现。录屏/截图用于实际观察，软件渲染环境帧耗时不代表真实手机。

## 回滚
回退本任务PosterMotion/Motion修改可恢复前批动效；未触及业务持久化或播放契约。
