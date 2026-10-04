package xyz.linplayer.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.ui.theme.LpEasing
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp
import xyz.linplayer.app.ui.theme.T

/**
 * 网络图。
 *
 * ☠ **必须看 `painter.state`。** 只画 `AsyncImage` 不看 state 的话,骨架要么永不消失
 * 要么永不出现 —— 那正是历史故障「封面隐身」在 Compose 上的等价漏法
 * (旧栈是手抄卡片时漏了「解码完成 → 加就绪标记」这一步)。
 */
@Composable
fun NetImage(
    url: String?,
    desc: String?,
    m: Modifier = Modifier,
    corner: androidx.compose.ui.unit.Dp = R.md,
    scale: ContentScale = ContentScale.Crop,
    placeholder: @Composable (() -> Unit)? = null,
) {
    Box(m.clip(RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
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
        val painter = rememberAsyncImagePainter(url)
        val state by painter.state.collectAsState()
        val ready = state is AsyncImagePainter.State.Success
        val alpha by animateFloatAsState(
            if (ready) 1f else 0f,
            androidx.compose.animation.core.tween(T.T5, easing = LpEasing.standard),
            label = "imgIn",
        )
        if (!ready) {
            if (placeholder != null) placeholder() else Skeleton(Modifier.fillMaxSize(), corner)
        }
        if (xyz.linplayer.app.BuildConfig.DEBUG) {
            val st = state
            if (st is AsyncImagePainter.State.Error) android.util.Log.w(
                "LinPlayer", "图片加载失败 $url", st.result.throwable)
        }
        androidx.compose.foundation.Image(
            painter = painter, contentDescription = desc, contentScale = scale,
            modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
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
    /** 长按菜单项。**null = 这一处不给长按菜单**(跨服结果就是这样,理由见 §7.5)。 */
    menu: List<CardAction>? = null,
    showCaption: Boolean = true,
    /** 长按交给调用方(插件的 `onLongPress`)。[menu] 在时以菜单为准:同一块区域只能有一个长按。 */
    onLongPress: (() -> Unit)? = null,
    /** 首页续播信息；其它轨道沿用普通卡片文案。 */
    resume: Boolean = false,
) {
    val c = Lp.colors
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val width = if (thumb) ThumbW else PosterW

    // 整卡读成一条:TalkBack 逐个念「图片」「标题」「年份」是噪音
    Column(m.width(width).semantics(mergeDescendants = true) { }) {
        Box {
            Box(
                Modifier.fillMaxWidth().aspectRatio(if (thumb) 16f / 9f else 2f / 3f)
                    .clip(RoundedCornerShape(R.md))
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = when {
                            menu != null -> {
                                { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuOpen = true }
                            }
                            onLongPress != null -> {
                                { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress() }
                            }
                            else -> null
                        },
                    )
            ) {
                NetImage(imageUrl, item.name, Modifier.fillMaxSize())

                // 角标:剧集 → 未看集数,全看完 → 打勾;电影 → 评分。**角标要小**,它压在封面上
                Badge(item, Modifier.align(Alignment.TopEnd).padding(4.dp))
                item.rating?.takeIf { it > 0 && it.isFinite() }?.let { rating ->
                    RatingCorner(rating, Modifier.align(Alignment.BottomEnd))
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
                        .background(c.line2)
                ) {
                    Box(Modifier.fillMaxWidth(item.progress).fillMaxSize().background(c.mediaAccent))
                }
            }
            if (menu != null) CardMenu(menuOpen, { menuOpen = false }, menu)
        }
        if (showCaption) {
            Spacer(Modifier.height(Sp.x6))
            // 一行 + 省略号:片名长短差别极大,不收会把行高撑成两三行,整条轨道高度乱跳
            Text(item.cardTitle, color = c.fg, fontSize = 14.sp, lineHeight = CapTitleLh,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            val subtitle = if (resume && item.isEpisode) listOfNotNull(
                if (item.seasonNo != null && item.episodeNo != null) "S${item.seasonNo}E${item.episodeNo}" else null,
                item.name.takeIf { it.isNotBlank() && it != item.cardTitle },
            ).joinToString(" · ").takeIf { it.isNotBlank() } else item.cardSub
            subtitle?.let {
                Text(it, color = c.fg2, fontSize = 11.sp, lineHeight = CapSubLh, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            }
        }
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
        ) { Text(item.unplayed.toString(), color = c.mediaOnAccent, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }

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
            fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(end = with(density) { 3.sp.toDp() },
                bottom = with(density) { 5.sp.toDp() }).graphicsLayer { rotationZ = -45f })
    }
}

/** 长按菜单一项。**这份定义是全站唯一的** —— 各页自己拼一套会长出不一致。 */
data class CardAction(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

@Composable
private fun CardMenu(open: Boolean, onDismiss: () -> Unit, actions: List<CardAction>) {
    LpMenu(open, onDismiss) {
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
private val CapTitleLh = 20.sp
private val CapSubLh = 16.sp

@Composable
private fun captionHeight(): Dp = with(LocalDensity.current) {
    Sp.x6 + CapTitleLh.toDp() + 2.dp + CapSubLh.toDp() + 2.dp
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
) {
    Column(m.fillMaxWidth()) {
        MediaRowHeader(title, onMore)
        LazyRow(
            Modifier.fillMaxWidth().height(rowHeight(thumb)),
            contentPadding = PaddingValues(horizontal = Sp.x16),
            horizontalArrangement = Arrangement.spacedBy(Sp.x10),
        ) {
            // key + contentType:不给的话滚动时 item 复用会让 Coil 重复发请求
            items(items, key = { it.id }, contentType = { if (thumb) "thumb" else "poster" }) {
                MediaCard(it, imageUrl(it), { onOpen(it) }, thumb = thumb, menu = menu?.invoke(it), resume = resume)
            }
        }
    }
}

/** 骨架轨道:4 张空卡。**形状和真卡一致**。 */
@Composable
fun LpRowSkeleton(title: String? = null, thumb: Boolean = false, m: Modifier = Modifier) {
    Column(m.fillMaxWidth()) {
        if (title != null) MediaRowHeader(title, null) else Box(Modifier.padding(Sp.x16)) {
            SkeletonLine(104.dp)
        }
        Row(
            Modifier.fillMaxWidth().height(rowHeight(thumb))
                .padding(horizontal = Sp.x16),
            horizontalArrangement = Arrangement.spacedBy(Sp.x10),
        ) {
            repeat(4) {
                Column(Modifier.width(if (thumb) ThumbW else PosterW)) {
                    Skeleton(Modifier.fillMaxWidth().aspectRatio(if (thumb) 16f / 9f else 2f / 3f))
                    Spacer(Modifier.height(Sp.x8))
                    SkeletonLine(78.dp)
                }
            }
        }
    }
}

/** 栏目竖杠与标题构成入口；无更多回调时只展示标题。 */
@Composable
internal fun MediaRowHeader(title: String, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Sp.x16)
        .then(if (onMore != null) Modifier.pressable(onMore) else Modifier)
        .padding(top = Sp.x12, bottom = Sp.x8),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(3.dp, 16.dp).clip(RoundedCornerShape(R.pill)).background(Lp.colors.mediaIcon))
        Spacer(Modifier.width(Sp.x8))
        Text(title, color = Lp.colors.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
    }
}
