# Android release 启动闪退修复

## 根因与证据

红米 K80 Pro、Android 16 用户反馈点图标立即退出。V2 真机验证 Go 核心初始化成功;V3 保留主进程真实启动路径后取得堆栈:

`InitializationProvider` → `WorkManagerInitializer` → `NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []`。

这发生在 Application.onCreate / 页面创建前。实际依赖为 WorkManager 2.10.1、Room 2.6.1。未压缩 AAR 中 `WorkDatabase_Impl` 有 public 无参构造器,旧 release APK 中通过 `apkanalyzer dex code` 查不到该方法。R8 裁掉了 Room 反射创建数据库所需的构造器,因此不能将本故障归因于 Android 16、手机硬件、Emby 或播放核心。

## 修正与验证

- `proguard-rules.pro` 显式保留 RoomDatabase 子类的 public 无参构造器。没有禁用 R8、保留整个 WorkManager 或修改数据库与业务逻辑。
- 新增 `scripts/check-android-workdatabase.py`,直接检查最终 APK 中 `WorkDatabase_Impl.<init>()V` 是否存在且为 public。旧 V3 APK 检查实际失败;修复后的手机与 TV APK 均通过。实际反编译结果包含 `.method public constructor <init>()V`。
- 独立源码审查未发现规则本身的阻断问题,其指出的成品输出验证已补齐。
- 正常手机 / TV release 重新出包,Android native 重新构建,签名检查通过。手机包 16 KB ZIP 对齐、压缩包完整性及与 V3 同签名检查通过。
- APK 启动配置检查通过:图标恢复 MainActivity,主进程初始化组件保持启用,诊断 Activity 关闭。正常生成配置为 `STARTUP_DIAGNOSTICS = false`。
- 风格、diff 和包含未跟踪文件的凭据检查通过。未修改 MediaStationGo,未推送或发布。
- 用户覆盖安装修复包后确认可以正常打开,红米 K80 Pro / Android 16 的本次启动闪退完成真机验证。

## 交付与边界

手机修复包:`build/android-fixes/app-arm64-v8a-workmanager-fix.apk`。使用独立文件名避免下载时混淆旧包。正常手机与 TV 包同时更新于 `build/android/`。

直接覆盖安装 V3 或原应用,无需卸载,保留应用数据。用户已确认修复包正常打开;该结果只验证本次启动闪退,不替代 Emby 播放全流程验收。保留的 V3 用于后续启动取证,其本身仍包含旧问题。
