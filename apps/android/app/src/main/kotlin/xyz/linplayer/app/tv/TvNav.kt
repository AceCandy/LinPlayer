package xyz.linplayer.app.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag

/** TV 播放器自己的路由:浏览页已走共享 NavHost,这里只剩播放页换集 / 换版本时的栈。 */
sealed interface TvRoute {
    /** 栈底哨兵:播放页 pop 到它就是退出播放。 */
    data object Home : TvRoute
    data class Player(
        val itemId: String, val title: String,
        /** 只有用户**真按过确认**才有值(§7.6 picked);为空就不传,交给核心层的版本正则。 */
        val versionId: String? = null,
        val audioIndex: Long? = null, val subIndex: Long? = null, val subOff: Boolean = false,
        val engine: String? = null, val fromStart: Boolean = false,
        /** 本机文件夹里的视频走 `source.play`,不走 `player.play`。 */
        val localEntry: Boolean = false,
        /** 下载完成的任务走 `player.playLocal`(itemId 是任务 id)。 */
        val download: Boolean = false,
        /** 换内核 / 换线路 / 换版本重播时接着当前位置,不回到服务端记的进度。 */
        val resumeAt: Double? = null,
        /** 数据源播放:`{server_id, item, line_id, episode_id}` 的 JSON 原文,走 source.playItem。 */
        val src: String? = null,
        /** 共用详情页预选的字幕身份，随本次版本交接。 */
        val subtitle: String? = null,
    ) : TvRoute
}

/** 播放页的返回栈;每一格带自己的焦点记忆。 */
class TvNav {
    class Entry(val id: Long, val route: TvRoute) {
        val memory = FocusMemory()
    }

    private var seq = 0L
    val stack = mutableStateListOf(Entry(seq++, TvRoute.Home))
    val top: Entry get() = stack.last()

    fun push(r: TvRoute) { stack.add(Entry(seq++, r)) }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

val LocalNav = staticCompositionLocalOf<TvNav> { error("TvNav 还没提供") }

/**
 * 一页的焦点记忆(§4.2):从下一级返回 / 面板关闭时,焦点回到打开它的那个元素。
 *
 * ☠ 面板一卸载焦点在树上没有落点 = **遥控器整个失灵**,TV 最经典的 P0。
 * ★ 记的是**键**不是 FocusRequester:页面被销毁重建之后,旧的 requester 已经不在树上。
 */
class FocusMemory {
    var key: String? = null
    internal val requesters = HashMap<String, FocusRequester>()

    fun restore(): Boolean {
        val fr = key?.let { requesters[it] } ?: return false
        return runCatching { fr.requestFocus() }.getOrDefault(false)
    }
}

val LocalFocusMemory = staticCompositionLocalOf { FocusMemory() }

/**
 * 给一个可聚焦元素挂上记忆。[initial] = 这一页的初始焦点(§7 各页规定的那个元素)。
 *
 * ★ 只在**这个元素第一次组合时**要焦点:异步回来的内容不许抢焦点(§4.2)——
 *   用户已经把焦点挪到别处时 [FocusMemory.key] 非空,初始焦点就不再生效。
 * ☠ Lazy 列表的项第一帧还没挂上焦点树,`requestFocus` 静默失败,所以按帧重试。
 */
@Composable
fun Modifier.memo(key: String, initial: Boolean = false): Modifier {
    val mem = LocalFocusMemory.current
    val fr = remember(key) { FocusRequester() }
    DisposableEffect(key) {
        mem.requesters[key] = fr
        onDispose { if (mem.requesters[key] === fr) mem.requesters.remove(key) }
    }
    LaunchedEffect(key) {
        if (mem.key != key && !(initial && mem.key == null)) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (runCatching { fr.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    return this.testTag(key).focusRequester(fr).onFocusChanged { if (it.isFocused) mem.key = key }
}
