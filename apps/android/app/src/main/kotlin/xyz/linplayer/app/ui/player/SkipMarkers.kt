package xyz.linplayer.app.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.fmtTime

/** 手机和 TV 共用标记命令；作用域继承和区间校验由核心决定。 */
internal class SkipMarkers(private val app: AppState, private val itemId: String) {
    var data by mutableStateOf<JsonObject?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val scope get() = data.str("scope") ?: "auto"
    val canSeason get() = data.bool("can_season")
    val ready get() = data != null && !busy
    fun label(part: String): String = data?.get(part).obj()?.let {
        "${fmtTime(it.dbl("start") ?: 0.0)} — ${fmtTime(it.dbl("end") ?: 0.0)}"
    } ?: "未设置"

    suspend fun load(duration: Double, scope: String = this.scope) {
        if (busy) return
        busy = true; error = null
        try {
            val value = app.call("player.getSkipRange", args("item_id" to itemId, "scope" to scope, "runtime_secs" to duration)).obj()
            currentCoroutineContext().ensureActive()
            checkNotNull(value) { "标记读取失败" }
            data = value
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) { error = e.message ?: "标记读取失败" }
        finally { busy = false }
    }

    suspend fun mark(action: String, position: Double, duration: Double, onSaved: () -> Unit, onWriting: (Boolean) -> Unit = {}) {
        if (!ready) return
        busy = true; error = null
        onWriting(true)
        try {
            val value = app.call("player.setSkipRange", args("item_id" to itemId, "scope" to scope,
                "action" to action, "position" to position, "runtime_secs" to duration)).obj()
            currentCoroutineContext().ensureActive()
            checkNotNull(value) { "标记保存失败" }
            data = value
            onSaved()
            app.toast(if (action == "clear") "已清除，恢复继承的设置" else "已保存")
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) { error = e.message ?: "标记保存失败" }
        finally { busy = false; onWriting(false) }
    }
}

/** 有片尾后续内容时，整段尾部保留给续播，不让末尾倒计时抢走操作。 */
internal fun nextUpForOutro(position: Double, duration: Double, outro: ClosedFloatingPointRange<Double>?): Boolean =
    if (!position.isFinite() || position < 0 || !duration.isFinite() || duration <= 0) false
    else if (outro != null) outro.endInclusive >= duration && position >= outro.start
    else nextUpVisible(position, duration)
