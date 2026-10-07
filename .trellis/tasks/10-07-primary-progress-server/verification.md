# 验证与交付记录

## 已完成
- LinPlayer：主服固定账号+UserID、可信唯一TMDB/季集匹配、实际版本时长核验、零/回退/从头续播、副服CAS回写、无凭据持久化待同步、冲突保留及三端设置。
- MediaStationGo：准确PlaybackProgress GET/POST、毫秒精度ticks、显式零/false、原子409；四来源触发器覆盖普通写入、软/硬删除、身份迁移，保留tombstone且不生成统计事件。
- 独立审查修复：账号失效等待在途同步及重试落盘；重试使用账号读取前epoch，切换完成后拒绝迟到旧请求。磁盘失败不误报记录已保存。

## 已验证
- 最终LinPlayer scripts/check-core.sh 十关通过；check-bindings.sh四关、306命令契约及Android参数检查通过。
- progress/prefs/player/config/history竞态回归通过；主服切换的playback和retry阻塞、旧epoch拒绝回归通过。增强旧epoch回归先失败，增加入口校验后通过。
- Android手机设置选择/关闭/冲突保留/重试回归，完整组件深浅主题渲染检查通过；Kotlin编译通过。
- 桌面Release编译和风格门禁通过；Linux Xvfb真实窗口检查布局及主服单选持久化固定账号+用户，未使用生产账号。
- 服务端PostgreSQL四来源revision/并发首次CAS/权限/短进度/清零/409及删除、身份迁移通过。database/repository完整竞态回归通过；service/handler播放相关限定竞态回归及go vet通过。
- 跨仓真实LinPlayer客户端→MediaStationGo鉴权HTTP路由→隔离PostgreSQL联调，验证短进度、Played=true、0/false、陈旧409与回读一致。此证据不是生产部署或多设备播放验收。
- 服务端接口目录lint、tsc和vite生产构建通过；agent-browser搜索/筛选/复制/三尺寸无溢出、深浅主题可访问性及非管理员拒绝通过。
- 手机arm64及TV armeabi-v7a release APK本轮重新生成，pack签名门禁及apksigner verify通过；ABI/核心库/播放库ELF核验通过。Linux本轮重出2.0.0-dev绿色包，版本/核心调用/dlopen冒烟通过。
- 两仓git diff --check及任务context校验通过；LinPlayer敏感信息门禁无新增真实地址/凭据。

## 未通过 / 未验证
- MediaStationGo扩大到完整service/handler竞态回归不是全绿，其中未改动的TestTasksHandlerReturnsStableDefinitions预期26实际27，单独复现；不把限定播放回归宣称为全仓通过。
- TV真机按用户要求暂缓；Windows没有运行环境；手机真机、多设备同时播放、生产数据库升级与真实服务重启尚未验收。

## 行为边界与剩余风险
- 主服需要支持PlaybackProgress扩展；未升级或原版Emby接口缺失时按所在服正常播放，提示同步状态。先升级MediaStationGo再选择主服。
- 列表/详情仍显示各所在服状态；主服规则用于起播与副服回写，不做全库批量显示覆盖。
- 不确定匹配、候选截断、未知时长或剪辑差异拒绝同步；当前采用主服preferred版本，不自动遍历其它剪辑版本。
- 冲突保留，不提供强制覆盖或丢弃按钮；旧主服待同步保留，重试只当前固定绑定。
- 切换等待已有有界同步完成，最坏受单次5秒/重试20秒预算影响。

## 安装包
- build/android/app-arm64-v8a-release.apk
- build/android/app-tv-armeabi-v7a-release.apk
- build/pack-linux/LinPlayer-Linux-v2.0.0-dev.zip

未提交、未推送、未部署；具体文件范围见commit-plan.md。
