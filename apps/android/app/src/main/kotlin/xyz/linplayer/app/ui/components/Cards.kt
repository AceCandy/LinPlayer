package xyz.linplayer.app.ui.components

import xyz.linplayer.app.ui.theme.LpText

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import java.util.UUID
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.request.allowHardware
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.ui.theme.LpEasing
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LocalMotionScale
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp
import xyz.linplayer.app.ui.theme.T
import xyz.linplayer.app.ui.theme.lpTween

/**
 * 网络图。
 *
 * ☠ **必须看 `painter.state`。** 只画 `AsyncImage` 不看 state 的话,占位可能永不消失
 * 或图片就绪状态不更新 —— 那正是历史故障「封面隐身」在 Compose 上的等价漏法
 * (旧栈是手抄卡片时漏了「解码完成 → 加就绪标记」这一步)。
 */
@Composable
fun NetImage(
    url: String?,
    desc: String?,
    m: Modifier = Modifier,
    corner: androidx.compose.ui.unit.Dp = R.md,
    scale: ContentScale = ContentScale.Crop,
    backgroundBlur: Boolean = false,
    previewUrl: String? = null,
    /** 共享海报已在转场中移动，保持图像连续，不叠加重新加载式的淡入。 */
    reveal: Boolean = true,
    onLoadResult: ((Boolean) -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
) {
    val report by androidx.compose.runtime.rememberUpdatedState(onLoadResult)
    LaunchedEffect(url) { if (url.isNullOrEmpty()) report?.invoke(false) }
    var visible by remember { mutableStateOf(false) }
    val viewport = if (backgroundBlur) Modifier else Modifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        val area = it.size.width.toFloat() * it.size.height
        val shown = bounds.width * bounds.height
        // 到15%才开始，完全离屏才复位；边缘小幅滚动不反复闪图。
        if (area > 0f && shown >= area * .15f) visible = true
        else if (shown <= 0f) visible = false
    }
    Box(m.clip(RoundedCornerShape(corner)).then(viewport), contentAlignment = Alignment.Center) {
        if (url.isNullOrEmpty()) {
            // 没有地址就画一块占位底。**不画骨架** —— 骨架的意思是「在路上」,
            // 而这里是「压根没有」,两者在界面上必须能分开
            if (placeholder != null) placeholder()
            else Box(Modifier.fillMaxSize().background(Lp.colors.s3))
            if (xyz.linplayer.app.BuildConfig.DEBUG) {
                android.util.Log.w("LinPlayer", "图片地址为空(数据通道没就绪?)desc=$desc")
            }
            return@Box
        }
        val ctx = LocalContext.current
        val model = remember(ctx, url, backgroundBlur) {
            if (backgroundBlur) ImageRequest.Builder(ctx).data(url).allowHardware(false)
                .transformations(DetailBackgroundBlur).build() else url
        }
        val painter = androidx.compose.runtime.key(url, backgroundBlur) { rememberAsyncImagePainter(model) }
        val state by androidx.compose.runtime.key(url, backgroundBlur) { painter.state.collectAsState() }
        val ready = state is AsyncImagePainter.State.Success
        val failed = state is AsyncImagePainter.State.Error
        LaunchedEffect(url, ready, failed) {
            if (ready || failed) report?.invoke(ready)
        }
        val fade = remember(url, backgroundBlur) { Animatable(0f) }
        val immediate = ready && (!reveal || LocalMotionScale.current <= 0f)
        val fadeSpec = lpTween<Float>(T.T8, LinearEasing)
        LaunchedEffect(url, ready, immediate, visible, fadeSpec) {
            when {
                immediate -> fade.snapTo(1f)
                !backgroundBlur && !visible -> fade.snapTo(0f)
                else -> fade.animateTo(if (ready) 1f else 0f, fadeSpec)
            }
        }
        val fading by remember(fade, immediate) { derivedStateOf { !immediate && fade.value < 1f } }
        val preview = previewUrl?.takeIf { it != url }?.let { previewData ->
            // 来源热图先显示；对它再次模糊会错过现成的内存缓存。
            androidx.compose.runtime.key(previewData) { rememberAsyncImagePainter(previewData) }
        }
        if (fading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                preview?.state?.collectAsState()?.value is AsyncImagePainter.State.Success ->
                    androidx.compose.foundation.Image(preview, null, Modifier.fillMaxSize(), contentScale = scale)
                placeholder != null -> placeholder()
                backgroundBlur -> Box(Modifier.fillMaxSize().background(Lp.colors.bg))
                else -> Box(Modifier.fillMaxSize().background(Lp.colors.s3))
            }
        }
        if (xyz.linplayer.app.BuildConfig.DEBUG) {
            val st = state
            if (st is AsyncImagePainter.State.Error) android.util.Log.w(
                "LinPlayer", "图片加载失败 $url", st.result.throwable)
        }
        androidx.compose.foundation.Image(
            painter = painter, contentDescription = desc, contentScale = scale,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = if (immediate) 1f else fade.value
            },
        )
    }
}

/**
 * 海报卡 / 横版卡(UI_MOBILE.md §4.3)。
 *
 * 一张卡最多同时表达五件事:封面 / 角标 / 观看进度 / 标题 / 副标题。
 * ★ **画质标签(4K/DV)整个去掉**【用户定 2026-07-28】——「没人会为了参数去看一部烂片」。
 */
@Composable
fun MediaCard(
    item: Item,
    imageUrl: String?,
    onOpen: () -> Unit,
    m: Modifier = Modifier,
    thumb: Boolean = false,
    /** 长按菜单项，null 表示该处不提供菜单。 */
    menu: List<CardAction>? = null,
    showCaption: Boolean = true,
    /** 长按交给调用方(插件的 `onLongPress`)。[menu] 在时以菜单为准:同一块区域只能有一个长按。 */
    onLongPress: (() -> Unit)? = null,
    /** 首页续播信息；其它轨道沿用普通卡片文案。 */
    resume: Boolean = false,
    /** 调用方提供首页入场时关闭默认动画，保留按压与图片淡入。 */
    entrance: Boolean = true,
    /** 首页精简文案与可信数据角标，不改变其它页卡片。 */
    homeStyle: Boolean = false,
) {
    val c = Lp.colors
    var menuOpen by remember { mutableStateOf(false) }
    val width = if (thumb) ThumbW else PosterW
    var imageResult by remember(item.id, imageUrl) { mutableStateOf<Boolean?>(null) }
    val posterToken = rememberSaveable(item.id, imageUrl) { UUID.randomUUID().toString() }
    val motion = LocalPosterMotion.current
    val scene = LocalPosterScene.current
    val sharedSource = motion != null && scene != null && motion.links.values.any {
        it.sourceEntry == scene.entry.id && it.token == posterToken
    }

    // 整卡读成一条:TalkBack 逐个念「图片」「标题」「年份」是噪音
    Column(m.width(width).then(if (entrance) Modifier.posterEntrance(item.id, imageUrl,
        ready = imageResult != null, animate = imageResult != false) else Modifier).semantics(mergeDescendants = true) { }) {
        Box {
            Box(
                Modifier.fillMaxWidth().aspectRatio(if (thumb) 16f / 9f else 2f / 3f)
                    .clip(RoundedCornerShape(R.md))
                    .pressable(
                        onClick = {
                            if (motion != null && scene != null) motion.open(scene.entry, item, posterToken, imageUrl, onOpen)
                            else onOpen()
                        },
                        onLongClick = when {
                            menu != null -> {
                                { menuOpen = true }
                            }
                            onLongPress != null -> {
                                { onLongPress() }
                            }
                            else -> null
                        },
                    )
            ) {
                NetImage(imageUrl, item.name, Modifier.fillMaxSize().sharedPoster(item.id, posterToken),
                    reveal = !sharedSource,
                    onLoadResult = if (entrance) { { imageResult = it } } else null)

                // 角标:剧集 → 未看集数,全看完 → 打勾;电影 → 评分。**角标要小**,它压在封面上
                if (homeStyle) {
                    EpisodeStatusBadge(item, Modifier.align(Alignment.TopEnd).padding(Sp.x6))
                    DoubanRatingBadge(item.doubanRating, Modifier.align(Alignment.BottomEnd).padding(Sp.x6))
                } else {
                    Badge(item, Modifier.align(Alignment.TopEnd).padding(4.dp))
                    item.rating?.takeIf { it > 0 && it.isFinite() }?.let { rating ->
                        RatingCorner(rating, Modifier.align(Alignment.BottomEnd))
                    }
                }

                // 仅续播轨道显示时长，普通海报即使有进度也不显示时间角标。
                if (resume && (item.type == "Movie" || item.isEpisode) && item.runtimeSecs > 0) {
                    val remaining = !item.played && item.resumeSecs > 0 && item.resumeSecs < item.runtimeSecs
                    val seconds = if (remaining) item.runtimeSecs - item.resumeSecs else item.runtimeSecs
                    val total = seconds.toLong().coerceAtLeast(1)
                    val duration = if (total >= 3600)
                        "%d:%02d:%02d".format(java.util.Locale.ROOT, total / 3600, total / 60 % 60, total % 60)
                    else "%d:%02d".format(java.util.Locale.ROOT, total / 60, total % 60)
                    Text(if (remaining) "剩余 $duration" else duration,
                        color = Color.White, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomStart).padding(Sp.x6)
                            .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = .65f))
                            .padding(horizontal = 5.dp, vertical = 2.dp))
                }

                // 播放进度:仅在有进度时出现
                if (item.progress > 0f) Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp)
                        .background(if (homeStyle) Color.White.copy(alpha = .24f) else c.line2)
                ) {
                    Box(Modifier.fillMaxWidth(item.progress).fillMaxSize().background(if (homeStyle) c.acc else c.mediaAccent))
                }
            }
            if (menu != null) CardMenu(menuOpen, { menuOpen = false }, menu)
        }
        if (showCaption) {
            Spacer(Modifier.height(Sp.x6))
            // 一行 + 省略号:片名长短差别极大,不收会把行高撑成两三行,整条轨道高度乱跳
            Text(item.cardTitle, color = c.fg, style = LpText.card,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = if (homeStyle) TextAlign.Start else TextAlign.Center, modifier = Modifier.fillMaxWidth())
            val subtitle = if (homeStyle) {
                if (resume && item.isEpisode && item.seasonNo != null && item.episodeNo != null)
                    "S${item.seasonNo}E${item.episodeNo}"
                else item.year?.toString().takeUnless { resume }
            } else if (resume && item.isEpisode) listOfNotNull(
                if (item.seasonNo != null && item.episodeNo != null) "S${item.seasonNo}E${item.episodeNo}" else null,
                item.name.takeIf { it.isNotBlank() && it != item.cardTitle },
            ).joinToString(" · ").takeIf { it.isNotBlank() } else item.cardSub
            subtitle?.let {
                Text(it, color = c.fg2, style = LpText.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = if (homeStyle) TextAlign.Start else TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            }
        }
    }
}

/** null 为未知；零只在明确统计且已完成时表示对勾，不把空剧库误判为完成。 */
internal fun homeEpisodeStatus(item: Item): Long? = when {
    item.isSeries && item.unplayedCountKnown && item.unplayed > 0 -> item.unplayed
    item.isSeries && item.unplayedCountKnown && item.unplayed == 0L && item.played -> 0L
    item.type == "Movie" && item.played -> 0L
    else -> null
}

@Composable
internal fun EpisodeStatusBadge(item: Item, m: Modifier = Modifier) {
    val count = homeEpisodeStatus(item) ?: return
    val done = count == 0L
    val c = Lp.colors
    Box(m.width(IntrinsicSize.Max).widthIn(min = 24.dp).heightIn(min = 24.dp)
        .clip(RoundedCornerShape(R.pill))
        .background(if (done) c.ok.copy(alpha = .24f) else c.mediaPanel)
        .semantics { contentDescription = if (done) "已看完" else "$count 集未观看" }
        .padding(horizontal = Sp.x6, vertical = Sp.x4), contentAlignment = Alignment.Center) {
        if (done) Icon(LpIcons.check, null, Modifier.size(14.dp), tint = c.ok)
        else Text(count.toString(), color = c.mediaBadgeInk, style = LpText.badge,
            maxLines = 1, softWrap = false)
    }
}

/** 只接受明确豆瓣来源的数据，缺失或越界不占位置。 */
@Composable
internal fun DoubanRatingBadge(rating: Double?, m: Modifier = Modifier) {
    val value = rating?.takeIf { it.isFinite() && it in 0.0..10.0 } ?: return
    Row(m.heightIn(min = 22.dp).clip(RoundedCornerShape(R.sm))
        .background(Color.Black.copy(alpha = .72f)).padding(horizontal = Sp.x6, vertical = Sp.x2)
        .semantics(mergeDescendants = true) { contentDescription = "豆瓣评分 %.1f".format(java.util.Locale.ROOT, value) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sp.x4)) {
        Text("豆", color = Lp.colors.ok, style = LpText.badge)
        Text("%.1f".format(java.util.Locale.ROOT, value), color = Color.White, style = LpText.caption)
    }
}

@Composable
private fun Badge(item: Item, m: Modifier) {
    val c = Lp.colors
    when {
        item.isSeries && item.unplayed > 0 -> Box(
            m.width(IntrinsicSize.Max).widthIn(min = 23.dp).heightIn(min = 23.dp).clip(RoundedCornerShape(R.pill))
                .background(c.mediaAccent).padding(horizontal = 4.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) { Text(item.unplayed.toString(), color = c.mediaOnAccent, style = LpText.action.copy(lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), maxLines = 1, softWrap = false) }

        item.played -> Box(
            m.size(18.dp).clip(RoundedCornerShape(R.pill)).background(c.ok),
            contentAlignment = Alignment.Center,
        ) { Icon(LpIcons.check, "已看完", Modifier.size(11.dp), tint = Color(0xFF062418)) }


    }
}

/** 评分独立于未看数量和已看标记，贴右下角斜切三角。 */
@Composable
private fun RatingCorner(rating: Double, m: Modifier) {
    val c = Lp.colors
    val density = LocalDensity.current
    // 三角及文字内边距随字号一起缩放，避免大字号评分跨出斜边。
    Box(m.size(with(density) { 40.sp.toDp() }).drawBehind {
        drawPath(Path().apply {
            moveTo(size.width, 0f); lineTo(size.width, size.height)
            lineTo(0f, size.height); close()
        }, c.mediaRating)
    }, contentAlignment = Alignment.BottomEnd) {
        Text("%.1f".format(java.util.Locale.ROOT, rating), color = c.mediaRatingInk,
            style = LpText.filter,
            modifier = Modifier.padding(end = with(density) { 3.sp.toDp() },
                bottom = with(density) { 5.sp.toDp() }).graphicsLayer { rotationZ = -45f })
    }
}

/** 长按菜单一项。**这份定义是全站唯一的** —— 各页自己拼一套会长出不一致。 */
data class CardAction(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

@Composable
internal fun CardMenu(open: Boolean, onDismiss: () -> Unit, actions: List<CardAction>) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(gap) { PosterMenuPositionProvider(gap) }
    LpMenu(open, onDismiss, positionProvider = position,
        m = Modifier.width(140.dp).testTag("poster.menu")) {
        actions.forEach { a ->
            LpMenuItem(a.label, { onDismiss(); a.onClick() }, danger = a.danger)
        }
    }
}


/*
 * 卡片文字区的高度。
 *
 * ☠ **必须按 sp 算出来,不能写死 dp。** 轨道高度是常量(LazyRow 嵌在 LazyColumn 里
 *   不给定高会每次测量重排整列),而卡下面两行字是 sp —— 系统字号调到 1.2 倍以上时,
 *   写死的 dp 就把副标题裁掉半行:「继续观看」里的 SxEy、库里的年份只剩上半截。
 *   这不是审美问题,是**两把不同的尺**被硬凑在一起。
 */

@Composable
private fun captionHeight(): Dp = with(LocalDensity.current) {
    Sp.x6 + LpText.card.lineHeight.toDp() + 2.dp + LpText.caption.lineHeight.toDp() + 2.dp
}

/** 一条轨道该多高 = 封面 + 文字区。**两处轨道(真卡 / 骨架)共用它**,否则骨架和真卡不等高。 */
@Composable
fun rowHeight(thumb: Boolean): Dp =
    (if (thumb) ThumbW * 9 / 16 else PosterW * 3 / 2) + captionHeight()

private val PosterW = 104.dp
private val ThumbW = 194.dp

/**
 * 横滑轨道。
 *
 * ☠ **高度必须是常量。** `LazyRow` 嵌在 `LazyColumn` 里而不给固定高度,
 * 会在每次测量时重新布局整列 —— 那是「首页滑不动」在 Compose 上的等价形态。
 */
@Composable
fun LpRow(
    title: String,
    items: List<Item>,
    imageUrl: (Item) -> String?,
    onOpen: (Item) -> Unit,
    m: Modifier = Modifier,
    thumb: Boolean = false,
    menu: ((Item) -> List<CardAction>)? = null,
    onMore: (() -> Unit)? = null,
    resume: Boolean = false,
    /** 首页以账号隔离已入场卡片，非首页沿用图片就绪入场。 */
    homeAccount: Pair<String, String>? = null,
    homeStyle: Boolean = false,
) {
    val row = rememberLazyListState()
    val firstId = items.firstOrNull()?.id
    var previousFirst by remember { mutableStateOf(firstId) }
    SideEffect {
        // 首位刷新展示新内容；中途浏览仍按作品key保持位置。
        if (previousFirst != null && previousFirst != firstId &&
            row.firstVisibleItemIndex == 0 && !row.isScrollInProgress) row.requestScrollToItem(0)
        previousFirst = firstId
    }
    Column(m.fillMaxWidth()) {
        if (title.isNotBlank()) MediaRowHeader(title, onMore, homeStyle)
        LazyRow(
            Modifier.fillMaxWidth().height(rowHeight(thumb)),
            state = row,
            contentPadding = PaddingValues(horizontal = Sp.x16),
            horizontalArrangement = Arrangement.spacedBy(Sp.x10),
        ) {
            // key + contentType:不给的话滚动时 item 复用会让 Coil 重复发请求
            itemsIndexed(items, key = { _, it -> it.id }, contentType = { _, _ -> if (thumb) "thumb" else "poster" }) { index, item ->
                MediaCard(item, imageUrl(item), { onOpen(item) },
                    m = if (homeAccount != null) Modifier.homePosterEntrance(item.id, index, homeAccount, row) else Modifier,
                    thumb = thumb, menu = menu?.invoke(item), resume = resume, entrance = homeAccount == null, homeStyle = homeStyle)
            }
        }
    }
}

/** 请求前仅保留低对比封面几何，不显示虚构的文字条和闪烁高光。 */
@Composable
fun LpRowSkeleton(title: String? = null, thumb: Boolean = false, m: Modifier = Modifier) {
    Column(m.fillMaxWidth()) {
        if (!title.isNullOrBlank()) MediaRowHeader(title)
        LazyRow(Modifier.fillMaxWidth().height(rowHeight(thumb)),
            contentPadding = PaddingValues(horizontal = Sp.x16),
            horizontalArrangement = Arrangement.spacedBy(Sp.x10)) {
            items(4) {
                Box(Modifier.width(if (thumb) ThumbW else PosterW)
                    .aspectRatio(if (thumb) 16f / 9f else 2f / 3f)
                    .clip(RoundedCornerShape(R.md)).background(Lp.colors.s1))
            }
        }
    }
}

/** 栏目竖杠与标题构成入口；无更多回调时只展示标题。 */
@Composable
internal fun MediaRowHeader(title: String, onMore: (() -> Unit)? = null, homeStyle: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Sp.x16)
        .then(if (onMore != null) Modifier.pressable(onMore) else Modifier)
        .padding(top = if (homeStyle) Sp.x26 else Sp.x12, bottom = if (homeStyle) Sp.x10 else Sp.x8),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(3.dp, 16.dp).clip(RoundedCornerShape(R.pill)).background(Lp.colors.mediaIcon))
        Spacer(Modifier.width(Sp.x8))
        Text(title, color = Lp.colors.fg, style = LpText.title,
            modifier = Modifier.weight(1f))
        if (homeStyle && onMore != null) Icon(LpIcons.chevR, null, Modifier.size(20.dp), tint = Lp.colors.fg2)
    }
}

/** 优先放在海报侧面；侧面不足时放在上下方，小窗口才允许边界钳位。 */
internal class PosterMenuPositionProvider(private val gap: Int) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val y = anchorBounds.top.coerceIn(0, maxY)
        val x = anchorBounds.left.coerceIn(0, maxX)
        val right = androidx.compose.ui.unit.IntOffset(anchorBounds.right + gap, y)
        val left = androidx.compose.ui.unit.IntOffset(anchorBounds.left - gap - popupContentSize.width, y)
        val sides = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) listOf(right, left) else listOf(left, right)
        val candidates = sides + listOf(
            androidx.compose.ui.unit.IntOffset(x, anchorBounds.bottom + gap),
            androidx.compose.ui.unit.IntOffset(x, anchorBounds.top - gap - popupContentSize.height),
        )
        return candidates.firstOrNull { it.x in 0..maxX && it.y in 0..maxY }
            ?: androidx.compose.ui.unit.IntOffset(x, y)
    }
}
