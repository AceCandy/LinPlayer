package xyz.linplayer.app.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.Modifier
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.ui.theme.LpEasing
import xyz.linplayer.app.ui.theme.T
import xyz.linplayer.app.ui.theme.lpTween
import xyz.linplayer.app.ui.theme.lpSpring
import xyz.linplayer.app.ui.theme.LocalMotionScale

/** 只关联实际点中的卡片；同片在不同栏目出现时不抢同一个共享元素。URL只存本次导航内存。 */
internal data class PosterLink(val sourceEntry: String, val token: String, val item: Item, val imageUrl: String?)
internal data class PosterScene(val entry: NavBackStackEntry, val visibility: AnimatedVisibilityScope)
internal val LocalPosterScene = compositionLocalOf<PosterScene?> { null }
internal val LocalPosterMotion = compositionLocalOf<PosterMotion?> { null }

/** 只给首批就绪海报短错峰，后续滚动和慢图片不排队。 */
internal class PosterEntranceBatch {
    private var startedAt: Long? = null
    private var count = 0
    fun delayMillis(now: Long = SystemClock.uptimeMillis()): Long {
        val start = startedAt ?: now.also { startedAt = it }
        if (now - start > T.T3 || count >= 9) return 0
        return (count++ % 3 * (T.T1 / 2)).toLong()
    }
}
private val LocalPosterBatch = compositionLocalOf<PosterEntranceBatch?> { null }
internal val LocalPosterScroll = compositionLocalOf<PosterScrollMotion?> { null }

/** 手机根滚动观察器：只切换快滚标记，不逐帧重组海报、不消费手势。 */
internal class PosterScrollMotion(private val density: Float) : NestedScrollConnection {
    var fast by mutableStateOf(false)
        private set
    private var lastScrollAt = 0L
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        observe(consumed)
        return Offset.Zero
    }
    fun observe(consumed: Offset, now: Long = SystemClock.uptimeMillis()) {
        val distance = maxOf(kotlin.math.abs(consumed.x), kotlin.math.abs(consumed.y)) / density
        if (distance == 0f) return
        val elapsed = now - lastScrollAt
        if (distance >= 32f || (elapsed in 1..T.T2.toLong() && distance / elapsed > 1.8f)) fast = true
        lastScrollAt = now
    }
    fun settle(now: Long = SystemClock.uptimeMillis()) {
        if (now - lastScrollAt >= T.T3) fast = false
    }
}

internal class PosterMotion(
    val nav: NavController,
    val shared: SharedTransitionScope,
    val links: MutableMap<String, PosterLink>,
) {
    fun open(source: NavBackStackEntry, item: Item, token: String, url: String?, action: () -> Unit) {
        action()
        val target = nav.currentBackStackEntry ?: return
        if (target.id == source.id || !target.destination.hasRoute<Route.Detail>()) return
        if (target.toRoute<Route.Detail>().itemId == item.id) links[target.id] = PosterLink(source.id, token, item, url)
    }
}

/** 共享范围包住整个NavHost，退出转场仍可见的entry保留关联，结束后随栈清理。 */
@Composable
internal fun PosterMotionHost(nav: NavController, account: Pair<String, String>? = null, content: @Composable () -> Unit) {
    SharedTransitionLayout {
        val links = remember(account) { mutableStateMapOf<String, PosterLink>() }
        val motion = remember(nav, this, account) { PosterMotion(nav, this, links) }
        LaunchedEffect(nav, account) {
            nav.visibleEntries.collect { visible ->
                val retained = (visible + nav.currentBackStack.value).map { it.id }.toSet()
                links.keys.toList().filterNot { it in retained }.forEach(links::remove)
            }
        }
        CompositionLocalProvider(LocalPosterMotion provides motion, content = content)
    }
}

/** 沿用原导航回调，只给destination子树提供共享元素所需的可见范围。 */
internal inline fun <reified T : Any> NavGraphBuilder.posterComposable(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        val visibility = this
        val scene = remember(entry, visibility) { PosterScene(entry, visibility) }
        val batch = remember(entry) { PosterEntranceBatch() }
        CompositionLocalProvider(LocalPosterScene provides scene, LocalPosterBatch provides batch) { content(visibility, entry) }
    }
}

/** 只有选中的源卡片与目标详情进入共享布局，空闲列表不承担共享测量。 */
@Composable
internal fun Modifier.sharedPoster(itemId: String, sourceToken: String? = null): Modifier {
    val motion = LocalPosterMotion.current ?: return this
    val scene = LocalPosterScene.current ?: return this
    if (sourceToken != null && motion.links.values.none {
        it.sourceEntry == scene.entry.id && it.token == sourceToken && !it.imageUrl.isNullOrBlank()
    }) return this
    val link = if (sourceToken == null) motion.links[scene.entry.id]?.takeIf {
        it.item.id == itemId && !it.imageUrl.isNullOrBlank()
    } else null
    val token = sourceToken ?: link?.token ?: return this
    val source = if (sourceToken != null) scene.entry.id else link!!.sourceEntry
    val spec = lpTween<Rect>(T.T7, LpEasing.emphasizedDecelerate)
    return with(motion.shared) {
        this@sharedPoster.sharedElement(
            rememberSharedContentState("$source/$token"), scene.visibility,
            boundsTransform = { _, _ -> spec },
        )
    }
}

/** 用来源已解码图托住目标尺寸图片加载，详情异步请求不阻塞海报转场。 */
@Composable
internal fun posterPreview(itemId: String): String? {
    val motion = LocalPosterMotion.current ?: return null
    val scene = LocalPosterScene.current ?: return null
    return motion.links[scene.entry.id]?.takeIf { it.item.id == itemId }?.imageUrl
}

/** 仅供详情头部展示；不得代替真实详情驱动播放、选集或插件数据。 */
@Composable
internal fun detailPreview(itemId: String): Item? {
    val motion = LocalPosterMotion.current ?: return null
    val scene = LocalPosterScene.current ?: return null
    return motion.links[scene.entry.id]?.takeIf { it.item.id == itemId }?.item
}

/** 首次入场与横滑边缘形变合成在绘制层；已加载海报往返仍有连续形变。 */
@Composable
internal fun Modifier.homePosterEntrance(
    itemId: String, index: Int, account: Pair<String, String>, row: LazyListState,
): Modifier = key(account, itemId) {
    var seen by rememberSaveable { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val progress = remember { Animatable(if (seen) 1f else 0f) }
    val scale = LocalMotionScale.current
    val spec = lpSpring<Float>(.001f)
    val travel = with(LocalDensity.current) { 22.dp.toPx() }
    LaunchedEffect(scale, spec) {
        if (seen) progress.snapTo(1f) else {
            if (scale <= 0f) progress.snapTo(1f) else progress.snapTo(0f)
            snapshotFlow { visible }.first { it }
            seen = true
            if (scale <= 0f) progress.snapTo(1f) else {
                delay((index % 3 * (T.T1 / 2) * scale).toLong())
                progress.animateTo(1f, spec)
            }
        }
    }
    onGloballyPositioned {
        val bounds = it.boundsInWindow()
        val area = it.size.width.toFloat() * it.size.height
        visible = area > 0 && bounds.width * bounds.height >= area * .15f
    }.graphicsLayer {
        val remaining = if (scale <= 0f) 0f else 1f - progress.value
        val layout = row.layoutInfo
        val card = layout.visibleItemsInfo.firstOrNull { it.index == index }
        val edge = if (scale <= 0f || card == null || card.size == 0) 0f else {
            val left = layout.viewportStartOffset + layout.beforeContentPadding
            val right = layout.viewportEndOffset - layout.afterContentPadding
            val clipped = maxOf(left - card.offset, card.offset + card.size - right, 0)
            (clipped.toFloat() / card.size).coerceIn(0f, 1f)
        }
        val bend = edge * edge * (3f - 2f * edge)
        transformOrigin = TransformOrigin(.5f, 1f)
        translationY = travel * maxOf(remaining, bend * .65f)
        scaleX = 1f - maxOf(.10f * remaining, .12f * bend)
        scaleY = scaleX
        alpha = (1f - .14f * remaining).coerceIn(0f, 1f)
    }
}

/** 图片就绪且15%可见后整卡弹簧入场；占位始终可见，返回不重播。 */
@Composable
internal fun Modifier.posterEntrance(itemId: String, url: String?, ready: Boolean = true, animate: Boolean = true): Modifier {
    var seen by rememberSaveable(itemId, url) { mutableStateOf(false) }
    var visible by remember(itemId, url) { mutableStateOf(false) }
    val progress = remember(itemId, url) { Animatable(if (seen) 1f else 0f) }
    val spec = lpSpring<Float>(.001f)
    val scale = LocalMotionScale.current
    val batch = LocalPosterBatch.current
    val fast = LocalPosterScroll.current?.fast == true
    val travel = with(LocalDensity.current) { 20.dp.toPx() }
    LaunchedEffect(itemId, url, visible, ready, animate, fast, spec) {
        when {
            scale <= 0f || !animate || fast -> {
                if (visible && (ready || fast || scale <= 0f)) seen = true
                progress.snapTo(1f)
            }
            visible && ready -> {
                if (!seen) {
                    delay(((batch?.delayMillis() ?: 0L) * scale).toLong())
                    seen = true
                    progress.animateTo(1f, spec)
                } else progress.snapTo(1f)
            }
            seen -> progress.snapTo(1f)
        }
    }
    return onGloballyPositioned {
        val bounds = it.boundsInWindow()
        val area = it.size.width.toFloat() * it.size.height
        visible = area > 0 && bounds.width * bounds.height >= area * .15f
    }.graphicsLayer {
        scaleX = .94f + .06f * progress.value
        scaleY = scaleX
        translationY = travel * (1f - progress.value)
    }
}
