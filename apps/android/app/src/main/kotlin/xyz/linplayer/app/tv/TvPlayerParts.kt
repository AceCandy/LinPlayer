package xyz.linplayer.app.tv

import androidx.compose.runtime.CompositionLocalProvider
import xyz.linplayer.app.tv.kit.LocalTvVideoControls

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.boolOrNull
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.kit.FullState
import xyz.linplayer.app.tv.kit.PanelGroup
import xyz.linplayer.app.tv.kit.PanelItem
import xyz.linplayer.app.tv.kit.ProgressBar
import xyz.linplayer.app.tv.kit.TvButton
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvDim
import xyz.linplayer.app.tv.kit.TvR
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.TvText
import xyz.linplayer.app.tv.kit.TvW
import xyz.linplayer.app.tv.kit.tvType
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.fmtTime
import xyz.linplayer.app.ui.player.DanmakuStyle
import xyz.linplayer.app.ui.player.SubStyle
import xyz.linplayer.app.ui.player.VideoFit
import xyz.linplayer.app.ui.player.exoCurrent
import xyz.linplayer.app.ui.player.exoPick
import xyz.linplayer.app.ui.player.exoTracks
import xyz.linplayer.app.ui.player.loadDanmakuFor
import xyz.linplayer.app.ui.player.resolveDanmakuFor
import xyz.linplayer.app.ui.player.danmakuSelection
import xyz.linplayer.app.ui.player.toggleDanmaku
import xyz.linplayer.app.ui.theme.LpIcons

/** 面板里要动播放器的四个口子。换内核时这四个动作的实现整个换掉,面板不用知道。 */
internal class PlayerCtl(
    val seek: (Double) -> Unit, val pause: (Boolean) -> Unit, val speed: (Double) -> Unit,
    val trackPicked: (String) -> Unit = {},
    val pickTrack: suspend (String, String) -> Unit,
    val pickExoTrack: ((ExoPlayer, String, String) -> Unit)? = null,
    val observeTracks: (List<JsonObject>) -> Unit = {},
    val markersSaved: () -> Unit = {},
    val markersWriting: (Boolean) -> Unit = {},
    val switchEngine: () -> Unit,
    val switchTo: (TvRoute.Player) -> Unit,
)

/** 速度为 0 时什么都不画:本地文件和缓存喂饱时本来就是 0,画「0 KB/s」像卡住了。 */
private fun speedText(s: String) = s.takeIf { it.isNotBlank() && !it.startsWith("0 ") }

/** 起播黑幕:32dp 转圈 + 状态文字 + 缓冲速度(§8.5),文字与手机一致。 */
@Composable
internal fun Curtain(ui: PlayerUi, showSpeed: Boolean) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Spinner(ui, if (ui.buffering) "正在缓冲…" else "正在打开…", showSpeed)
    }
}

/** 起播后卡住缓冲:画面上叠同一个转圈,不盖黑底。 */
@Composable
internal fun BoxScope.BufferingHint(ui: PlayerUi, showSpeed: Boolean) {
    Box(Modifier.align(Alignment.Center)) { Spinner(ui, "正在缓冲…", showSpeed) }
}

@Composable
private fun Spinner(ui: PlayerUi, label: String, showSpeed: Boolean) {
    val tr = rememberInfiniteTransition(label = "spin")
    val a by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "spinA")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(32.dp)) {
            drawArc(TvC.acc, a, 270f, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        Spacer(Modifier.height(TvSp.x8))
        TvText(label, tvType.meta, Color.White)
        speedText(ui.netSpeed)?.takeIf { showSpeed }?.let {
            Spacer(Modifier.height(TvSp.x4))
            TvText(it, tvType.meta, Color.White.copy(alpha = .8f), mono = true)
        }
    }
}

@Composable
internal fun BoxScope.PlayerTopStatus(ui: PlayerUi, controlsVisible: Boolean,
    showIntro: Boolean, playbackFocus: FocusRequester, onSkip: () -> Unit) {
    var clock by remember { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                clock = android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis()).toString()
                delay(10_000)
            }
        }
    }
    BoxWithConstraints(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
        val inset = (maxWidth * .038f).coerceIn(16.dp, 48.dp)
        val badge = Modifier.background(Color.Black.copy(alpha = .6f), TvR.sm)
            .padding(horizontal = TvSp.x8, vertical = TvSp.x4)
        Row(Modifier.fillMaxWidth().padding(start = inset, end = inset, top = TvDim.safeV),
            verticalAlignment = Alignment.Top) {
            if (clock.isNotEmpty()) TvText(clock, tvType.body, Color.White.copy(alpha = .9f),
                Modifier.testTag("player.clock").then(badge), weight = TvW.medium, mono = true)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(TvSp.x8)) {
                speedText(ui.netSpeed)?.takeIf { controlsVisible }?.let {
                    TvText(it, tvType.meta, Color.White.copy(alpha = .9f),
                        Modifier.testTag("player.netSpeed").then(badge), weight = TvW.medium, mono = true)
                }
                if (showIntro) SkipButton(ui, playbackFocus, onSkip)
            }
        }
    }
}

/** 起播失败 / 放不出来(§8.6)。第一行给人看，第二行是核心层原话；数据源还可重新选择换源。 */
@Composable
internal fun PlayerFailure(reason: String, retried: Boolean, onRetry: () -> Unit, finished: Boolean = false,
    onSwitchSource: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().background(TvC.bg)) {
        FullState(LpIcons.info, if (finished) "无法继续播放下一集" else "这一片没能播起来", sub = if (retried) "已自动重试 1 次仍然失败" else null, detail = reason,
            buttons = if (onSwitchSource == null) listOf("重试") else listOf("重试", "换源"),
            tone = TvC.bad, onButton = { if (it == 0) onRetry() else onSwitchSource?.invoke() })
    }
}

/** 播放页先判断长按；子控件只消费短按释放，避免长按拦截抬键后残留按压态。 */
private fun Modifier.playerConfirm(enabled: Boolean, onClick: () -> Unit) = onPreviewKeyEvent { e ->
    if (!isOk(e.key)) false else {
        if (enabled && e.type == KeyEventType.KeyUp && !e.nativeKeyEvent.isCanceled) onClick()
        true
    }
}

/** 视频上的动作只用图标或当前倍率，名称留在可访问语义中。 */
@Composable
private fun PlayerAction(label: String, size: Dp, modifier: Modifier, icon: ImageVector? = null,
    value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled,
        modifier = modifier.height(size).widthIn(min = size).semantics { contentDescription = label }
            .playerConfirm(enabled, onClick),
        shape = ClickableSurfaceDefaults.shape(TvR.pill),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f, pressedScale = .98f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = .8f), pressedContentColor = Color.Black,
            disabledContainerColor = Color.Transparent, disabledContentColor = Color.White.copy(alpha = .35f),
        ),
    ) {
        Box(Modifier.height(size).then(if (icon != null) Modifier.width(size) else Modifier.padding(horizontal = TvSp.x8)),
            contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, null, Modifier.size(size * .5f))
            else Text(value.orEmpty(), fontSize = tvType.body, maxLines = 1)
        }
    }
}

@Composable
internal fun BoxScope.Osd(
    ui: PlayerUi, target: TvRoute.Player, engine: String, playbackFocus: FocusRequester,
    onSeek: (Double) -> Unit, onPause: () -> Unit, onNext: () -> Unit,
    onPanel: (String) -> Unit, nextUpVisible: Boolean,
    seekPosition: Double = ui.position,
) {
    val mem = LocalFocusMemory.current
    CompositionLocalProvider(LocalTvVideoControls provides true) {
        BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            val inset = (maxWidth * .038f).coerceIn(16.dp, 48.dp)
            val size = (maxWidth / 12).coerceIn(36.dp, 44.dp)
            val barWidth = maxWidth - inset * 2
            val fadeHeight = 32.dp
            Column(Modifier.fillMaxWidth()
                .drawBehind {
                    // 文字起点已有稳定遮罩，预览缩略图撑高控件时也不降低对比度。
                    drawRect(Brush.verticalGradient(0f to Color.Transparent,
                        fadeHeight.toPx() / this.size.height to Color.Black.copy(alpha = .72f),
                        1f to Color.Black.copy(alpha = .85f)))
                }
                .padding(start = inset, end = inset, top = fadeHeight, bottom = TvDim.safeV)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(TvSp.x16)) {
                    Column(Modifier.weight(1f)) {
                        if (ui.episodeLabel.isNotBlank()) TvText(ui.episodeLabel, tvType.meta, Color.White.copy(alpha = .8f))
                        TvText(ui.title, tvType.title, Color.White, weight = TvW.semi)
                    }
                    Row(Modifier.focusProperties {
                        down = if (ui.duration > 0) mem.requesters["osd.bar"] ?: playbackFocus else playbackFocus
                        up = mem.requesters["player.nextUp"] ?: mem.requesters["osd.skip"] ?: FocusRequester.Cancel
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSp.x8)) {
                        if (ui.hasEpisodes && !nextUpVisible) PlayerAction("下一集", size, Modifier.memo("osd.next"),
                            icon = LpIcons.skipNext, enabled = ui.next(target.itemId) != null, onClick = onNext)
                        PlayerAction("倍速", size, Modifier.memo("osd.speed"), value = "${ui.speed.toString().removeSuffix(".0")}×",
                            onClick = { onPanel("speed") })
                        if (!target.localEntry && !target.download)
                            PlayerAction("弹幕", size, Modifier.memo("osd.dm"), icon = LpIcons.danmaku, onClick = { onPanel("danmaku") })
                        PlayerAction("音轨", size, Modifier.memo("osd.audio"), icon = LpIcons.music, onClick = { onPanel("audio") })
                        PlayerAction("字幕", size, Modifier.memo("osd.sub"), icon = LpIcons.sub, onClick = { onPanel("sub") })
                        PlayerAction("更多", size, Modifier.memo("osd.more"), icon = LpIcons.more, onClick = { onPanel("more") })
                    }
                }
                Spacer(Modifier.height(TvSp.x8))
                key(target, engine) {
                    SeekBar(ui, onSeek, onPause, seekPosition, barWidth,
                        upTo = { mem.requesters[if (ui.next(target.itemId) != null && !nextUpVisible) "osd.next" else "osd.speed"] })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TvText(fmtTime(seekPosition), tvType.meta, Color.White, mono = true)
                    TvText(if (ui.duration > 0) fmtTime(ui.duration) else "--:--", tvType.meta, Color.White.copy(alpha = .8f), mono = true)
                }
            }
        }
    }
}

/**
 * 进度条(族 D,§8.3)。←/→ **不立即 seek**:只移预览游标;松手 700ms 或按确认才发。
 * ☠ seek 是排队命令,每按一下发一次会把队列灌满(「拖着拖着卡住」),而且进度条先弹回原处再跳。
 * ★ 时长未知时不可聚焦:用 0 当量程,「点了跳转画面没动」。
 */
@Composable
private fun SeekBar(ui: PlayerUi, onSeek: (Double) -> Unit, onPause: () -> Unit, seekPosition: Double, width: Dp, upTo: () -> androidx.compose.ui.focus.FocusRequester?) {
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    var commit by remember { mutableStateOf<Job?>(null) }
    val d = ui.duration
    fun frac(v: Double) = if (d > 0) (v / d).toFloat().coerceIn(0f, 1f) else 0f
    fun confirm() {
        commit?.cancel()
        val preview = ui.preview
        if (preview != null) { onSeek(preview); ui.preview = null } else onPause()
    }
    Box(
        Modifier.memo("osd.bar")
            .focusProperties { canFocus = d > 0; down = FocusRequester.Cancel; upTo()?.let { up = it } }
            .semantics {
                contentDescription = "播放进度"
                onClick(label = if (ui.preview != null) "确认跳转" else if (ui.paused) "播放" else "暂停") { confirm(); true }
            }
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) { commit?.cancel(); ui.preview = null } }
            .onKeyEvent { e ->
                val right = e.key == Key.DirectionRight
                when {
                    e.key == Key.DirectionLeft || right -> {
                        if (e.type == KeyEventType.KeyDown) {
                            commit?.cancel()
                            // 按住加速:前 3 下一个步长 → 30s → 1min → 5min
                            val rc = e.nativeKeyEvent.repeatCount
                            val step = when { rc < 3 -> UiPrefs.tvSeekStep.value.toDouble(); rc < 10 -> 30.0; rc < 20 -> 60.0; else -> 300.0 }
                            ui.preview = ((ui.preview ?: seekPosition) + if (right) step else -step).coerceIn(0.0, d)
                        } else {
                            commit = scope.launch { delay(700); ui.preview?.let(onSeek); ui.preview = null }
                        }
                        true
                    }
                    isOk(e.key) -> {
                        commit?.cancel()
                        if (e.type == KeyEventType.KeyUp) confirm()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        val bmp = ui.thumb
        val heat = xyz.linplayer.app.ui.player.rememberDanmakuHeatmap(LocalApp.current, ui.duration)
        xyz.linplayer.app.ui.player.DanmakuHeatmap(heat, Modifier.width(width).height(10.dp)
            .align(Alignment.BottomCenter).offset(y = (-16).dp))
        ProgressBar(
            played = frac(seekPosition), buffered = frac(maxOf(ui.buffered, ui.position)), focused = focused, width = width,
            chapters = (ui.chapters.map { it.first } + listOfNotNull(ui.intro?.endInclusive, ui.outro?.start))
                .map(::frac).filter { it in 0.001f..0.999f }.distinct(),
            preview = ui.preview?.let(::frac), previewLabel = ui.preview?.let(::fmtTime) ?: "",
            previewThumb = bmp?.let { b -> { Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } },
        )
    }
}

@Composable
private fun SkipButton(ui: PlayerUi, playbackFocus: FocusRequester, onSkip: () -> Unit) {
    val mem = LocalFocusMemory.current
    CompositionLocalProvider(LocalTvVideoControls provides true) {
        TvButton(if (ui.skipWhat == "intro") "跳过片头" else "跳过片尾", LpIcons.skipNext,
            modifier = Modifier.memo("osd.skip").playerConfirm(ui.osd, onSkip).focusProperties {
                canFocus = ui.osd
                down = if (ui.duration > 0) mem.requesters["osd.bar"] ?: playbackFocus else playbackFocus
            }, onClick = onSkip)
    }
}

/** 片尾提示沿用手机胶囊；展开控件后显式聚焦，不占用画面的确认键。 */
@Composable
internal fun BoxScope.NextUpChip(ui: PlayerUi, next: Item?, playbackFocus: FocusRequester, onPlay: () -> Unit) {
    val mem = LocalFocusMemory.current
    // 收起时贴着底部细进度线；展开时避开时间码、进度条和按钮行，落在按钮上方。
    val bottom = if (ui.osd) TvDim.safeV + 24.dp + 24.dp + TvSp.x8 + 44.dp + TvSp.x8 else 3.dp + TvSp.x8
    val modifier = Modifier.align(Alignment.BottomEnd).padding(end = TvDim.safeH, bottom = bottom).alpha(if (ui.osd) 1f else .5f)
        .memo("player.nextUp").playerConfirm(ui.osd, onPlay).focusProperties {
            canFocus = ui.osd
            down = if (ui.duration > 0) mem.requesters["osd.bar"] ?: playbackFocus else playbackFocus
        }
    if (next != null) xyz.linplayer.app.ui.player.NextUp(next, ui.duration - ui.position, ui.duration, 150.dp, modifier, onClick = onPlay)
    else xyz.linplayer.app.ui.player.SkipChip("跳过片尾", 150.dp, modifier, onPlay)

}

/** 瞬时提示:中央播放暂停图标、「3.0x ▸▸」。 */
@Composable
internal fun BoxScope.Moments(ui: PlayerUi) {
    val t = tvType
    if (ui.hold) Row(
        Modifier.align(Alignment.TopCenter).padding(top = TvDim.safeV).clip(TvR.pill).background(TvC.surface2)
            .padding(horizontal = TvSp.x16, vertical = TvSp.x6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvText("%.1fx".format(UiPrefs.tvHoldSpeed.value), t.title, TvC.acc, weight = TvW.bold)
        Spacer(Modifier.width(TvSp.x6))
        Icon(LpIcons.forward, null, Modifier.size(t.iconS), tint = TvC.acc)
        Spacer(Modifier.width(TvSp.x8))
        TvText("松开恢复", t.meta, TvC.fg2)
    }
    ui.flash?.let { playing ->
        Box(Modifier.align(Alignment.Center).size(64.dp).clip(TvR.pill).background(TvC.surface2), contentAlignment = Alignment.Center) {
            Icon(if (playing) LpIcons.play else LpIcons.pause, null, Modifier.size(28.dp), tint = TvC.fg)
        }
    }
}

// ---------------------------------------------------------------- 面板(§8.4)

internal fun openPanel(
    which: String, app: AppState, scope: CoroutineScope, overlay: Overlay, ui: PlayerUi,
    target: TvRoute.Player, engine: String, exo: ExoPlayer?, ctl: PlayerCtl,
    /** 这一格是插件标签时的那一行(SPEC 9.5 的 panel),官方面板是 null。 */
    plugin: xyz.linplayer.app.ui.plugin.PlayerSurfaceInfo? = null,
    plugins: List<xyz.linplayer.app.ui.plugin.PlayerSurfaceInfo> = emptyList(),
) {
    overlay.open {
        if (plugin != null) {
            TvSidePanel(plugin.title.ifBlank { plugin.target }, { overlay.close() }) {
                xyz.linplayer.app.ui.plugin.PluginSurface(
                    plugin.pluginId, plugin.target, "panel",
                    modifier = Modifier.fillMaxWidth(),
                    focusNs = "${plugin.pluginId}/${plugin.target}.",
                )
            }
            return@open
        }
        when (which) {
            "sub" -> TrackPanel("sub", app, scope, overlay, ui, exo, ctl.trackPicked, ctl.pickTrack, ctl.pickExoTrack, ctl.observeTracks)
            "audio" -> TrackPanel("audio", app, scope, overlay, ui, exo, ctl.trackPicked, ctl.pickTrack, ctl.pickExoTrack, ctl.observeTracks)
            "danmaku" -> DanmakuPanel(app, scope, overlay, target)
            else -> MorePanel(app, scope, overlay, ui, target, engine, exo, ctl, plugins, if (which == "speed") "speed" else "")
        }
    }
}

private suspend fun refreshTracks(app: AppState, ui: PlayerUi) {
    ui.tracks = runCatching { app.call("player.tracks") }.getOrNull().arr().mapNotNull { it.obj() }
}

@Composable
private fun BoxScope.TrackPanel(kind: String, app: AppState, scope: CoroutineScope, overlay: Overlay, ui: PlayerUi, exo: ExoPlayer?, onPicked: (String) -> Unit, pickTrack: suspend (String, String) -> Unit, pickExoTrack: ((ExoPlayer, String, String) -> Unit)?, observeTracks: (List<JsonObject>) -> Unit) {
    val isSub = kind == "sub"
    var level by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (exo == null) { refreshTracks(app, ui); observeTracks(ui.tracks) }
        if (isSub && !SubStyle.loaded.value) SubStyle.load(app)
    }
    val t = tvType
    if (level == 1) {
        TvSidePanel("字幕样式", { overlay.close() }, back = { level = 0 }) {
            PanelItem("字号", value = "%.1fx".format(SubStyle.scale.doubleValue), step = true, focused = true,
                onStep = { d -> scope.launch { SubStyle.set(app, "scale", SubStyle.stepScale(SubStyle.scale.doubleValue, d > 0)) } })
            PanelItem("位置", value = SubStyle.position.intValue.toString(), step = true,
                onStep = { d -> scope.launch { SubStyle.set(app, "position", SubStyle.stepPos(SubStyle.position.intValue, d > 0)) } })
            PanelItem("描边", value = "%.1f".format(SubStyle.border.doubleValue), step = true, enabled = SubStyle.loaded.value,
                onStep = { d -> scope.launch { SubStyle.set(app, "border_size", SubStyle.stepBorder(SubStyle.border.doubleValue, d > 0)) } })
            PanelItem("粗体", switch = SubStyle.bold.value, enabled = SubStyle.loaded.value,
                onClick = { scope.launch { SubStyle.set(app, "bold", !SubStyle.bold.value) } })
            PanelItem("100 = 画面下沿。ASS 特效字幕自带样式,描边和粗体不生效;图形字幕改不了字号。", enabled = false)
        }
        return
    }
    TvSidePanel(if (isSub) "字幕" else "音轨", { overlay.close() }) {
        PanelGroup("轨道")
        if (exo != null) {
            val options = exoTracks(exo, if (isSub) "subtitle" else "audio")
            val cur = exoCurrent(exo, if (isSub) "subtitle" else "audio")
            if (options.isEmpty() || (isSub && options.size == 1)) PanelItem("这里没有可选项", enabled = false)
            options.forEach { (id, detail, label) ->
                PanelItem(label, sub = detail, selected = id == cur, focused = id == cur, onClick = {
                    onPicked(kind)
                    runCatching { if (pickExoTrack != null) pickExoTrack(exo, kind, id) else exoPick(exo, if (isSub) "subtitle" else "audio", id) }.onFailure { app.report(it) }
                    overlay.close()
                })
            }
        } else {
            val list = ui.tracks.filter { it.str("kind") == kind }
            val selected = list.firstOrNull { it.bool("selected") }?.str("id")
            if (list.isEmpty()) PanelItem("这里没有可选项", enabled = false)
            if (isSub && list.isNotEmpty()) PanelItem("关闭字幕", selected = selected == null, focused = selected == null, onClick = {
                onPicked(kind)
                scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { runCatching { pickTrack("sub", "") }; refreshTracks(app, ui) }
                overlay.close()
            })
            list.forEach { o ->
                val id = o.str("id") ?: return@forEach
                // 名称与说明行同手机字幕面板:图形字幕在说明里写明,样式改不了字号时用户看得出原因
                val language = xyz.linplayer.app.ui.player.trackLanguage(o.str("lang"), if (isSub) "subtitle" else "audio")
                val name = o.str("title")?.takeIf { it.isNotBlank() && it != o.str("lang") && (isSub || it != "未标注") } ?: language ?: "轨道"
                val detail = if (isSub) listOfNotNull(language?.takeUnless { it == name }, xyz.linplayer.app.ui.player.subtitleFormat(o.str("codec")),
                    if (o.bool("external")) "外挂" else "内嵌").joinToString(" · ") else language
                PanelItem(name, sub = detail, selected = id == selected, focused = id == selected, onClick = {
                    onPicked(kind)
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        runCatching { pickTrack(kind, id) }.onFailure { app.report(it) }
                        refreshTracks(app, ui)
                    }
                    overlay.close()
                })
            }
        }
        if (isSub) {
            PanelGroup("样式")
            PanelItem("字号", value = "%.1fx".format(SubStyle.scale.doubleValue), chevron = true, onClick = { level = 1 })
        }
    }
}

@Composable
private fun BoxScope.DanmakuPanel(app: AppState, scope: CoroutineScope, overlay: Overlay, target: TvRoute.Player) {
    var level by remember { mutableIntStateOf(0) }
    var advancedReturn by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf(target.title.substringBefore(" · ")) }
    var groups by remember { mutableStateOf<List<JsonObject>?>(null) }
    var status by remember { mutableStateOf("") }
    var anime by remember { mutableStateOf<Pair<String, JsonObject>?>(null) }
    var eps by remember { mutableStateOf<List<JsonObject>?>(null) }
    LaunchedEffect(Unit) { if (!DanmakuStyle.loaded.value) DanmakuStyle.load(app) }
    suspend fun search() {
        status = "搜索中…"; groups = null
        val r = runCatching { app.call("danmaku.search", args("keyword" to keyword.trim())) }
        r.onFailure { status = (it as? xyz.linplayer.app.core.CoreException)?.advice ?: "搜索失败" }
        groups = r.getOrNull().arr().mapNotNull { it.obj() }
        status = if (groups.orEmpty().sumOf { it["animes"].arr().size } == 0) "都没搜到。换个写法试试(原名往往比中文名好使)" else ""
    }
    when (level) {
        1 -> TvSidePanel("搜索弹幕", { overlay.close() }, back = { level = 0 }) {
            Box(Modifier.padding(horizontal = TvSp.x6)) {
                TvTextField(keyword, { keyword = it }, "片名", 218.dp, Modifier.memo("dm.kw", initial = true), onSubmit = {
                    if (it.isNotBlank()) scope.launch { search() }
                })
            }
            PanelItem("搜索", onClick = { scope.launch { search() } })
            if (status.isNotBlank()) PanelItem(status, enabled = false)
            groups.orEmpty().forEach { g ->
                PanelGroup(g.str("source_name") ?: "弹幕源")
                g.str("error")?.let { PanelItem("这个源报错了:$it", enabled = false) }
                g["animes"].arr().mapNotNull { it.obj() }.forEach { an ->
                    PanelItem(an.str("anime_title") ?: "作品", sub = an.str("type_description"), chevron = true, onClick = {
                        anime = (g.str("source_id") ?: "") to an
                        eps = an["episodes"].arr().mapNotNull { it.obj() }.ifEmpty { null }
                        level = 2
                        if (eps == null) scope.launch {
                            eps = runCatching {
                                app.call("danmaku.episodes", args("source_id" to (g.str("source_id") ?: ""), "anime_id" to (an.str("anime_id") ?: "")))
                            }.getOrNull().arr().mapNotNull { it.obj() }
                        }
                    })
                }
            }
        }
        2 -> TvSidePanel(anime?.second.str("anime_title") ?: "选集", { overlay.close() }, back = { level = 1 }) {
            val list = eps
            if (list == null) PanelItem("正在取集数…", enabled = false)
            else if (list.isEmpty()) PanelItem("这部作品下面没有集", enabled = false)
            list.orEmpty().forEach { ep ->
                PanelItem(ep.str("episode_title") ?: "第 ? 集", onClick = {
                    val sid = anime?.first ?: return@PanelItem
                    scope.launch {
                        val selected = anime?.second ?: return@launch
                        if (resolveDanmakuFor(app, target.itemId, danmakuSelection(sid, selected, ep), loud = true)) overlay.close()
                    }
                })
            }
        }
        3 -> TvSidePanel("更多设置", { overlay.close() }, back = { advancedReturn = true; level = 0 }) {
            listOf(
                Triple("粗体", "bold", DanmakuStyle.bold),
                Triple("合并重复", "merge", DanmakuStyle.merge),
                Triple("防止重叠", "prevent_overlap", DanmakuStyle.preventOverlap),
                Triple("进度条热力图", "heatmap", DanmakuStyle.heatmap),
            ).forEachIndexed { i, (label, key, state) ->
                PanelItem(label, switch = state.value, focused = i == 0, enabled = DanmakuStyle.loaded.value,
                    onClick = { scope.launch { DanmakuStyle.set(app, key) { !state.value } } })
            }
        }
        else -> TvSidePanel("弹幕", { overlay.close() }) {
            PanelGroup("当前视频")
            PanelItem("显示弹幕", switch = DanmakuStyle.enabled.value, focused = !advancedReturn, onClick = {
                scope.launch { toggleDanmaku(app, target.itemId, !DanmakuStyle.enabled.value) }
            })
            PanelItem("当前来源", value = DanmakuStyle.source.value.ifBlank { "未匹配" }, enabled = false)
            PanelItem("更换弹幕", chevron = true, onClick = { level = 1 })
            PanelItem("重新匹配这一集", onClick = { scope.launch { loadDanmakuFor(app, target.itemId, loud = true, ignoreMemory = true) } })
            PanelItem("清除当前匹配记忆", onClick = { scope.launch { resolveDanmakuFor(app, target.itemId, forget = true, loud = true) } })
            PanelItem("时间偏移", value = "%.1f秒".format(DanmakuStyle.offset.doubleValue), step = true,
                onStep = { d -> scope.launch { DanmakuStyle.setOffset(app, DanmakuStyle.offset.doubleValue + d * 0.5) } })
            PanelItem("重置弹幕时间", onClick = { scope.launch { DanmakuStyle.setOffset(app, 0.0) } })
            PanelGroup("显示设置")
            val ready = DanmakuStyle.loaded.value
            DanmakuStyle.loadError.value?.let {
                PanelItem(it, enabled = false)
                PanelItem("重试读取设置", onClick = { scope.launch { DanmakuStyle.load(app) } })
            }
            PanelItem("显示区域", value = DanmakuStyle.areaLabel(DanmakuStyle.area.doubleValue), enabled = ready, onClick = {
                scope.launch { DanmakuStyle.set(app, "area") { DanmakuStyle.nextArea(DanmakuStyle.area.doubleValue) } }
            })
            PanelItem("不透明度", value = "${kotlin.math.round(DanmakuStyle.opacity.doubleValue * 100).toInt()}%", enabled = ready,
                step = true, onStep = { d -> scope.launch { DanmakuStyle.set(app, "opacity") {
                    DanmakuStyle.step(DanmakuStyle.opacity.doubleValue, d > 0, .1, .1, 1.0) } } })
            PanelItem("字体大小", value = "${kotlin.math.round(DanmakuStyle.scale.doubleValue * 100).toInt()}%", enabled = ready,
                step = true, onStep = { d -> scope.launch { DanmakuStyle.set(app, "scale") {
                    DanmakuStyle.step(DanmakuStyle.scale.doubleValue, d > 0, .1, .1, 3.0) } } })
            PanelItem("弹幕速度", value = "%.1f×".format(DanmakuStyle.speed.doubleValue), enabled = ready,
                step = true, onStep = { d -> scope.launch { DanmakuStyle.set(app, "speed") {
                    DanmakuStyle.step(DanmakuStyle.speed.doubleValue, d > 0, .1, .1, 3.0) } } })
            PanelItem("更多设置", chevron = true, focused = advancedReturn, enabled = ready, onClick = { level = 3 })
        }
    }
}


@Composable
private fun BoxScope.MorePanel(
    app: AppState, scope: CoroutineScope, overlay: Overlay, ui: PlayerUi, target: TvRoute.Player,
    engine: String, exo: ExoPlayer?, ctl: PlayerCtl,
    plugins: List<xyz.linplayer.app.ui.plugin.PlayerSurfaceInfo>, initialLevel: String = "",
) {
    var level by remember { mutableStateOf(initialLevel) }
    val plugin = plugins.firstOrNull { xyz.linplayer.app.ui.plugin.pluginPanelKind(it) == level }
    if (plugin != null) {
        TvSidePanel(plugin.title.ifBlank { plugin.target }, { overlay.close() }, back = { level = "" }) {
            xyz.linplayer.app.ui.plugin.PluginSurface(plugin.pluginId, plugin.target, "panel",
                modifier = Modifier.fillMaxWidth(), focusNs = "${plugin.pluginId}/${plugin.target}.")
        }
        return
    }
    var skipIntro by remember { mutableStateOf<Boolean?>(null) }
    var skipOutro by remember { mutableStateOf<Boolean?>(null) }
    // 带 › 的项把当前值写在右边:不写的话,得点进二级才知道现在是哪一档
    var shaderName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        launch {
            val p = runCatching { app.call("player.getPlaybackPrefs") }.getOrNull().obj()
            skipIntro = p?.boolOrNull("skip_intro"); skipOutro = p?.boolOrNull("skip_outro")
        }
        if (exo == null) launch {
            shaderName = runCatching { app.call("player.shaderLevels") }.getOrNull().arr().mapNotNull { it.obj() }
                .firstOrNull { it.bool("selected") }.str("name")
        }
    }
    val back = { if (initialLevel == "speed") overlay.close() else level = "" }
    val local = target.localEntry || target.download
    when (level) {
        "ratio" -> TvSidePanel("画面比例", { overlay.close() }, back = back) {
            VideoFit.entries.forEach { f ->
                PanelItem(f.label, selected = f == ui.fit, focused = f == ui.fit, onClick = {
                    ui.fit = f
                    if (exo == null) scope.launch { runCatching { app.call("player.setAspectRatio", args("ratio" to f.mpvRatio)) } }
                    overlay.close()
                })
            }
        }
        "shader" -> ShaderLevels(app, scope, overlay, back)
        "version" -> TvSidePanel("版本", { overlay.close() }, back = back) {
            ui.versions.forEach { v ->
                val cur = v.id == ui.mediaSourceId
                PanelItem(v.name, value = resLabel(v), sub = specLabel(v), selected = cur, focused = cur, onClick = {
                    if (!cur) ctl.switchTo(target.copy(versionId = v.id, resumeAt = ui.position, fromStart = false))
                    else overlay.close()
                })
            }
        }
        "speed" -> TvSidePanel("倍速", { overlay.close() }, back = back) {
            (1..16).map { it * .25 }.forEach { v ->
                PanelItem("${v}×", selected = v == ui.speed, focused = v == ui.speed, onClick = { ctl.speed(v); overlay.close() })
            }
        }
        "chapter" -> TvSidePanel("章节", { overlay.close() }, back = back) {
            ui.chapters.forEachIndexed { i, (at, name) ->
                PanelItem(name.ifBlank { "章节 ${i + 1}" }, value = fmtTime(at), onClick = { ctl.seek(at); overlay.close() })
            }
        }
        "segments" -> MarkerPanel(app, scope, overlay, ui, target, ctl, back)
        "info" -> PlaybackInfo(app, overlay, back, exo, ui)
        else -> TvSidePanel("更多", { overlay.close() }) {
            PanelGroup("画面")
            PanelItem("画面比例", value = ui.fit.label, chevron = true, focused = true, onClick = { level = "ratio" })
            // 画面增强是 mpv 的着色器:Exo 那条路上挂上去是空转,而 setShaderLevel 照样返回成功
            if (exo == null) PanelItem("画面增强", value = shaderName, chevron = true, onClick = { level = "shader" })
            if (ui.versions.size > 1) PanelItem("版本", value = ui.versions.firstOrNull { it.id == ui.mediaSourceId }?.let(::resLabel), chevron = true, onClick = { level = "version" })
            PanelGroup("播放")
            ui.prev(target.itemId)?.let { previous ->
                PanelItem("上一集", onClick = { ctl.switchTo(TvRoute.Player(previous.id, cardTitleOf(previous))) })
            }
            if (!local && target.src == null) PanelItem("片头片尾", chevron = true, onClick = { level = "segments" })
            if (ui.chapters.isNotEmpty()) PanelItem("章节", chevron = true, onClick = { level = "chapter" })
            skipIntro?.let { on ->
                PanelItem("自动跳过片头", switch = on, onClick = {
                    skipIntro = !on
                    scope.launch { runCatching { app.call("player.setPlaybackPrefs", args("skip_intro" to !on)) }.onFailure { skipIntro = on; app.report(it) } }
                })
            }
            skipOutro?.let { on ->
                PanelItem("自动跳过片尾", switch = on, onClick = {
                    skipOutro = !on
                    scope.launch { runCatching { app.call("player.setPlaybackPrefs", args("skip_outro" to !on)) }.onFailure { skipOutro = on; app.report(it) } }
                })
            }
            PanelGroup("工具")
            if (!local) PanelItem(if (engine == "exo") "切换到 MPV 内核" else "切换到 Media3 内核", onClick = ctl.switchEngine)
            PanelItem("媒体信息", chevron = true, onClick = { level = "info" })
            plugins.forEach { p ->
                PanelItem(p.title.ifBlank { p.target }, chevron = true,
                    onClick = { level = xyz.linplayer.app.ui.plugin.pluginPanelKind(p) })
            }
        }
    }
}

@Composable
private fun BoxScope.MarkerPanel(app: AppState, scope: CoroutineScope, overlay: Overlay, ui: PlayerUi,
    target: TvRoute.Player, ctl: PlayerCtl, back: () -> Unit) {
    val markers = remember(app, target.itemId) { xyz.linplayer.app.ui.player.SkipMarkers(app, target.itemId) }
    var custom by remember { mutableStateOf(false) }
    LaunchedEffect(target.itemId) { markers.load(ui.duration) }
    fun mark(action: String) {
        val position = ui.position
        val duration = ui.duration
        scope.launch { markers.mark(action, position, duration, ctl.markersSaved, ctl.markersWriting) }
    }
    TvSidePanel("片头片尾", { overlay.close() }, back = back) {
        PanelItem("应用范围", value = if (markers.scope == "season") "本季" else "仅本集",
            enabled = markers.data != null && markers.canSeason, onClick = {
                scope.launch { markers.load(ui.duration, if (markers.scope == "season") "episode" else "season") }
            })
        markers.error?.let {
            PanelItem(it, enabled = false)
            PanelItem("重试", enabled = !markers.busy, onClick = { scope.launch { markers.load(ui.duration) } })
        }
        PanelItem("当前位置设为片头结束", sub = "片头 · ${markers.label("intro")}", focused = true,
            enabled = markers.data != null && ui.duration > 0, onClick = { mark("intro_end") })
        PanelItem("当前位置设为片尾开始", sub = "片尾 · ${markers.label("outro")}",
            enabled = markers.data != null && ui.duration > 0, onClick = { mark("outro_start") })
        PanelItem("自定义区间", value = if (custom) "收起" else "展开", onClick = { custom = !custom })
        if (custom) {
            PanelItem("当前位置设为片头开始", enabled = markers.data != null && ui.duration > 0, onClick = { mark("intro_start") })
            PanelItem("当前位置设为片尾结束", sub = "保留片尾后的内容", enabled = markers.data != null && ui.duration > 0, onClick = { mark("outro_end") })
        }
        PanelItem(if (markers.scope == "season") "清除本季标记" else "清除本集标记", enabled = markers.data != null, onClick = { mark("clear") })
        if (markers.busy) PanelItem("正在保存或读取…", enabled = false)
        if (markers.data.bool("legacy")) PanelItem("未覆盖的片段沿用旧整剧标记", enabled = false)
    }
}

/** 画面增强:`will_run == false` 的档**不列**;设了之后看 `reverted`,被退回就 Toast 核心层给的原话【继承 PC A-2】。 */
@Composable
private fun BoxScope.ShaderLevels(app: AppState, scope: CoroutineScope, overlay: Overlay, back: () -> Unit) {
    var levels by remember { mutableStateOf<List<JsonObject>?>(null) }
    var interp by remember { mutableStateOf<JsonObject?>(null) }
    val displayHz = androidx.compose.ui.platform.LocalView.current.display?.refreshRate ?: 0f
    LaunchedEffect(Unit) {
        levels = runCatching { app.call("player.shaderLevels") }.getOrNull().arr().mapNotNull { it.obj() }
        interp = runCatching { app.call("player.interpLevels", args("display_hz" to displayHz.toDouble())) }.getOrNull().obj()
    }
    TvSidePanel("画面增强", { overlay.close() }, back = back) {
        val list = levels
        if (list == null) PanelItem("正在取档位…", enabled = false)
        list.orEmpty().filter { it.str("id") == "off" || it.boolOrNull("will_run") != false }.forEach { o ->
            val id = o.str("id") ?: return@forEach
            PanelItem(o.str("name") ?: id, sub = o.str("group"), selected = o.bool("selected"), focused = o.bool("selected"), onClick = {
                scope.launch {
                    val r = runCatching { app.call("player.setShaderLevel", args("level" to id)) }.onFailure { app.report(it) }.getOrNull().obj()
                    if (r.bool("reverted")) app.toast(r.str("note") ?: "这档在这台机器上跑不起来", ToastKind.Error)
                    overlay.close()
                }
            })
        }
        /* 补帧与手机同一套:只列 will_run 不为 false 的档,用不了时留一行原因(OpenCL 因机型而异)。
           挂上只说明滤镜收下了;跑不动由 player.interpReverted 事件回来说(TvPlayerPage 订阅)。 */
        interp?.let { st ->
            PanelGroup("补帧")
            if (!st.bool("supported")) PanelItem(st.str("reason") ?: "这台设备用不了补帧", enabled = false)
            else st["levels"].arr().mapNotNull { it.obj() }.filter { it.str("id") == "off" || it.boolOrNull("will_run") != false }.forEach { o ->
                val id = o.str("id") ?: return@forEach
                val fps = o.str("fps")
                PanelItem((o.str("name") ?: "档位") + if (fps.isNullOrEmpty()) "" else " · $fps", sub = o.str("group"),
                    selected = o.bool("selected"), onClick = {
                    scope.launch {
                        val r = runCatching { app.call("player.setInterpLevel", args("level" to id, "display_hz" to displayHz.toDouble())) }
                            .onFailure { app.report(it) }.getOrNull().obj()
                        if (r.bool("reverted")) app.toast(r.str("note") ?: "补帧开不了", ToastKind.Error)
                        else if (r != null && id != "off") app.toast("补帧已开启,光流起来要一两秒")
                        overlay.close()
                    }
                })
            }
        }
    }
}

@Composable
private fun BoxScope.PlaybackInfo(app: AppState, overlay: Overlay, back: () -> Unit, exo: ExoPlayer?, ui: PlayerUi) {
    var opts by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(Unit) { if (exo == null) opts = runCatching { app.call("player.opts") }.getOrNull().obj() }
    TvSidePanel("媒体信息", { overlay.close() }, back = back, scrollWithArrows = true) {
        // 与手机同一份字段表,两个内核都列得出参数
        val rows = xyz.linplayer.app.ui.player.playbackMediaInfo(exo, opts)
        // 只读行不可聚焦;放一个可聚焦的首项,面板才圈得住焦点
        PanelItem("返回", chevron = false, focused = true, onClick = back)
        if (ui.spec.isNotBlank()) PanelItem("当前版本", value = ui.spec, enabled = false)
        rows.forEach { (k, v) -> PanelItem(k, value = v, enabled = false) }
    }
}
