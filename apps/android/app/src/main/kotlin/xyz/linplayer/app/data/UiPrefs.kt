package xyz.linplayer.app.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * **纯呈现的、只属于这一台设备的**偏好。
 *
 * 规矩是「一切持久化归核心层」(`SPEC.md` §8.5),这里是一个有边界的例外:
 * 深浅色覆盖既没有核心层命令能存(`prefs.setPrefs` 只认
 * `audio_lang` / `sub_lang` / `sub_enabled`),也**不该**跨设备同步 ——
 * 手机上想强制深色不代表电视上也要。它和「我在哪一页、滚到哪」是同一类东西。
 *
 * ☠ **这里只放这一类。** 任何有核心层消费点的东西都不许进来:
 * 放进来的那一刻,它就变成一个「设了但核心层不知道」的开关 ——
 * 而那正是本仓库最难查的一类 bug。
 */
object UiPrefs {
    private const val FILE = "lp_ui"
    private const val K_THEME = "theme"
    private const val K_COLOR = "color_scheme"
    private const val K_ENGINE = "engine"
    private const val K_FONT = "ui_font"
    const val K_SHOT_TIME = "shot_time"
    const val K_SHOT_LOGO = "shot_logo"
    const val K_SHOT_TIME_POS = "shot_time_pos"
    const val K_SHOT_LOGO_POS = "shot_logo_pos"
    private const val K_LONG_SHOT = "long_shot"

    val hideHomeLibraries = mutableStateOf(false)
    val searchHistory = mutableStateOf<List<String>>(emptyList())

    fun setHideHomeLibraries(ctx: Context, hidden: Boolean) {
        hideHomeLibraries.value = hidden
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("hide_home_libraries", hidden).apply()
    }

    /** 只记录明确提交的关键词，保持最近顺序，最多10条。 */
    fun recordSearch(ctx: Context, text: String) {
        val word = text.trim()
        if (word.isEmpty()) return
        searchHistory.value = (listOf(word) + searchHistory.value).distinct().take(10)
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("search_history", org.json.JSONArray(searchHistory.value).toString()).apply()
    }

    /** `system` / `dark` / `light`。 */
    val theme = mutableStateOf("system")

    /** 色系与深浅模式独立，只影响本机手机呈现。 */
    val colorScheme = mutableStateOf("amber")
    val colorOptions = listOf("橙金" to "amber", "蓝色" to "blue", "绿色" to "green", "紫色" to "purple", "Monet" to "monet")
    fun colorLabel(): String = colorOptions.firstOrNull { it.second == colorScheme.value }?.first ?: "橙金"
    fun setColorScheme(ctx: Context, value: String) {
        colorScheme.value = value.takeIf { id -> colorOptions.any { it.second == id } } ?: "amber"
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(K_COLOR, colorScheme.value).apply()
    }

    /**
     * 内置界面字体ID，空为系统默认；不再读取用户文件路径。
     *
     * ★ 它进这里而不是核心层:字体得在**第一帧之前**就拿得到。走核心层是一次异步调用,
     *   表现是每次冷启动先用默认字体画一屏、再整页换字 —— 那比不给这个功能还难看。
     */
    val uiFont = mutableStateOf("")
    val fontOptions = listOf("系统默认" to "", "思源黑体" to "sans", "思源宋体" to "serif")
    fun fontLabel(): String = fontOptions.firstOrNull { it.second == uiFont.value }?.first ?: "系统默认"

    /**
     * 播放键短按模式:`mpv` / `exo` / `auto`。自动先试 Media3，兼容失败时回退 MPV。
     *
     * ★ 它进这里是因为**「哪个内核能在这台机器上出画面」是设备属性,不是账号属性**
     *   —— 手机上 mpv 出「有声音没画面」不代表电视上也会。跨设备同步它反而害人。
     * ★ 值只是**发给 `player.play` 的一个参数**,核心层不存它,所以不违反
     *   「一切持久化归核心层」:这里没有第二个真相。
     */
    val engine = mutableStateOf("mpv")
    val engineOptions = listOf("自动" to "auto", "Media3" to "exo", "MPV" to "mpv")
    fun engineLabel(of: String = engine.value): String = engineOptions.firstOrNull { it.second == of }?.first ?: "MPV"

    /**
     * 截屏叠加【用户定 2026-09-07】:要不要压上系统时间 / 条目艺术字,各自摆在哪一角。
     *
     * ★ 它们进这里的理由和主题一样:**核心层没有消费点**。截屏整条路
     *   (PixelCopy → 叠字 → 写相册)都在 Kotlin 这一侧,核心层不知道也不需要知道。
     * ★ 位置用四角的字母码 `tl/tr/bl/br` —— 存中文标签的话改一次文案就把用户的设置弄丢了。
     */
    val shotTime = mutableStateOf(true)
    val shotLogo = mutableStateOf(false)
    val shotTimePos = mutableStateOf("br")
    val shotLogoPos = mutableStateOf("tl")

    /**
     * 可滚动的页面上显示「截长屏」按钮【用户 2026-09-10:「方便截图演示」】。
     *
     * ★ 默认关:它是给演示用的,常驻一颗浮标会挡住内容右下角。
     */
    val longShot = mutableStateOf(false)

    /*
     * TV 播放手感(UI_TV.md §7.13「播放」分类后四项)。核心层没有消费点:
     * 快进步长、长按倍速、自动下一集都是遥控器这一侧的行为;选集栏视图是纯呈现(§7.6「持久化在本机」)。
     */
    val tvSeekStep = mutableStateOf(10)
    val tvHoldSpeed = mutableStateOf(3.0)
    val tvAutoNext = mutableStateOf(true)
    /** `compact` / `detail`。 */
    val tvEpisodeView = mutableStateOf("compact")

    /**
     * 壁纸的模糊度与压暗(0~1,官方设置项,对任何壁纸生效,SPEC 11.5)。
     *
     * ★ 进这里而不是核心层:`prefs.setPrefs` 是白名单式的,没有这两项 ——
     *   往它塞等于一个「设了核心层不知道」的开关。糊多少也是这台设备的事。
     */
    val wallBlur = mutableStateOf(0f)
    val wallDim = mutableStateOf(0f)

    fun setWall(ctx: Context, blur: Float, dim: Float) {
        wallBlur.value = blur.coerceIn(0f, 1f)
        wallDim.value = dim.coerceIn(0f, 1f)
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat("wall_blur", wallBlur.value).putFloat("wall_dim", wallDim.value).apply()
    }

    fun setTv(ctx: Context, key: String, v: Any) {
        val e = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        when (key) {
            "tv_seek_step" -> { tvSeekStep.value = v as Int; e.putInt(key, v) }
            "tv_hold_speed" -> { tvHoldSpeed.value = v as Double; e.putFloat(key, v.toFloat()) }
            "tv_auto_next" -> { tvAutoNext.value = v as Boolean; e.putBoolean(key, v) }
            "tv_episode_view" -> { tvEpisodeView.value = v as String; e.putString(key, v) }
        }
        e.apply()
    }

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        hideHomeLibraries.value = sp.getBoolean("hide_home_libraries", false)
        searchHistory.value = runCatching {
            val array = org.json.JSONArray(sp.getString("search_history", "[]"))
            (0 until array.length()).map { array.getString(it) }.filter { it.isNotBlank() }.distinct().take(10)
        }.getOrDefault(emptyList())
        theme.value = sp.getString(K_THEME, "system") ?: "system"
        colorScheme.value = sp.getString(K_COLOR, "amber").takeIf { id -> colorOptions.any { it.second == id } } ?: "amber"
        engine.value = sp.getString(K_ENGINE, "mpv") ?: "mpv"
        val font = sp.getString(K_FONT, "").orEmpty()
        uiFont.value = font.takeIf { v -> fontOptions.any { it.second == v } }.orEmpty()
        if (font != uiFont.value) sp.edit().putString(K_FONT, uiFont.value).apply()
        shotTime.value = sp.getBoolean(K_SHOT_TIME, true)
        shotLogo.value = sp.getBoolean(K_SHOT_LOGO, false)
        shotTimePos.value = sp.getString(K_SHOT_TIME_POS, "br") ?: "br"
        shotLogoPos.value = sp.getString(K_SHOT_LOGO_POS, "tl") ?: "tl"
        longShot.value = sp.getBoolean(K_LONG_SHOT, false)
        tvSeekStep.value = sp.getInt("tv_seek_step", 10)
        tvHoldSpeed.value = sp.getFloat("tv_hold_speed", 3f).toDouble()
        tvAutoNext.value = sp.getBoolean("tv_auto_next", true)
        tvEpisodeView.value = sp.getString("tv_episode_view", "compact") ?: "compact"
        wallBlur.value = sp.getFloat("wall_blur", 0f)
        wallDim.value = sp.getFloat("wall_dim", 0f)
    }

    fun setShotFlag(ctx: Context, key: String, v: Boolean) {
        (if (key == K_SHOT_TIME) shotTime else shotLogo).value = v
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(key, v).apply()
    }

    fun setLongShot(ctx: Context, v: Boolean) {
        longShot.value = v
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(K_LONG_SHOT, v).apply()
    }

    fun setShotPos(ctx: Context, key: String, v: String) {
        (if (key == K_SHOT_TIME_POS) shotTimePos else shotLogoPos).value = v
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, v).apply()
    }

    fun setTheme(ctx: Context, v: String) {
        theme.value = v
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(K_THEME, v).apply()
    }

    /**
     * 长按播放键用的内核 —— 短按那个的**另一个**。
     *
     * 自动模式的首选是 Media3，因此长按明确用 MPV，不触发自动回退。
     */
    fun otherEngine(of: String = engine.value): String = if (of == "mpv") "exo" else "mpv"

    fun setFont(ctx: Context, id: String) {
        require(fontOptions.any { it.second == id })
        uiFont.value = id
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(K_FONT, id).apply()
    }

    fun setEngine(ctx: Context, v: String) {
        engine.value = v
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(K_ENGINE, v).apply()
    }
}
