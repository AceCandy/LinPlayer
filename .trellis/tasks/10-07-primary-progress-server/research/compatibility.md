# 主服同步兼容核查

服务端基准：MediaStationGo 0c32eee7c2169d4da3b0873a4af5b8e80be41e5b。已跟踪工作树无差异，未跟踪 core 未读取；未做真实账号联调。

## 客户端事实
- core/player/playback.go:164-170 / core/history/store.go:198-245：起播取远端、本服本地、跨服本地最大值，不满足主服0/回退权威。
- core/config/account.go:24-31、298-339：账号键server，线路会变，SessionOf在核心解析凭据。主服须保存server+user_id，不能跟随active或只绑线路。
- core/player/transport.go:70-103、playback.go:463-558：进度落本地后报所在服，停播强制落盘。core/player/start_report.go:14-55隔离每次会话的迟到上报。
- core/history/restore.go:113-170：已有回写三件套，失败返回；无持久化待同步队列和冲突版本控制。现有跨服回写偏好没有运行期自动回写调用，不能把设置存在视为功能已完成。
- core/history/match.go:446-454：跨服MatchCandidates不比较仅本服唯一的PUK；主服自动同步要求可信且唯一匹配。
- core/emby/emby.go:151-155、248-260：原始UserData指针区分缺失/0，公开Item已丢失存在性。
- 手机SettingsPage.kt:90-93隐藏未落地跨服面板；TV通用设置已有bool；桌面SettingsSections.cs:193-273有跨服组。

## 服务端限制
- internal/service/playback.go:70-85：长片播放位置不足60000毫秒不写，返回nil；短片也有最小记录门槛。
- internal/handler/emby_playstate_handlers.go:54：Sessions三件套走RecordProgress，所以HTTP成功不能证明状态已更新。
- internal/service/emby_user_data.go:156：DELETE PlayedItems对普通元数据删除历史，不能未经明确同意把它当成仅清零进度的方法。
- internal/repository/history_repository.go:90-91：upsert无expected version / If-Match，按接收时间更新。客户端先读再写存在竞态，不能保证其他端的新状态不被覆盖。
- internal/service/emby_items_detail.go:437、internal/service/emby_items_helpers.go:157：显式0/false有效；普通UserData不提供进度更新版本。
- internal/handler/emby_items_handlers.go:13：未解析AnyProviderIdEquals；客户端ByTmdb能力降级与二次筛选必须保留，不能假定精确查询可用。

## 结论
客户端可安全完成主服读取、严格匹配、独有资源回落、状态保留和经核验的正常进度同步。要保证任意短进度/清零正确回写，以及多端冲突检测，需服务端增加兼容的显式写入语义和版本校验；此跨仓行为未获授权。不得静默解除自动播放记录门槛、以删除历史代替清零，或对未落盘的HTTP200报同步成功。
