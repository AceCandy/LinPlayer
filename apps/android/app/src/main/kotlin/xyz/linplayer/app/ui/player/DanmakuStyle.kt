package xyz.linplayer.app.ui.player

import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.runtime.mutableStateOf
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.boolOrNull
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.ui.pages.args

/**
 * 当前安装全局生效的弹幕显示设置。
 *
 * ## 分工
 *
 * **排版在核心层**(`core/player/danmakustyle.go`:合并、分轨、排不下的丢掉),
 * **绘制在 View 层**([DanmakuLayer])。这个对象是中间那一层:读回落库的值、
 * 把改动发过去、拿回排好版的那一份交给绘制层。
 *
 * ★ 设置一改就要**重新取一次排版** —— 缩放、速度、范围、行数、合并这五项
 *   一改都会换一套分轨结果。不重取的表现是读数变了而画面没变。
 */
object DanmakuStyle {
    val source = mutableStateOf("")
    val offset = mutableDoubleStateOf(0.0)

    suspend fun setOffset(app: AppState, seconds: Double) {
        try {
            val r = app.call("player.danmakuOffset", args("seconds" to seconds.coerceIn(-600.0, 600.0))).obj()
            currentCoroutineContext().ensureActive()
            offset.doubleValue = r.dbl("seconds") ?: 0.0
            reloadLayout(app)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { app.report(e) }
    }


    /** 所有弹幕占画面高度:0.25 / 0.5 / 0.75 / 1.0。 */
    val area = mutableDoubleStateOf(1.0)

    /** 字号倍率 0.1..3.0。行高跟着一起缩。 */
    val scale = mutableDoubleStateOf(1.0)

    /** 不透明度 0.1..1.0。 */
    val opacity = mutableDoubleStateOf(1.0)

    /** 滚动速度倍率 0.1..3.0。 */
    val speed = mutableDoubleStateOf(1.0)

    /** 置顶 / 置底最多几行。**0 是合法值** = 这一类不显示。 */
    val rollLines = mutableIntStateOf(20)
    val preventOverlap = mutableStateOf(true)
    val topLines = mutableIntStateOf(10)
    val bottomLines = mutableIntStateOf(10)

    val merge = mutableStateOf(false)
    val bold = mutableStateOf(false)
    val heatmap = mutableStateOf(false)

    /** 弹幕总开关。和样式分开落库(`prefs.danmaku_enabled`)。 */
    val enabled = mutableStateOf(false)

    val loaded = mutableStateOf(false)
    val loadError = mutableStateOf<String?>(null)
    val saveRevision = mutableIntStateOf(0)
    private val styleMutex = Mutex()
    private var savedStyle = JsonObject(emptyMap())
    private val pendingStyle = mutableMapOf<String, JsonElement>()

    /** 排好版的那一份。null = 还没灌语料。 */
    val layout = mutableStateOf<DmLayout?>(null)
    private var layoutRequest = 0L

    /** 重新取一次排版。灌完语料、改完设置都要调。 */
    suspend fun reloadLayout(app: AppState, clear: Boolean = true) {
        val request = ++layoutRequest
        val generation = DanmakuSession.generation
        if (clear) layout.value = null
        val result = runCatching { app.call("player.danmakuLayout", args("prepare" to true)) }.getOrNull().obj()
        currentCoroutineContext().ensureActive()
        if (request != layoutRequest || generation != DanmakuSession.generation) return
        val parsed = withContext(kotlinx.coroutines.Dispatchers.Default) { parseDmLayout(result) }
        if (request != layoutRequest || generation != DanmakuSession.generation) return
        if (parsed != null) layout.value = parsed
        else if (!clear) app.toast("弹幕设置已保存，画面更新失败，请重新打开设置重试")
    }

    suspend fun load(app: AppState) = styleMutex.withLock {
        loaded.value = false
        loadError.value = null
        try {
            val r = app.call("player.getDanmakuStyle").obj() ?: error("弹幕样式为空")
            val prefs = app.call("prefs.getPrefs").obj() ?: error("弹幕偏好为空")
            currentCoroutineContext().ensureActive()
            savedStyle = r
            applyStyle(JsonObject(r + pendingStyle))
            enabled.value = prefs.bool("danmaku_enabled")
            loaded.value = true
            reloadLayout(app)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (e: Exception) { loadError.value = "弹幕设置读取失败" }
    }

    /** 即时回显输入；在途保存之后只提交后续字段的最新值。 */
    suspend fun set(app: AppState, key: String, value: Any) = set(app, key) { value }

    /** 步进以最新意图累加，旧回执不覆盖新输入；关闭面板仍完成已登记的保存。 */
    suspend fun set(app: AppState, key: String, next: () -> Any) {
        currentCoroutineContext().ensureActive()
        val update = when (val value = next()) {
            // 用户改区域时交还自动排布，避免旧的零行数/低行数继续限制显示。
            is Double -> if (key == "area") args(key to value, "roll_lines" to 20,
                "top_lines" to 20, "bottom_lines" to 20) else args(key to value)
            is Int -> args(key to value.toDouble())
            is Boolean -> args(key to value)
            else -> return
        }
        pendingStyle.putAll(update)
        applyStyle(update)
        withContext(NonCancellable) { styleMutex.withLock {
            var reflow = false
            while (pendingStyle.isNotEmpty()) {
                val changes = JsonObject(pendingStyle.toMap())
                pendingStyle.clear()
                try {
                    val result = app.call("player.setDanmakuStyle", changes).obj() ?: error("弹幕样式为空")
                    reflow = reflow || changes.any { (name, value) -> name != "opacity" && name != "heatmap" && savedStyle[name] != value }
                    savedStyle = result
                } catch (e: Exception) { app.report(e) }
                finally {
                    applyStyle(JsonObject(savedStyle + pendingStyle))
                    saveRevision.intValue++
                }
            }
            if (reflow) reloadLayout(app, clear = false)
        } }
    }

    private fun applyStyle(r: JsonObject) {
        r.dbl("area")?.let { area.doubleValue = it }
        r.dbl("scale")?.let { scale.doubleValue = it }
        r.dbl("opacity")?.let { opacity.doubleValue = it }
        r.dbl("speed")?.let { speed.doubleValue = it }
        r.long("roll_lines")?.let { rollLines.intValue = it.toInt() }
        r.long("top_lines")?.let { topLines.intValue = it.toInt() }
        r.long("bottom_lines")?.let { bottomLines.intValue = it.toInt() }
        r.boolOrNull("prevent_overlap")?.let { preventOverlap.value = it }
        r.boolOrNull("merge")?.let { merge.value = it }
        r.boolOrNull("bold")?.let { bold.value = it }
        r.boolOrNull("heatmap")?.let { heatmap.value = it }
    }

    /* ---- 纯函数。拆出来是为了可测 —— 这两条规则的全部内容就是这几行。 ---- */

    /** 范围是**四档枚举**不是连续值:点一下换下一档,1/4 → 1/2 → 3/4 → 全屏。 */
    fun nextArea(cur: Double): Double = when {
        cur <= 0.3 -> 0.5
        cur <= 0.6 -> 0.75
        cur <= 0.85 -> 1.0
        else -> 0.25
    }

    /**
     * 步进并**收掉浮点误差**:0.1 一直加会攒出 1.7000000000000002,
     * 而那个数原样发给核心层、原样显示回来,读数上就是「1.7000000000000002×」。
     */
    fun step(cur: Double, up: Boolean, by: Double, min: Double, max: Double): Double =
        Math.round((cur + if (up) by else -by).coerceIn(min, max) * 100) / 100.0

    fun areaLabel(cur: Double): String = when {
        cur <= 0.3 -> "1/4 屏"
        cur <= 0.6 -> "1/2 屏"
        cur <= 0.85 -> "3/4 屏"
        else -> "全屏"
    }
}
