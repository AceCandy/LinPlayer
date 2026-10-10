package xyz.linplayer.app.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计 token(UI_MOBILE.md §1)。**刻度是枚举不是区间** ——
 * 要用第五种圆角,先改 UI_MOBILE.md §1.3 那张表,别就地写新值。
 */
@Immutable
data class LpColors(
    val bg: Color, val s1: Color, val s2: Color, val s3: Color,
    val line: Color, val line2: Color,
    val fg: Color, val fg2: Color, val fg3: Color,
    val acc: Color, val accDim: Color, val accFg: Color,
    val ok: Color, val warn: Color, val bad: Color,
    val scrim: Color,
    /** 叠在画面上的玻璃底。**写死色号必须带 alpha** —— 不透明的一律进上面那些 token */
    val chip: Color,
    val isDark: Boolean,
) {
    /** 兼容媒体组件沿用的token名称，强调色始终来自同一色系。 */
    val mediaAccent: Color get() = acc
    val mediaOnAccent: Color get() = accFg
    val mediaPanel: Color get() = s2.compositeOver(bg).copy(alpha = .9f)
    val mediaIcon: Color get() = fg2
    val mediaBadgeInk: Color get() = fg
}

/*
 * 调色板照 docs 的手机端草稿(Draft 04「去边界」)。
 *
 * ★ **s1/s2/s3 和 line 全是半透明白**,不是不透明色号。
 *   草稿的核心手法是「分层替代描边」—— 卡片靠一层更亮的膜浮起来,
 *   不靠一圈 1px 边框框住。写成不透明色号的话,叠在取色底上就成了一块死灰补丁。
 */
/* 主题 token 是 `var`:插件主题在**第一次组合之前**改它一次(重启生效,D69),
   之后再没人写 —— 做成状态反而要求全站跟着重组,而那一次重组什么也换不了。 */
internal var DarkColors = LpColors(
    bg = Color(0xFF0D0C12),
    s1 = Color(0x0EFFFFFF), s2 = Color(0x1AFFFFFF), s3 = Color(0x26FFFFFF),
    line = Color(0x12FFFFFF), line2 = Color(0x24FFFFFF),
    fg = Color(0xFFF5F5F7), fg2 = Color(0xFF9996A8), fg3 = Color(0xFF6E6880),
    // 强调色是**琥珀**不是蓝:草稿里评分、进度、选中态、主按钮渐变都吃它
    acc = Color(0xFFF4AD39), accDim = Color(0x2EF4AD39), accFg = Color(0xFF20160A),
    ok = Color(0xFF5CD6A0), warn = Color(0xFFF5A524), bad = Color(0xFFFF6B5E),
    scrim = Color(0xB3100E14), chip = Color(0x8C1A1720), isDark = true,
)

// 浅色:同一套语义翻个面。**琥珀在白底上要压暗**,#F5A524 放浅底上是看不清的
internal var LightColors = LpColors(
    bg = Color(0xFFFAF7FC),
    s1 = Color(0x0B000000), s2 = Color(0x13000000), s3 = Color(0x1E000000),
    line = Color(0x0F000000), line2 = Color(0x1F000000),
    // 说明与占位也承载可读内容:叠在最深的 s3 分层底上仍需保留 4.5:1 对比度。
    fg = Color(0xFF1A1622), fg2 = Color(0xFF514A60), fg3 = Color(0xFF615A70),
    acc = Color(0xFF8A5A00), accDim = Color(0x1F8A5A00), accFg = Color(0xFFFFFBF2),
    ok = Color(0xFF1F7A55), warn = Color(0xFF8A5A00), bad = Color(0xFFC7554E),
    scrim = Color(0xB3FAF7FC), chip = Color(0xC7FFFFFF), isDark = false,
)

/** 间距刻度。**允许的值只有这些** */
object Sp {
    val x0 = 0.dp
    var x2 = 2.dp; var x4 = 4.dp; var x6 = 6.dp; var x8 = 8.dp
    var x10 = 10.dp; var x12 = 12.dp; var x16 = 16.dp; var x20 = 20.dp
    var x26 = 26.dp; var x34 = 34.dp; var x48 = 48.dp

    /** 主题密度(`layout.density`)。整把尺一起缩放,启动时调一次。 */
    fun scale(k: Float) {
        x2 *= k; x4 *= k; x6 *= k; x8 *= k; x10 *= k; x12 *= k
        x16 *= k; x20 *= k; x26 *= k; x34 *= k; x48 *= k
    }
}

/** 圆角刻度。8=小件 · 12=卡片 · 18=弹窗面板 · 999=胶囊 */
object R {
    val none = 0.dp; var sm = 8.dp; var md = 12.dp; val lg = 18.dp; var pill = 999.dp
    val xl = 28.dp
}

/** 固定尺寸。超过 48 的偏移不许写字面数字,抽成这里的具名常量 */
object Dim {
    val topBar = 52.dp
    val tabBar = 64.dp
    val tabFloatGap = 12.dp
    val tap = 48.dp
    val hairline = 1.dp
    /* 草稿里那几块「铺到屏幕顶」的图。它们不是间距,是**版面高度**,
       所以抽成具名常量 —— 改了封面高度而没改让位高度的话,底下第一条轨会被压住。 */
    val coverDetail = 236.dp // 剧/影详情页背景图(草稿 03)

    /** 悬浮栏及上下间距；系统导航条由页面安全区另外计入。 */
    val tabClearance = tabBar + tabFloatGap + 6.dp
}

val LocalLpColors = staticCompositionLocalOf { DarkColors }

/**
 * 动效倍率。跟随系统的「移除动画」设置(UI_MOBILE.md §2.4)。
 * 每个动画都要乘它 —— 散着判会漏,所以挂在 CompositionLocal 上。
 */
val LocalMotionScale: ProvidableCompositionLocal<Float> = compositionLocalOf { 1f }

private fun lpTypography(f: FontFamily?): Typography {
    fun style(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontSize = size.sp, lineHeight = height.sp, fontWeight = weight,
        fontFamily = f, letterSpacing = 0.sp,
    )
    return Typography(
        displayLarge = style(25, 32, FontWeight.Bold),
        displayMedium = style(20, 28, FontWeight.Bold),
        displaySmall = style(20, 28, FontWeight.Medium),
        headlineLarge = style(25, 32, FontWeight.Bold),
        headlineMedium = style(20, 28, FontWeight.Medium),
        headlineSmall = style(16, 24, FontWeight.SemiBold),
        titleLarge = style(18, 24, FontWeight.SemiBold),
        titleMedium = style(16, 24, FontWeight.SemiBold),
        titleSmall = style(15, 22),
        bodyLarge = style(14, 21),
        bodyMedium = style(13, 20),
        bodySmall = style(12, 16),
        labelLarge = style(14, 20, FontWeight.Medium),
        labelMedium = style(13, 20, FontWeight.Medium),
        labelSmall = style(11, 14, FontWeight.Medium),
    )
}

/**
 * 内置静态字体资源，兼容API24；粗字由字体族合成，不使用变量字体轴。
 */
@Composable
private fun userFontFamily(): FontFamily? {
    val id = xyz.linplayer.app.data.UiPrefs.uiFont.value
    return remember(id) {
        when (id) {
            "sans" -> FontFamily(Font(xyz.linplayer.app.R.font.noto_sans_sc_regular))
            "serif" -> FontFamily(Font(xyz.linplayer.app.R.font.noto_serif_sc_regular))
            else -> null
        }
    }
}

/** 可选色系不改变静态模式的影院底色；Monet使用系统动态中性色与强调色。 */
internal fun phonePalette(base: LpColors, color: String, dynamic: ColorScheme? = null): LpColors {
    if (color == "monet" && dynamic != null) return base.copy(
        bg = dynamic.background, fg = dynamic.onBackground,
        fg2 = dynamic.onSurfaceVariant, fg3 = dynamic.onSurfaceVariant,
        acc = dynamic.primary, accFg = dynamic.onPrimary,
        accDim = dynamic.primary.copy(alpha = if (base.isDark) .18f else .12f),
    )
    val accent = when (color) {
        "blue" -> if (base.isDark) Color(0xFF9BBAFF) else Color(0xFF315DA8)
        "green" -> if (base.isDark) Color(0xFF82D6B0) else Color(0xFF216B4A)
        "purple" -> if (base.isDark) Color(0xFFCAB0FF) else Color(0xFF71449C)
        else -> return base
    }
    return base.copy(acc = accent, accFg = if (base.isDark) Color(0xFF151019) else Color.White,
        accDim = accent.copy(alpha = if (base.isDark) .18f else .12f))
}

/** 深浅模式与手机色系独立；TV调用仍使用默认配色。 */
@Composable
fun LpTheme(
    darkOverride: Boolean? = null,
    color: String = "amber",
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = darkOverride ?: systemDark
    val ctx = LocalContext.current
    val dynamic = if (color == "monet" && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else null
    val c = phonePalette(if (dark) DarkColors else LightColors, color, dynamic)
    var motion by remember(ctx) { mutableFloatStateOf(animatorScale(ctx)) }
    DisposableEffect(ctx) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motion = animatorScale(ctx) }
        }
        ctx.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { ctx.contentResolver.unregisterContentObserver(observer) }
    }

    // M3 的 ColorScheme 仍然要给:M3 组件(Slider / Switch / Chip)读的是它。
    // 我们自己的组件读 LocalLpColors,两套值必须一致,否则同一屏上会出现两种蓝
    // 自定义玻璃层保留透明度，标准控件的表面则先合成成不透明色。
    val surface = c.s1.compositeOver(c.bg)
    val surfaceAlt = c.s2.compositeOver(c.bg)
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.acc, onPrimary = c.accFg, primaryContainer = c.accDim.compositeOver(c.bg), onPrimaryContainer = c.fg,
        secondary = c.acc, onSecondary = c.accFg, secondaryContainer = surfaceAlt, onSecondaryContainer = c.fg,
        tertiary = c.acc, onTertiary = c.accFg, tertiaryContainer = surfaceAlt, onTertiaryContainer = c.fg,
        inversePrimary = c.acc, background = c.bg, onBackground = c.fg,
        surface = surface, onSurface = c.fg, surfaceVariant = surfaceAlt, onSurfaceVariant = c.fg2,
        surfaceContainerLowest = c.bg, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = surfaceAlt, surfaceContainerHighest = c.s3.compositeOver(c.bg),
        outline = c.line2, error = c.bad,
    )

    // 公共文字与M3组件共用完整的字体、行高和字距，避免只改字号后继承正文行高。
    val family = userFontFamily()
    val typo = remember(family) { lpTypography(family) }
    CompositionLocalProvider(LocalLpColors provides c, LocalMotionScale provides motion) {
        MaterialTheme(colorScheme = scheme, typography = typo, shapes = Shapes(
            extraSmall = RoundedCornerShape(R.sm), small = RoundedCornerShape(R.md),
            medium = RoundedCornerShape(R.lg), large = RoundedCornerShape(R.xl), extraLarge = RoundedCornerShape(R.xl),
        )) {
            CompositionLocalProvider(
                LocalTextStyle provides typo.bodyLarge,
                content = content,
            )
        }
    }
}

/** 系统的动画时长倍率。开发者选项里关掉动画时是 0f。 */
private fun animatorScale(ctx: android.content.Context): Float = runCatching {
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f)

/** 本项目自己的 token 入口。写 `Lp.colors.acc` 而不是 `MaterialTheme.colorScheme.primary`。 */
object Lp {
    val colors: LpColors
        @Composable get() = LocalLpColors.current
}

@Suppress("unused")
internal val sdkAtLeast31 = Build.VERSION.SDK_INT >= 31
