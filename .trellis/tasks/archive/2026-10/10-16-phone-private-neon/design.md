# 设计

在手机主题提供空间标志，PhoneRoot内提供唯一共享流光相位。使用受生命周期和系统倍率控制的无限动画，绘制阶段消费State，避免每帧整页重组。绘制缓存保存圆角轮廓、双色渐变和矩阵；旋转渐变而非控件，分层细线与透明宽线形成柔光，不新增依赖。

共用buttonSkin、LpButton、LpIconButton、ToneChip、PrimaryAction、GlassIcon、IconAction及导航接入；首页Hero、详情自绘播放按钮、流影快捷入口和卍解开关单点补齐。排序等无形状图标仅加光。MainShell排除播放交接页外框，独立PlayerActivity不安装流光宿主。

移除服名胶囊并恢复叠图白字阴影。回滚仅撤销本任务UI文件，无数据迁移。

## 审查修正

- FilterChip外层预留最小触摸布局，内层以Dp.Unspecified取消重复最小布局；荧光随真实可见表面，不用硬编码8dp裁切，也不缩小扩展点击区。
- InfiniteTransition自身消费系统MotionDurationScale；固定基础时长10秒，仅用LocalMotionScale决定零倍率静态。
- 先在故障实现跑4项新增回归，全部失败；修复后6项流光回归通过。此前遗漏原因：只测自绘按钮，没有真实M3筛选组件；动画只测0/1倍本地变量，没有同时设置框架倍率。新增真实像素/扩展点击和框架倍率周期断言防止重现。
