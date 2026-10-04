# 方案与边界

手机 PhoneRoot 是背景拥有者，始终铺当前主题底色；壁纸在其上绘制，页面仍通过 pageBg 让开。这样失败、透明或压淡的壁纸都合成到正确底色。共享 GlassIcon 和服务器胶囊使用 chip/fg 语义色。MainActivity 在主题作用域内同步系统栏明暗，禁止平台再次强制改色。Material 表面色由透明分层色合成到主题底色再传入。

涉及 PhoneRoot、MainActivity、Theme.kt、Dissolve.kt、HomePage.kt 、LibraryPage.kt 及 PhoneThemeTest。不改变电视独立配色、桌面、服务端、插件协议或播放渲染链，不增加依赖/设置。

追加：浅色主按钮取消亮黄中心停色，媒体库标题区合成浅色渐变底；API 24/25 使用黑色导航底适配平台仅有的白色导航图标，现代版本维持透明。深色库头保留原渐变停色。
