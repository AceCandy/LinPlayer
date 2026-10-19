# 共用组件与主题

- 页面优先复用已有组件词汇。桌面用 PageBase、Toast、Skeleton、Images.LoadAsync、Nav；手机用 LpScaffold、Panel、LpCell 等；TV浏览复用手机页面与组件，必须提供遥控焦点、确认/菜单键和返回恢复；顶部导航与播放控制沿用tv/kit。
- 桌面色彩读 Tokens.axaml / Tok.Of，共享浏览页读 Lp.colors，TV专用控件读 TvC；布局刻度分别按平台规范，不混用。
- 不透明颜色走 token；叠图的半透明颜色遵守对应控件语义。改变主题时检查实际合成底色，不能只比较独立色号。
- 主题修改覆盖正文、说明、占位、数值、图标、输入框、按钮与弹窗；检查深色、浅色及实时切换。
- UI 按草稿逐页实现，用户点名的控件形态照留；改变控件种类需先确认。草稿与实现冲突按最新反馈核实并记录。
- 设计正本：桌面 [UI_PC](../../../docs/go-migration/UI_PC.md)、手机 [UI_MOBILE](../../../docs/go-migration/UI_MOBILE.md)、TV [UI_TV](../../../docs/go-migration/UI_TV.md)；历史刻度不覆盖当前源码。

代表实现：[桌面样式](../../../apps/windows/LinPlayer.Desktop/Theme/Controls.axaml)、[手机组件](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/components/Base.kt)、[TV 组件](../../../apps/android/app/src/main/kotlin/xyz/linplayer/app/tv/kit/TvKit.kt)。

- `LpField` 可选 `trailingIcon` 复用原生输入框尾部槽，`onSearch` 非空时使用 IME Search 并调用该回调；未传时保持已有键盘和布局行为。

## 详情演职员

- 核心`Detail`稳定排序：Actor/GuestStar优先，其余人员在后；`role`为展示副标题，演员保留角色，已知幕后类型显示中文职务，未知类型保留原角色或类型原文，不猜测。
- 桌面为80–112逻辑像素圆头像、15号粗体姓名、12号副标题；手机/TV共用`ui/pages/DetailPage.People`，分别72/88dp头像、14/16sp粗体姓名、11/13sp副标题。均居中，缺角色不虚构。
- 不截断前20人，否则末尾幕后人员不可达；桌面沿用Carousel虚拟化，手机/TV用LazyRow。TV整组展示焦点支持左右滚动，不添加无效点击。
- 回归：`TestDetailPeopleCastFirstAndCrewJobs`与详情差分语料保护排序和职务；`CastPresentationTest`覆盖深浅手机渲染、TV遥控到末尾及返回首项。JVM渲染不能代替真机验收；旧展示快照实时刷新前可能仍是旧顺序。

## 季集图片展示回退

1. 范围：桌面卡片/列表/选集抽屉/详情与手机、TV共用浏览页；只改客户端展示，不修改服务端图片或条目海报归属。
2. 接口：列表 `SeasonId` 透传为可选 `season_id`；既有 `series_id` 和详情 `season_id` 保持。命令参数、生成绑定不变。
3. 顺序：单集 `Primary`（服务端提供的本集图片）→ 本集 `Backdrop/0` → 季 `Primary` → 整剧 `Backdrop/0` → 整剧 `Primary` → 文字占位；季 `Primary` → 整剧 `Primary` →「暂无季封面」；整剧卡片只用自己的 `Primary`。
4. 边界：加载失败才试下一候选，成功即停；缺父级ID跳过对应级别，不按名称或ID前缀猜测；详情已有明确季上下文时可补展示用ID。缺本集 `has_primary` 不得提前阻断父级回退，回退成功也不得改写该字段。
5. 缓存：候选保留当前服务器、代理尺寸与鉴权通道；Android浏览缓存、桌面CardItem序列化保留季ID。换条目重置候选，离页沿用既有组件生命周期。
6. 回归：ArtworkFallbackTest验证候选顺序、真实组件失败回退、成功即停、全失败占位和换条目；BrowseCacheTest验证季ID重启往返；桌面productcheck --images-only验证真实HTTP缺图与控件；TestArtworkParentsDoNotBecomeOwnPrimary验证Go字段与原图归属。
7. 正反例：错误是把整剧海报写入本集字段，或仅凭has_primary=false停止加载；正确是保留原始字段，在显示组件依次尝试各所属条目的图。此规则不要求服务端新增图区分接口。
