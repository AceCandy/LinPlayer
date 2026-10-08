# 手机视觉需求源码勘察

## 事实锚点

- 播放按钮唯一共用渲染点 `PlayerControl`：`apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/player/PlayerPage.kt:1143-1154`。默认 `44.dp` 圆形，`background(Color.Black.copy(alpha = .38f))`，图标白色，修饰链末尾 `.pressable(onClick)`。横屏及竖屏 OSD 均调用它：横屏 `PlayerPage.kt:1021-1067`；竖屏分支始于 `1079`，顶部返回用了 `LpIconButton`（`1085`），继续确认竖屏子树时从该行往下看。由此，去底色应落在共享 `PlayerControl`，不能删 `.pressable`；`LpIconButton` 是否也在用户所说“所有按钮”范围需据实查看竖屏树。
- OSD 当前已有完整可交互进度 `ProgressRow(position,duration,heat,onSeek,showTotal,seekKey,buffered)`：`:1257-1334`。它带时间、Slider、拖动预览/seek、热力图和缓冲段，不能直接复用为“只读极窄条”；横屏接线在 `:1056-1067`，进度组件接收 `position/duration/heat/onSeek/buffered`。
- 播放页已有状态：`:241-252` 有 `position/duration/buffered/paused/buffering/osd/locked/panel/osdClearance`；`:286-296` 的 `leaving` 先放开方向锁、等 `TurnSettleMs` 再 pop。OSD 的横竖布局各自 `.safeDrawingPadding()`（横屏 `:1023-24,1056-57`；竖屏顶部 `:1081-83`），底栏报告高度给 `onBottomHeight`。插件面板列表传入 `Osd`，横屏在底栏中追加（`:1068` 起）。
- OSD 主体签名：`PlayerPage.kt:1007-1019`，有 `panelOpen`，收到 `position/duration` 和 `buffered`。但 CodeGraph 此轮未给出 `PlayerPage` 后半段的 OSD 显隐条件、轮询/事件订阅、PIP 绘制分支和完整竖屏结构；不能据当前片段断言何时消失或PiP行为。
- 播放器两路状态源不同：`:233-238` 明确写有 ExoPlayer 不发 `player.status`，需页面存在时用 `WallpaperGate.PLAYER`；`:298-308` `PlayerController` 按 engine 分派，数据源强制 mpv。OSD进度是已有 `position` 状态，详情语境没有证据表明可独立直接读取底层。添加细条宜消费此状态；不要新开MPV属性轮询（现有轮询代码位置/频率本次未成功定位，详见未覆盖）。
- 手机详情页 `DetailPage` 在 `ui/pages/DetailPage.kt:230`，从 `:354` 开始取得 `d=detail.valueOrNull`；然而本次 CodeGraph 切片未返回详情页面背景图/封面所在的下半段。TV 详情单独实现 `tv/DetailPages.kt:110-116` 的 `DetailBackdrop`，明确均匀 scrim 且“不模糊”，不能把它误当手机页实现。
- 项目已有 blur 支持边界在壁纸实现 `ui/plugin/WallpaperLayer.kt:36-43`：`Modifier.blur` 的 RenderEffect 仅 API 31+有效，API 31 以下静默不工作，项目用 `canBlurWallpaper = SDK_INT >= 31` 隐藏相关设置。用户需求是详情背景模糊，不等同于壁纸模糊。尚未发现 API 24 降级组件；仓库的既有可用实现只支持 31+，不能声称 API24 已有降级。
- 手机外观壁纸调用在 `PhoneRoot.kt:91-95`：根 Box 先铺主题底色，随后组合 `WallpaperLayer()`；该层 `WallpaperLayer.kt:45-89` 从 `plugin.initialWallpaper`、`plugin.wallpaper` 事件设置内容，支持 image/canvas/video（shader 未实现），全站底层绘制。设置入口可能不在手机：CodeGraph列出 `ThemePicker.kt:38` 的“界面主题”入口和TV设置 `SettingsPage.kt:223` AppearanceRows，Android当前手机设置入口尚未核实。`Wallpaper` 被主题默认壁纸/插件壁纸共用（WallpaperLayer `:54-62` 回退 `PluginTheme.wallpaper`），且被TV、ThemePicker、WallpaperLayer使用；删除整个插件壁纸能力会超出“去掉手机外观壁纸”的需求范围。只移除 PhoneRoot 组合可达成手机不绘制，同时保留插件 API与其它调用方（推断，需主代理判断）。
- 图片通道 `AppState.imageUrl` 在 `data/AppState.kt:179-187`，Backdrop 会转为 `Backdrop/0`，走本地 `/img` URL；主题壁纸 `ImageWall` 使用 Coil3 `rememberAsyncImagePainter` (`WallpaperLayer.kt:91-104`)。本轮没定位手机详情用图的实际 painter，也没找到页面导航转场实现。`Dissolve.kt` 的 `Layer`/`dissolve` 是组件候选（CodeGraph命中 `Layer:128,dissolve:62`），尚不能据此说它已挂入导航或适合页面转场。

## 推断/实现边界建议

- “OSD消失后最底部极窄只读进度条”宜为 OSD 之外的独立叠层：只显示播放比例，不接触摸/seek，也不调用 mpv；显示与否取 `osd`、`locked`、`leaving`、`panel`、方向/safeDrawing、PIP状态的现有页面条件统一判定。具体条件必须先从未覆盖的 PlayerPage 后半段补查，避免锁屏/面板/退场/PIP 时误显或遮系统手势区。
- 横竖两套 OSD 都通过 `Osd`/`PlayerControl` 共用实现，调按钮底色影响最窄范围是共享函数；但竖屏返回按钮的实现类型不同，验收要检查其余单独按钮。
- 详情背景 blur 应仅作用于详情背景图层，前景内容不模糊；API31+可沿用项目已有 `Modifier.blur`。API24-30的降级策略项目证据不足，需要主代理结合产品要求选（不应让无效果的 Modifier 冒充支持）。
- 壁纸“外观壁纸不要了”从现有代码可解释为关闭手机根页面的壁纸渲染入口；不要删除 `Wallpaper`、壁纸插件事件/API、TV壁纸和主题插件能力。需额外核实 Android 手机设置中壁纸 picker/设置项的真实入口，避免留下无效入口。
- “图片与导航更丝滑”范围较宽。可优先确认手机详情图是否有 Coil painter/内容 key/现成转场；当前证据只足以定位 `DetailPage` 和 `AppState.imageUrl`，尚不足以确定修改点。

## 验收矩阵建议

| 场景 | 检查点 |
|---|---|
| 手机播放器竖屏/横屏 | 所有控制图标无黑色按钮底，点击和按压反馈仍工作；safeDrawing边缘不被系统栏遮挡 |
| OSD显示/自动隐藏 | OSD可 seek；OSD隐藏后只剩最底部细进度，时间推进正确且不可触摸、不改变播放位置 |
| 锁屏、控制面板打开、切换方向、离开播放器 | 检查细条不会与OSD/面板重复、退场闪现、或旋转中残留；明确产品上期望锁屏是否显示 |
| PIP、API24-30、API31+ | PIP按期望隐藏/显示；低API模糊有明确定义且无静默假支持；API31+背景模糊而前景清楚 |
| 手机外观壁纸/插件 | 手机页面不再绘制用户所指壁纸；壁纸插件及主题壁纸注册能力、TV能力不被误删；设置入口不留下无效项 |
| 详情图与导航 | 冷缓存/热缓存、加载中占位、返回详情、快速连续导航时图像与过渡无闪烁/突跳；需先核实实际页面实现 |

## 未覆盖/待核实

- `PlayerPage.kt` 后半段：位置/时长/缓冲值如何更新、mpv查询节奏、OSD auto-hide effect、locked/panel/leaving/PIP 条件、竖屏所有按钮。
- `ui/pages/DetailPage.kt` 实际 backdrop/poster 绘制代码及图片 painter；手机设置页面是否有壁纸开关/选择器。
- 手机 NavHost 的动画/图像加载组件及其调用关系。
- 用户说的“外观壁纸”是否仅指全局插件壁纸，还是详情/主题背景也包括在内；现有代码无法替用户解释该措辞。
