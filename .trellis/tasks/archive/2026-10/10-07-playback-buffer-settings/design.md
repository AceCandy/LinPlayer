# 双内核缓冲目标

## 最小行为差距与拥有者

目前核心没有内核容量字段，Android Media3沿默认LoadControl，MPV沿默认/用户conf；设置页无法调整。新增单一全局播放器偏好buffer_target_bytes（0自动，64～512MiB自定义），不复用磁盘prefetch_cache_bytes。

## 接线

core/config/Prefs存储/旧值默认自动，非法读值回自动；core/player/prefscmds现有get/set命令透传并严格校验整数/类型/范围，未传保留。新增字段不改生成绑定。手机/TV设置提供自动/自定义及容量，复用SegRow/StepperRow与TV PanelItem，保存失败回滚并阻止同字段并发保存。

Media3从播放页已有prefs.getPrefs读值，无新请求；等偏好就绪再构造播放器及起播。ExoEngine构造DefaultLoadControl：自动不改策略，自定义targetBufferBytes且size优先，同时保留默认启动时间。TV起播目前未等trackPrefs，接入时明确就绪门，避免先按自动起播再重建。当前播放器不为设置更新中断。

MPV使用loadfile本片demuxer-max-bytes选项，自动不加该选项，结束由内核恢复原conf有效值；loadArgs与续播start选项同一个options槽位，旧/新语法fallback兼容且无续播也可试。Android build-tag提供容量，桌面返回0；本地playFile同样传本片选项，不绕过。避免自维护缓存基线或修改mpv.conf。

## 变更文件与边界

必需core/config/prefs.go（字段约束）、core/player/prefscmds.go（命令）、load.go及playFile（MPV本片选项）、surface_android/other（平台门）；Android两设置页、两播放页、ExoEngine与共用缓冲常量/LoadControl helper；对应回归和规范。原工作树保留，不重构邻近代码。服务端、磁盘缓存UI、桌面设置、HDR/直通、预取策略均不改。

## 验证与风险

旧行为回归先红再绿；覆盖命令拒非法/保持其它值/落盘兼容，Media3实际LoadControl加载判据，手机/TV设置真实交互与回滚，生产页起播传值，MPV旧新loadfile槽位/从头/续播/自动恢复。严格同版源码+Linux运行探针是证据，Android真实so/设备另验，不能宣称已测真机。目标不是硬限额，时间/码率可使实际量低于目标。回滚移除接线；旧配置新键会被原unknown字段保留。

## 验证工具契约修正

字段检查发现COMMANDS.md将player.status动态map误标为Status，被解析成progress.Status（conflicts/error/pending）产生7项假红。真实handler返回map，因此仅将这一行返回类型修正为Result<Value,String>；脚本保持原判据，按其原有动态结果放行规则报告覆盖率，不宣称全覆盖。
