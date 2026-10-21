# 已确认接线

- Android流影通过 `NativeLiuyingContent.kt:311` 的内嵌PlayerPage复用播放与停止屏障。换片key在同文件`:299`；收藏窗口状态应放在其外。
- `PlayerPage.kt:376` 读default_speed；`:935` 的doSpeed修改当前内核并写default_speed。只改按钮不够，必须隔离起播与存储。
- `core/config/prefs.go:198` 是普通倍速字段，`:418` 默认值、`:534` 钳制，未识别配置键会保留，新增字段必须走强类型解析。
- `core/player/prefscmds.go:338` / `:351` 是播放器偏好getter/setter；setter保存失败会恢复before。`prefs.setPrefs`不是任意键写入口。
- `core/player/playback.go:67` 的applyPlaybackDefaults在MPV load前设置普通速度，需确保流影作用域在此之前生效；Media3由PlayerPage加载前setPlaybackSpeed。
- `core/emby/liuying.go:24` 的候选接口按feed/tab/filter/favorite分页，page_size固定12，无total；客户端列表不能把第一页当全部，也不能用通用收藏结果冒充流影收藏。
- 当前无现成流影收藏列表。继续使用该接口可保留权限、绑定库约束和当前身份，不需新增服务端端点。
