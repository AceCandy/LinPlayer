# 验证

## 自动回归

实际命令：在项目工具链环境下，Gradle :app:testDebugUnitTest，选择五个类。

| 类 | 通过数 |
|---|---:|
| PhoneDetailCacheTest | 20 |
| PhoneDetailOptionsTest | 12 |
| PhonePosterMotionTest | 31 |
| PhoneMotionTest | 4 |
| PluginNavTest | 5 |

合计72项，0失败、0错误。包含挂起媒体/元数据/图片、普通和大字号、季/分集稳定布局、离页/账号切换、授权/不存在清缓存及三项新增呈现回归。
新增冷加载测试在旧实现先实际失败，再在局部呈现实现通过；缓存首帧测试隔离宿主导航动画后通过，未改生产导航。其像素断言只针对局部文字，而非网络耗时或真机帧时间。

参数契约检查：255处通过。git diff --check通过；隐私门禁15个文件没有新增实际地址/凭据。

## 独立复核

见review.md，完成两轮只读复核并由主线程按源码核对。不存在本次新增的请求或权限策略变化；淡出旧标签已禁用导航。

## 未覆盖

未连接真机，未验证同设备同数据帧耗时、真实网络和系统图形栈的整体流畅度；没有进行TV、服务端或播放器的新改造/验收。真实空缓存仍需要网络。简介/演员等可变长度区局部展开，不能保证整个页面绝对位置不变。超长资料、插件插入内容和错误提示仍可能改变布局。

## 手机交付

标准scripts/pack-android.sh成功，仅ARM64手机；TV交付包未更新。
最终build/android/app-arm64-v8a-release.apk，80,371,686字节，低于80MiB；与assemble产物逐字节一致。
apksigner按minSdk23检查v1/v2/v3均为true；13个原生库均为ARM64 ELF，包含libmpv.so和liblpcore.so。
SHA256：48cae49beb967b8e35d6e22a8488549e51f593b0bbde7fe89df920d6006ffbd3。

不提交、不归档，保留前轮首页待验收改动；待真机主观动效与帧耗时验收。
