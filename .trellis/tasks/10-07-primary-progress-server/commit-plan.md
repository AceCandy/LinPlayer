# 本地提交清单

仅提交本次功能，分别在两个仓库创建工作提交；不推送、不部署。当前没有任何新提交。

## LinPlayer

建议提交说明：`feat(progress): 支持指定主进度服与安全同步`

- .trellis/spec/backend/database-guidelines.md
- .trellis/tasks/10-07-primary-progress-server/check.jsonl
- .trellis/tasks/10-07-primary-progress-server/commit-plan.md
- .trellis/tasks/10-07-primary-progress-server/design.md
- .trellis/tasks/10-07-primary-progress-server/implement.jsonl
- .trellis/tasks/10-07-primary-progress-server/implement.md
- .trellis/tasks/10-07-primary-progress-server/prd.md
- .trellis/tasks/10-07-primary-progress-server/research/compatibility.md
- .trellis/tasks/10-07-primary-progress-server/task.json
- .trellis/tasks/10-07-primary-progress-server/verification.md
- apps/android/app/src/main/kotlin/xyz/linplayer/app/tv/SettingsPage.kt
- apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/PrimaryProgressPanel.kt
- apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/pages/SettingsPage.kt
- apps/android/app/src/test/kotlin/xyz/linplayer/app/PhonePrimaryProgressTest.kt
- apps/windows/LinPlayer.Desktop/Views/MorePages.cs
- apps/windows/LinPlayer.Desktop/Views/SettingsSections.cs
- bindings/csharp/Commands.g.cs
- bindings/kotlin/Commands.g.kt
- core/config/account.go
- core/config/prefs.go
- core/config/progress_server.go
- core/emby/commands.go
- core/emby/playback.go
- core/emby/progress_sync.go
- core/history/primary_match.go
- core/history/primary_match_test.go
- core/paths/paths.go
- core/player/playback.go
- core/player/primary_progress_test.go
- core/player/start_report.go
- core/prefs/prefs.go
- core/prefs/primary_progress.go
- core/prefs/primary_progress_test.go
- core/progress/link.go
- core/progress/store.go
- core/progress/store_test.go
- docs/go-migration/COMMANDS.md
- docs/go-migration/SPEC.md
- docs/go-migration/UI_MOBILE.md
- docs/go-migration/UI_PC.md
- docs/go-migration/UI_TV.md

## MediaStationGo

建议提交说明：`feat(emby): 增加准确进度快照与版本条件同步`

- .trellis/spec/backend/database-guidelines.md
- .trellis/spec/backend/emby-api-catalog-sync.md
- .trellis/spec/backend/index.md
- .trellis/spec/backend/playback-contracts.md
- .trellis/spec/backend/progress-sync-contracts.md
- .trellis/tasks/10-07-primary-progress-api/check.jsonl
- .trellis/tasks/10-07-primary-progress-api/design.md
- .trellis/tasks/10-07-primary-progress-api/implement.jsonl
- .trellis/tasks/10-07-primary-progress-api/implement.md
- .trellis/tasks/10-07-primary-progress-api/prd.md
- .trellis/tasks/10-07-primary-progress-api/task.json
- .trellis/tasks/10-07-primary-progress-api/verification.md
- internal/database/playback_progress_revision.go
- internal/database/playback_progress_revision_test.go
- internal/database/schema_migration.go
- internal/handler/emby_progress_sync.go
- internal/handler/emby_progress_sync_test.go
- internal/handler/emby_routes.go
- internal/model/model.go
- internal/model/playback_progress_revision.go
- internal/repository/playback_progress_sync.go
- internal/service/emby_progress_sync.go
- web/src/pages/embyApiCatalog.ts

## 不包含

- MediaStationGo 预先存在的未跟踪 `core`：未读取、未修改，不纳入提交。
- build、缓存、安装包、凭据、临时测试数据不纳入提交。
- .trellis/workspace 会话记录属于后续收尾记录，不混入功能提交；任务归档在工作提交获准并完成后处理。

本清单对应当前已独立复核及验证的结果。完整服务端扩大回归未全绿，详见 verification.md；该限制不会隐藏在提交说明中。
