# 审查与验证

## 已修复

桌面Images的GetOrAdd工厂直接启动异步请求，并发时多个工厂都可能执行。16个独立线程同时取同键图片，原实现触发“同键并发启动了重复图片下载”；改为字典存Lazy任务，仅胜出项启动HTTP后回归通过。另一次独立复核确认finally移除顺序和无同步等待。

## 核实后排除

- cache-only与普通下载的字典键包含不同路由，不会合流到同一任务。
- 原图解码直接用实际物理高度，不经过缩略图Ladder上限。
- Android就绪状态按原图URL记忆，同一作品已成功图片的复用不是跨作品覆盖；延迟旧请求回归已覆盖切项隔离。

## 验证

- Android 51项：CachedHomeImagesTest 8、HeroArtworkTest 3、HomeHeroTest 15、PhoneHomeRefreshTest 23、TV Hero焦点2，全部通过。
- 缓存预览故障注入：移除预览绘制后回归失败，恢复后通过。
- 桌面productcheck --images-only通过：缓存探测不回源、旧缓存预览、无尺寸/quality原图请求、解码缓存隔离、鉴权、真实Hero同层升级、16线程合流。
- 桌面样式7项、Android参数名264处、diff检查通过；提交前执行敏感信息门禁。
- 未做真机、实服画质/流量/内存验收。桌面已有依赖与WindowsBase构建警告未在本任务扩大处理。

## 交付

按用户顺序先提交归档再构建Android手机arm64-v8a包。签名/ABI与最终产物检查结果记录到会话日志。
