# 实施

- [x] 用户确认范围与方案；读取既有设计及源代码
- [x] 补回归并记录修复前失败
- [x] 核心保留/回收与读取门控，命令表/绑定
- [x] 手机双向窗口、源保留、提前取批、取消Progress
- [x] 独立审查修复；Go/Android定向测试与参数/绑定/核心门禁
- [x] 记录证据、未验项及剩余风险

验证：项目Go工具链下player/emby/prefetch/preload测试及race；Android PhoneLiuyingTest、LiuyingMediaSlotsTest及普通播放/TV隔离回归；check-android-args.py、check-core.sh、check-bindings.sh、diff空白与隐私检查。无需打包。真机/实服不可用时不得勾选性能验收。

证据见validation.md；设备性能验收仍未验证，不把实现完成等同于真机丝滑验收。
