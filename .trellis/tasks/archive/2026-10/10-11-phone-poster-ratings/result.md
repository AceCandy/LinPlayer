# 手机海报评分调整完成记录

代码提交：`b9fa8c3d`（`fix(android): 恢复普通评分并保留豆瓣评分`）。

## 改动

- `MediaCard` 右下纵排展示普通和豆瓣评分；`MediaRatingBadge` 复用胶囊样式，各自处理缺失、范围和无障碍描述。
- Emby 普通评分使用既有 `rating`（来源为 `CommunityRating`），豆瓣使用独立 `DoubanRating`，不新增评分请求，不混用来源。
- 插件普通评分只取非豆瓣的 10 分制项；豆瓣继续只认明确来源，避免只有豆瓣时显示两个相同评分。
- 同步手机设计正本和 Android 规范，覆盖用户最新要求。

## 修改文件

- `.trellis/spec/frontend/android.md`
- `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/components/Cards.kt`
- `apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/plugin/PluginComponents.kt`
- `apps/android/app/src/test/kotlin/xyz/linplayer/app/HomeCinemaTest.kt`
- `apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneColorSchemeTest.kt`
- `apps/android/app/src/test/kotlin/xyz/linplayer/app/PhoneLibraryUiTest.kt`
- `docs/go-migration/UI_MOBILE.md`

## 验证

- 新增普通评分可见、仅豆瓣不重复为普通分的断言，在修复前失败、修复后通过。
- `HomeCinemaTest` 4 项、`PhoneColorSchemeTest` 7 项、`PhoneLibraryUiTest` 12 项、`ResumeCardTest` 7 项，合计 30 项通过；归档前核对测试报告，无失败、错误或跳过。
- 覆盖评分共存、独立隐藏与更新、无效普通评分、插件来源与分制隔离、1.3 倍字体、长按菜单及续播。
- Android 参数检查 257 处通过；字段检查 207 处通过，另 240 处为既有放行。
- 生产差异独立复核、`git diff --check` 和新增内容隐私门禁通过。
- 最终手机包：`build/android/app-arm64-v8a-release.apk`，构建通过，`apksigner` v2/v3 验签通过。
- 安装包 SHA256：`2992b396c6ee283b5ca0932d4ff2b499491e486a561fb77f773a4ac04fd2af17`。
- 渲染截图为测试夹具，仅用于评分布局核验，不是实际媒体页面；安装包和截图均被忽略，未入仓。

## 未验证与剩余风险

- 未做真实 Emby 联调与真机 UI 验收；实际媒体、设备字体和安全区域表现仍需设备验证。
- 未改变核心请求与播放逻辑。仅提交及归档，不推送远端。
- 临时日志已清理，构建守护进程已停止。
