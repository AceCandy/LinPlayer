# 实现
- `DetailPage.WideHead`：海报高 ≤ min(屏高×54%, 屏高−360dp)。
- `HomePage.CachedHomeBanner`：wide 时 Hero 高=可用高×50%（260~460dp），标题36sp，Logo 260×80，文字列≤560dp。
- `libraryCardSize()`：wide 时 220×124，加载占位同步。
- `PhoneWideLayoutTest`：新增 FOLD / SMALL_TABLET 9 例，`assertPlayInSide()` 复用。
- 验证：全量 1022 测试，3 跳过，0 失败。
