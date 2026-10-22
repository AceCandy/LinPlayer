package xyz.linplayer.app.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.*
import xyz.linplayer.app.ui.components.LpIconButton
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.player.FeedPlayback
import xyz.linplayer.app.ui.theme.*

private val FeedAccent = Color(0xFFFF405E)
private val FeedSecondary = Color.White.copy(alpha = .7f)
private val FeedSurface = Color(0xFF17181B)
private val FeedTextShadow = Shadow(Color.Black.copy(alpha = .7f), Offset(0f, 2f), 6f)

/** 手机只呈现视频上的轻控件；筛选状态与播放会话由流影页持有。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneLiuyingChrome(
    feed: String, tab: String, showFilters: Boolean, enabled: Boolean,
    current: JsonObject?, favoriteBusy: Boolean, autoAdvance: Boolean, playback: FeedPlayback?,
    onExit: () -> Unit, onBankai: () -> Unit, onTab: (String) -> Unit,
    onFavorite: () -> Unit, onFilter: () -> Unit, onMode: () -> Unit, onFavorites: () -> Unit,
    showNavigation: Boolean = true,
) {
    var speedOpen by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) speedOpen = false }
    Box(Modifier.fillMaxSize()) {
        if (showNavigation) Box(Modifier.fillMaxWidth().height(180.dp).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .65f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(280.dp).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))))
        if (playback?.paused == true) Icon(LpIcons.play, null,
            Modifier.align(Alignment.Center).size(88.dp).testTag("liuying.paused"),
            tint = Color.White.copy(alpha = .4f))
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
            if (showNavigation) PhoneLiuyingTopBar(feed, tab, enabled, onExit, onBankai, onTab, onFavorites)
            else Spacer(Modifier.height(48.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                @Composable
                fun actions(modifier: Modifier = Modifier) {
                    FeedAction(if (current.bool("favorite")) LpIcons.heartOn else LpIcons.heart,
                        if (current.bool("favorite")) "取消收藏" else "收藏", "liuying.favorite",
                        enabled && current != null && !favoriteBusy, if (current.bool("favorite")) FeedAccent else Color.White, onFavorite,
                        modifier.semantics { selected = current.bool("favorite") }, showTitle = false)
                    FeedAction(LpIcons.forward, "${playback?.speed ?: 1.0}×", "liuying.speed",
                        enabled && playback?.ready == true && !playback.speedBusy, Color.White, { speedOpen = true },
                        modifier.semantics { contentDescription = "流影倍速" })
                }
                if (maxHeight < 160.dp) {
                    Row(Modifier.align(Alignment.BottomEnd).width(144.dp).testTag("liuying.rail")) {
                        actions(Modifier.weight(1f))
                    }
                } else Column(Modifier.align(Alignment.BottomEnd).heightIn(max = maxHeight).width(76.dp).testTag("liuying.rail")
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    actions()
                }
            }
            if (current != null) {
                Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 80.dp, top = 16.dp).testTag("liuying.details")) {
                    current.str("author")?.takeIf { it.isNotBlank() }?.let {
                        Text("@${it.removePrefix("@")}", color = Color.White,
                            style = LpText.list.copy(fontWeight = FontWeight.SemiBold, shadow = FeedTextShadow),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(current.str("title").orEmpty(), color = Color.White,
                        style = LpText.body.copy(shadow = FeedTextShadow), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (playback != null) {
                var preview by remember { mutableStateOf<Float?>(null) }
                val duration = playback.duration.toFloat().coerceAtLeast(1f)
                val value = preview ?: playback.position.toFloat().coerceIn(0f, duration)
                Row(Modifier.fillMaxWidth().testTag("liuying.timeline"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${feedTime(playback.position)} / ${feedTime(playback.duration)}",
                        Modifier.testTag("liuying.time"), color = FeedSecondary, style = LpText.number, maxLines = 1)
                    Slider(value, { preview = it }, valueRange = 0f..duration,
                    onValueChangeFinished = { preview?.let { playback.seek(it.toDouble()) }; preview = null },
                    enabled = enabled && playback.ready && playback.duration > 0,
                    modifier = Modifier.weight(1f).testTag("liuying.progress"),
                    thumb = {
                        // 与滑条内部最小槽位等高，避免小圆点被放在槽位顶部。
                        Box(Modifier.width(4.dp).height(16.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(4.dp).background(Color.White, RoundedCornerShape(2.dp)))
                        }
                    },
                    track = {
                        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                            drawRect(Color.White.copy(alpha = .25f))
                            drawRect(Color.White, size = size.copy(width = size.width * (value / duration)))
                        }
                    })
                }
            }
            Row(Modifier.fillMaxWidth().testTag("liuying.bottom"), verticalAlignment = Alignment.CenterVertically) {
                FeedBottomAction(if (autoAdvance) LpIcons.skipNext else LpIcons.sync,
                    if (autoAdvance) "自动连播" else "循环播放", "liuying.mode", enabled, onMode, Modifier.weight(1f))
                if (showFilters) FeedBottomAction(LpIcons.filter, "筛选", "liuying.filter", enabled, onFilter, Modifier.weight(1f))
                FeedBottomAction(LpIcons.audio, if (playback?.muted == true) "开声音" else "静音",
                    "liuying.sound", enabled && playback?.ready == true, { playback?.toggleMute?.invoke() }, Modifier.weight(1f))
            }
        }
    }
    if (speedOpen && playback != null) PhoneLiuyingSheet("流影倍速", "liuying.speedSheet", { speedOpen = false }) {
        Text("仅用于流影，不影响普通播放器", color = FeedSecondary, style = LpText.caption)
        FlowRow(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (speed in listOf(.25, .5, .75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 4.0)) {
                FeedFilterChip("$speed×", null, playback.speed == speed, "liuying.speed.$speed") {
                    if (playback.ready && !playback.speedBusy) { playback.setSpeed(speed); speedOpen = false }
                }
            }
        }
    }
}

/** 导航不属于任何视频项，翻页时保持固定。 */
@Composable
internal fun PhoneLiuyingTopBar(feed: String, tab: String, enabled: Boolean, onExit: () -> Unit,
    onBankai: () -> Unit, onTab: (String) -> Unit, onFavorites: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("liuying.controls"), verticalAlignment = Alignment.CenterVertically) {
        LpIconButton(LpIcons.back, "返回", tint = Color.White, onClick = onExit)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
            if (feed == "normal") for ((key, title) in listOf("dance" to "舞蹈", "live" to "直播")) {
                Column(Modifier.widthIn(min = 64.dp).pressable({ onTab(key) }, enabled)
                    .semantics { selected = tab == key }.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, color = if (tab == key) Color.White else FeedSecondary,
                        style = LpText.list.copy(fontWeight = FontWeight.SemiBold, shadow = FeedTextShadow))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.size(20.dp, 3.dp).background(
                        if (tab == key) Color.White else Color.Transparent, RoundedCornerShape(2.dp)))
                }
            } else Text("流影", color = Color.White, style = LpText.list)
        }
        Box(Modifier.size(48.dp).testTag("liuying.bankai")
            .semantics {
                selected = feed == "adult"
                contentDescription = "收藏视频列表"
                stateDescription = if (feed == "adult") "卍解已开启" else "普通流影"
                onLongClick("切换卍解") { onBankai(); true }
            }.pressable(onFavorites, onBankai, enabled), contentAlignment = Alignment.Center) {
            Icon(LpIcons.unlock, null, Modifier.size(26.dp),
                tint = if (feed == "adult") FeedAccent else Color.White)
        }
    }
}

private fun feedTime(seconds: Double): String {
    val value = seconds.toInt().coerceAtLeast(0)
    return "${value / 60}:${(value % 60).toString().padStart(2, '0')}"
}

@Composable
private fun FeedAction(icon: ImageVector, title: String, tag: String, enabled: Boolean, tint: Color, onClick: () -> Unit,
    modifier: Modifier = Modifier, showTitle: Boolean = true) {
    Column(modifier.heightIn(min = 48.dp).testTag(tag).pressable(onClick, enabled)
        .semantics { if (!showTitle) contentDescription = title }
        .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(30.dp).drawBehind {
            drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = .25f), Color.Transparent),
                center, 24.dp.toPx()), radius = 24.dp.toPx())
        }, tint = tint.copy(alpha = if (enabled) 1f else .35f))
        if (showTitle) {
            Spacer(Modifier.height(5.dp))
            Text(title, color = Color.White.copy(alpha = if (enabled) 1f else .35f),
                style = LpText.caption.copy(shadow = FeedTextShadow), maxLines = 1)
        }
    }
}

@Composable
private fun FeedBottomAction(icon: ImageVector, title: String, tag: String, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier) {
    Row(modifier.heightIn(min = 48.dp).testTag(tag).pressable(onClick, enabled).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (enabled) Color.White else FeedSecondary)
        Spacer(Modifier.width(6.dp))
        Text(title, color = Color.White, style = LpText.caption, maxLines = 2)
    }
}

/** 独立可聚焦窗口阻隔视频手势，生命周期不随播放器换片而重建。 */
@Composable
internal fun PhoneLiuyingFilters(
    labels: List<JsonObject>, showLabels: Boolean, channel: String, exposure: String, favorites: Boolean,
    canPrevious: Boolean, canNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit,
    onLabel: (String, String) -> Unit, onFavorites: () -> Unit, onClear: () -> Unit, onClose: () -> Unit,
) {
    PhoneLiuyingSheet("筛选视频", "liuying.sheet", onClose) {
        Text("只在你选中的范围内连续播放", color = FeedSecondary, style = LpText.caption)
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag("liuying.actions").padding(top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("清除筛选", Modifier.heightIn(min = 48.dp).wrapContentHeight().pressable(onClear)
                .testTag("liuying.clear"), color = FeedSecondary, style = LpText.caption)
            if (showLabels) {
                for ((kind, title) in listOf("channel" to "频道", "exposure" to "衣着")) {
                    Text(title, color = FeedSecondary, style = LpText.caption)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FeedFilterChip("全部", null, if (kind == "channel") channel.isEmpty() else exposure.isEmpty(),
                            "liuying.all.$kind") { onLabel(kind, "") }
                        for (label in labels.filter { it.str("kind") == kind }) {
                            val name = label.str("name").orEmpty()
                            FeedFilterChip(label.str("title") ?: name, label.long("count"),
                                if (kind == "channel") channel == name else exposure == name, "liuying.label.$name") {
                                onLabel(kind, name)
                            }
                        }
                    }
                }
            } else Text("当前来源或分类不提供标签", color = FeedSecondary, style = LpText.caption)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("liuying.favorites")
                .semantics { selected = favorites }.pressable(onFavorites), verticalAlignment = Alignment.CenterVertically) {
                Text("只看收藏", Modifier.weight(1f), color = Color.White, style = LpText.body)
                Icon(if (favorites) LpIcons.checkCircle else LpIcons.heart, null,
                    Modifier.size(24.dp), tint = if (favorites) FeedAccent else FeedSecondary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FeedAction(LpIcons.skipPrev, "上一条", "liuying.previous", canPrevious, Color.White, onPrevious,
                    modifier = Modifier.weight(1f))
                FeedAction(LpIcons.skipNext, "下一条", "liuying.next", canNext, Color.White, onNext,
                    modifier = Modifier.weight(1f))
            }
            Text("标签数量由服务器提供，不代表组合筛选后的实时总数", color = FeedSecondary, style = LpText.caption)
        }
    }
}

@Composable
internal fun PhoneLiuyingSheet(title: String, tag: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Popup(alignment = Alignment.BottomCenter, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f))) {
            Box(Modifier.fillMaxSize().testTag("liuying.scrim").pointerInput(Unit) { detectTapGestures { onClose() } })
            Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth()
                .heightIn(max = maxHeight * .85f).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(FeedSurface).testTag(tag).semantics { paneTitle = title }
                .pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding().padding(horizontal = 20.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 8.dp)
                    .size(32.dp, 4.dp).background(Color.White.copy(alpha = .25f), RoundedCornerShape(2.dp)))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), color = Color.White, style = LpText.section)
                    Text("完成", Modifier.heightIn(min = 48.dp).wrapContentHeight().pressable(onClose).padding(horizontal = 8.dp),
                        color = FeedAccent, style = LpText.body)
                }
                content()
            }
        }
    }
}

@Composable
private fun FeedFilterChip(title: String, count: Long?, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 48.dp).testTag(tag).semantics { this.selected = selected }
        .clip(RoundedCornerShape(10.dp)).pressable(onClick)
        .background(if (selected) FeedAccent.copy(alpha = .12f) else Color.White.copy(alpha = .04f))
        .border(1.dp, if (selected) FeedAccent.copy(alpha = .65f) else Color.White.copy(alpha = .12f), RoundedCornerShape(10.dp))
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f, fill = false), color = if (selected) FeedAccent else Color.White,
            style = LpText.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        count?.let { Text("$it 部", color = if (selected) FeedAccent else FeedSecondary, style = LpText.caption) }
    }
}
